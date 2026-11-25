package app.offline.escconfigurator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Answers the web app's firmware related requests (GitHub release lists and
 * hex files) so that it keeps working without a connection.
 *
 * Every file has a key derived from its URL, and is looked up in
 *
 *  1. the disk cache (things downloaded on this phone earlier),
 *  2. the firmware pack bundled into the APK (assets/offline),
 *  3. the network - whatever is downloaded lands in the disk cache.
 *
 * Release lists are the exception: they change, so the network is asked first
 * and the stored copy is the fallback. Without a connection the list is cut
 * down to the releases that have files stored on the phone, so the firmware
 * selector only offers what can actually be flashed.
 *
 * The same key scheme is used by tools/fetch_firmware.py which builds the
 * bundled pack - keep the two in sync.
 */
class OfflineStore(private val context: Context) {
    private val cacheRoot = File(context.filesDir, ROOT)

    /**
     * @return a response, or null if the URL is not a firmware source
     */
    fun handle(requested: Uri): WebResourceResponse? {
        val uri = unwrapCorsProxy(requested) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || host !in HOSTS) {
            return null
        }

        val path = uri.path ?: return null
        val releases = if (host == API_HOST) RELEASES_PATH.matchEntire(path) else null

        return try {
            if (releases != null) {
                val (owner, repo) = releases.destructured
                serveReleases(uri, owner, repo)
            } else {
                serveFile(uri, keyFor(host + path) ?: return null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Serving $uri failed", e)
            failure(HttpURLConnection.HTTP_GATEWAY_TIMEOUT)
        }
    }

    /**
     * The web app sends some requests through a CORS proxy
     * (https://proxy/?https://actual/url). Native code has no need for it.
     */
    private fun unwrapCorsProxy(uri: Uri): Uri? {
        if (uri.host?.lowercase() != CORS_PROXY_HOST) {
            return uri
        }

        val inner = uri.toString().substringAfter('?', "")

        return if (inner.startsWith("https://")) Uri.parse(inner) else null
    }

    /* ---------------------------------------------------------------------
     * Plain files (hex files): immutable, stored copy wins
     * ------------------------------------------------------------------- */

    private fun serveFile(uri: Uri, key: String): WebResourceResponse {
        openStored(key)?.let { return success(it, mimeFor(key)) }

        if (!isOnline()) {
            return failure(HttpURLConnection.HTTP_GATEWAY_TIMEOUT)
        }

        val target = File(cacheRoot, key)
        val status = try {
            download(uri.toString(), target) { input, file ->
                file.outputStream().use { input.copyTo(it) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Downloading $uri failed", e)
            HttpURLConnection.HTTP_GATEWAY_TIMEOUT
        }

        return if (status == HttpURLConnection.HTTP_OK) {
            success(FileInputStream(target), mimeFor(key))
        } else {
            failure(status)
        }
    }

    /* ---------------------------------------------------------------------
     * Release lists: network first, stored copy as fallback
     * ------------------------------------------------------------------- */

    private fun serveReleases(uri: Uri, owner: String, repo: String): WebResourceResponse {
        val key = keyFor("$API_HOST/repos/$owner/$repo/releases.json")
            ?: return failure(HttpURLConnection.HTTP_BAD_REQUEST)
        val target = File(cacheRoot, key)

        if (isOnline()) {
            try {
                val status = download(uri.toString(), target, API_ACCEPT) { input, file ->
                    slimReleases(input, file)
                }

                if (status == HttpURLConnection.HTTP_OK) {
                    return success(FileInputStream(target), MIME_JSON)
                }

                // The API answered with an error (rate limit, most likely).
                // Downloads still work, so offer everything we know about.
                Log.w(TAG, "Release list for $owner/$repo: HTTP $status")
                openStored(key)?.let { return success(it, MIME_JSON) }

                return failure(status)
            } catch (e: IOException) {
                Log.w(TAG, "Fetching release list for $owner/$repo failed", e)
            }
        }

        val stored = openStored(key) ?: return failure(HttpURLConnection.HTTP_GATEWAY_TIMEOUT)
        val all = JSONArray(stored.use { it.readBytes().toString(Charsets.UTF_8) })
        val available = JSONArray()
        for (i in 0 until all.length()) {
            val release = all.getJSONObject(i)
            val tag = release.optString("tag_name")
            val folder = keyFor("github.com/$owner/$repo/releases/download/$tag")
            if (folder != null && hasStoredFiles(folder)) {
                available.put(release)
            }
        }

        return success(ByteArrayInputStream(available.toString().toByteArray()), MIME_JSON)
    }

    /**
     * GitHub release lists are huge (megabytes, mostly asset details), the web
     * app only looks at a handful of fields. Store just those.
     */
    private fun slimReleases(input: InputStream, target: File) {
        JsonReader(input.reader()).use { reader ->
            JsonWriter(target.writer()).use { writer ->
                reader.beginArray()
                writer.beginArray()

                while (reader.hasNext()) {
                    reader.beginObject()
                    writer.beginObject()

                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        when {
                            name == "assets" -> {
                                var count = 0
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.skipValue()
                                    count += 1
                                }
                                reader.endArray()

                                // Only "has assets" matters
                                writer.name(name).beginArray()
                                if (count > 0) {
                                    writer.beginObject().endObject()
                                }
                                writer.endArray()
                            }

                            name in RELEASE_FIELDS -> when (reader.peek()) {
                                JsonToken.STRING -> writer.name(name).value(reader.nextString())
                                JsonToken.BOOLEAN -> writer.name(name).value(reader.nextBoolean())
                                else -> reader.skipValue()
                            }

                            else -> reader.skipValue()
                        }
                    }

                    reader.endObject()
                    writer.endObject()
                }

                reader.endArray()
                writer.endArray()
            }
        }
    }

    /* ---------------------------------------------------------------------
     * Storage
     * ------------------------------------------------------------------- */

    /**
     * Turns "host/path" into a relative file path that is safe both as an
     * APK asset name and on disk.
     */
    private fun keyFor(location: String): String? {
        val key = location.replace(UNSAFE_CHARS, "_")
        val valid = key.split('/').none { it.isEmpty() || it.all { c -> c == '.' } }

        return if (valid) key else null
    }

    private fun openStored(key: String): InputStream? {
        val cached = File(cacheRoot, key)
        if (cached.isFile) {
            return FileInputStream(cached)
        }

        return try {
            context.assets.open("$ROOT/$key")
        } catch (e: IOException) {
            null
        }
    }

    private fun hasStoredFiles(folderKey: String): Boolean {
        if (File(cacheRoot, folderKey).list()?.isNotEmpty() == true) {
            return true
        }

        return try {
            context.assets.list("$ROOT/$folderKey")?.isNotEmpty() == true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Downloads [url] into [target]. The file only appears under its final
     * name once it was written completely.
     *
     * @return the HTTP status, the file was stored only on 200
     */
    private fun download(
        url: String,
        target: File,
        accept: String? = null,
        write: (InputStream, File) -> Unit,
    ): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            accept?.let { connection.setRequestProperty("Accept", it) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return status
            }

            val folder = target.parentFile ?: throw IOException("Invalid target $target")
            if (!folder.isDirectory && !folder.mkdirs()) {
                throw IOException("Could not create $folder")
            }

            val partial = File.createTempFile("download", ".part", folder)
            try {
                connection.inputStream.use { write(it, partial) }

                if (!partial.renameTo(target)) {
                    target.delete()
                    if (!partial.renameTo(target)) {
                        throw IOException("Could not store $target")
                    }
                }
            } catch (e: Exception) {
                partial.delete()
                throw if (e is IOException) e else IOException(e)
            }

            return status
        } finally {
            connection.disconnect()
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /* ---------------------------------------------------------------------
     * Responses
     * ------------------------------------------------------------------- */

    private fun mimeFor(key: String): String =
        if (key.endsWith(".json", ignoreCase = true)) MIME_JSON else "text/plain"

    private fun success(body: InputStream, mime: String) =
        WebResourceResponse(mime, "utf-8", HttpURLConnection.HTTP_OK, "OK", RESPONSE_HEADERS, body)

    private fun failure(status: Int) = WebResourceResponse(
        "text/plain",
        "utf-8",
        if (status in 400..599) status else HttpURLConnection.HTTP_BAD_GATEWAY,
        "Unavailable",
        RESPONSE_HEADERS,
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val TAG = "OfflineStore"

        // Folder name, both below the app's files dir and in the APK assets
        private const val ROOT = "offline"

        private const val API_HOST = "api.github.com"
        private const val CORS_PROXY_HOST = "cors.bubblesort.me"
        private val HOSTS = setOf(API_HOST, "github.com", "raw.githubusercontent.com")

        private val RELEASES_PATH = Regex("^/repos/([^/]+)/([^/]+)/releases/?$")
        private val RELEASE_FIELDS = setOf("tag_name", "name", "prerelease", "published_at")
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._/-]")

        private const val MIME_JSON = "application/json"
        private const val API_ACCEPT = "application/vnd.github+json"
        private const val USER_AGENT = "esc-configurator-android"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 20000

        // The page lives on another origin, it may read these only with CORS
        private val RESPONSE_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store",
        )
    }
}

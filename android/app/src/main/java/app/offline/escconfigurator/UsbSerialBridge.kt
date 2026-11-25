package app.offline.escconfigurator

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Native half of android-bridge.js.
 *
 * Exposed to the page as `window.EscAndroid`. It lets the page talk to USB
 * serial devices (flight controllers, USB-UART adapters) through the Android
 * USB host API, which is what backs `navigator.serial` inside the WebView.
 *
 * Calls coming from JavaScript carry a call id and are answered through
 * `window.__escAndroid.result(id, error, value)`.
 */
class UsbSerialBridge(
    private val activity: MainActivity,
    private val webView: WebView,
) {
    private class Connection(val port: UsbSerialPort) {
        var ioManager: SerialInputOutputManager? = null
    }

    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Opening, writing and closing happen strictly in order on this thread.
    private val io = Executors.newSingleThreadExecutor()
    private val prober = UsbSerialProber.getDefaultProber()

    private val connections = ConcurrentHashMap<Int, Connection>()

    // Devices the page already knows about (listed or announced).
    private val announced = ConcurrentHashMap.newKeySet<Int>()

    // Everything below is only touched on the main thread.
    private val attached = HashSet<Int>()
    private val permissionAsked = HashSet<Int>()
    private val permissionWaiters = HashMap<Int, MutableList<(Boolean) -> Unit>>()
    private var pageReady = false
    private var resumed = false
    private var destroyed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> onPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scanDevices()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /* ---------------------------------------------------------------------
     * Lifecycle, driven by the activity
     * ------------------------------------------------------------------- */

    /** A (new) page is loading: whatever the old page had open is stale. */
    fun onPageStarted() {
        pageReady = false
        announced.clear()
        io.execute { closeAll() }
    }

    fun onPageReady() {
        pageReady = true

        // The app was opened by hand with a device already plugged in: nobody
        // asked for access yet. (For devices plugged in later Android shows
        // its own "open with this app" dialog, which also grants access.)
        scanDevices(askPermission = true)
    }

    fun onResume() {
        resumed = true
        scanDevices()
    }

    fun onPause() {
        resumed = false
    }

    fun destroy() {
        destroyed = true
        activity.unregisterReceiver(receiver)
        io.execute { closeAll() }
        io.shutdown()
    }

    /* ---------------------------------------------------------------------
     * Device discovery and permissions
     * ------------------------------------------------------------------- */

    private fun driverFor(device: UsbDevice): UsbSerialDriver? {
        prober.probeDevice(device)?.let { return it }

        // Unknown vendor/product id, but it has a CDC data interface: treat it
        // as a virtual COM port. Covers flight controllers with exotic ids.
        val hasCdcData = (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
        }

        return if (hasCdcData) CdcAcmSerialDriver(device) else null
    }

    private fun serialDevices(): List<UsbDevice> =
        usbManager.deviceList.values
            .filter { driverFor(it) != null }
            .sortedBy { it.deviceId }

    private fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    private fun describe(device: UsbDevice): JSONObject = JSONObject()
        .put("id", device.deviceId)
        .put("vendorId", device.vendorId)
        .put("productId", device.productId)
        .put("name", label(device))
        .put("hasPermission", usbManager.hasPermission(device))

    private fun label(device: UsbDevice): String {
        val id = "%04x:%04x".format(device.vendorId, device.productId)
        val name = device.productName?.trim().orEmpty()

        return if (name.isEmpty()) id else "$name ($id)"
    }

    /**
     * Brings the page up to date with what is plugged in. Called whenever
     * something USB related might have changed. Main thread only.
     */
    fun scanDevices(askPermission: Boolean = false) {
        if (destroyed) {
            return
        }

        val devices = serialDevices()
        val current = devices.map { it.deviceId }.toSet()

        for (deviceId in attached - current) {
            onDeviceGone(deviceId)
        }
        attached.clear()
        attached.addAll(current)

        if (!pageReady) {
            return
        }

        for (device in devices) {
            announce(device, askPermission)
        }
    }

    private fun announce(device: UsbDevice, askPermission: Boolean) {
        val deviceId = device.deviceId
        if (announced.contains(deviceId)) {
            return
        }

        if (usbManager.hasPermission(device)) {
            announced.add(deviceId)
            runJs("window.__escAndroid&&window.__escAndroid.attached(${describe(device)})")
        } else if (askPermission && resumed && permissionAsked.add(deviceId)) {
            // Ask once per plug-in, the user can still ask again from the UI.
            ensurePermission(device) { granted ->
                if (granted) {
                    announce(device, askPermission = false)
                }
            }
        }
    }

    private fun onDeviceGone(deviceId: Int) {
        permissionAsked.remove(deviceId)
        permissionWaiters.remove(deviceId)?.forEach { it(false) }
        io.execute { closeConnection(deviceId) }

        if (announced.remove(deviceId)) {
            runJs("window.__escAndroid&&window.__escAndroid.detached($deviceId)")
        }
    }

    private fun ensurePermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }

        val waiters = permissionWaiters.getOrPut(device.deviceId) { mutableListOf() }
        waiters.add(callback)
        if (waiters.size > 1) {
            // A request for this device is already on screen
            return
        }

        // The system adds the result as extras, so the intent has to be mutable
        // and (since Android 14) explicitly limited to this app.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(activity, 0, intent, flags)

        try {
            usbManager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Requesting USB permission failed", e)
            permissionWaiters.remove(device.deviceId)?.forEach { it(false) }
        }
    }

    private fun onPermissionResult(intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        val deviceIds = if (device != null) listOf(device.deviceId) else permissionWaiters.keys.toList()

        for (deviceId in deviceIds) {
            val current = findDevice(deviceId)
            val granted = current != null && usbManager.hasPermission(current)
            permissionWaiters.remove(deviceId)?.forEach { it(granted) }
        }
    }

    /* ---------------------------------------------------------------------
     * JavaScript interface - these run on a WebView owned thread
     * ------------------------------------------------------------------- */

    @JavascriptInterface
    fun listPorts(): String {
        val ports = JSONArray()
        for (device in serialDevices()) {
            val port = describe(device)
            if (port.getBoolean("hasPermission")) {
                announced.add(device.deviceId)
            }
            ports.put(port)
        }

        return ports.toString()
    }

    @JavascriptInterface
    fun requestPort(callId: Int) {
        mainHandler.post {
            val devices = serialDevices()
            when {
                devices.isEmpty() -> {
                    Toast.makeText(activity, R.string.no_usb_device, Toast.LENGTH_LONG).show()
                    reject(callId, "No USB serial device connected")
                }

                devices.size == 1 -> grantPort(callId, devices[0])

                else -> AlertDialog.Builder(activity)
                    .setTitle(R.string.select_usb_device)
                    .setItems(devices.map { label(it) }.toTypedArray()) { _, which ->
                        grantPort(callId, devices[which])
                    }
                    .setOnCancelListener { reject(callId, "No port selected by the user") }
                    .show()
            }
        }
    }

    private fun grantPort(callId: Int, device: UsbDevice) {
        ensurePermission(device) { granted ->
            if (granted) {
                announced.add(device.deviceId)
                resolve(callId, describe(device).toString())
            } else {
                reject(callId, "USB permission denied")
            }
        }
    }

    @JavascriptInterface
    fun open(callId: Int, deviceId: Int, baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        io.execute {
            try {
                if (connections.containsKey(deviceId)) {
                    throw IOException("Port is already open")
                }

                val device = findDevice(deviceId) ?: throw IOException("Device is not connected")
                val driver = driverFor(device) ?: throw IOException("No serial driver for this device")
                if (!usbManager.hasPermission(device)) {
                    throw IOException("No permission to access the device")
                }

                val usbConnection = usbManager.openDevice(device)
                    ?: throw IOException("Could not open the device")
                val port = driver.ports[0]

                try {
                    port.open(usbConnection)
                    port.setParameters(baudRate, dataBits, toStopBits(stopBits), toParity(parity))
                } catch (e: Exception) {
                    closeQuietly(port)
                    throw e
                }

                try {
                    // Virtual COM ports only start talking once DTR is raised
                    port.setDTR(true)
                    port.setRTS(true)
                } catch (e: Exception) {
                    // Not every adapter has these lines
                }

                val connection = Connection(port)
                val ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
                        runJs("window.__escAndroid&&window.__escAndroid.data($deviceId,'$encoded')")
                    }

                    override fun onRunError(e: Exception) {
                        // Only an error if nobody closed the port on purpose
                        if (connections.remove(deviceId, connection)) {
                            Log.w(TAG, "Reading from device $deviceId failed", e)
                            closeQuietly(port)
                            onOpenCountChanged()

                            val message = JSONObject.quote(e.message ?: "Connection lost")
                            runJs("window.__escAndroid&&window.__escAndroid.ioError($deviceId,$message)")
                        }
                    }
                })

                connection.ioManager = ioManager
                connections[deviceId] = connection
                ioManager.start()
                onOpenCountChanged()

                resolve(callId)
            } catch (e: Exception) {
                Log.w(TAG, "Opening device $deviceId failed", e)
                reject(callId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    @JavascriptInterface
    fun write(callId: Int, deviceId: Int, base64: String) {
        io.execute {
            try {
                val connection = connections[deviceId] ?: throw IOException("Port is not open")
                connection.port.write(Base64.decode(base64, Base64.DEFAULT), WRITE_TIMEOUT_MS)
                resolve(callId)
            } catch (e: Exception) {
                reject(callId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    @JavascriptInterface
    fun close(callId: Int, deviceId: Int) {
        io.execute {
            closeConnection(deviceId)
            resolve(callId)
        }
    }

    @JavascriptInterface
    fun saveFile(name: String, base64: String) {
        val data = Base64.decode(base64, Base64.DEFAULT)
        mainHandler.post { activity.saveFile(name, data) }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        mainHandler.post { activity.openExternal(url) }
    }

    /** The page changed its theme; an empty string stands for the default. */
    @JavascriptInterface
    fun setChromeColor(color: String) {
        mainHandler.post {
            if (!destroyed) {
                activity.setChromeColor(color)
            }
        }
    }

    /* ---------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------- */

    private fun toStopBits(stopBits: Int): Int =
        if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1

    private fun toParity(parity: String): Int = when (parity) {
        "even" -> UsbSerialPort.PARITY_EVEN
        "odd" -> UsbSerialPort.PARITY_ODD
        else -> UsbSerialPort.PARITY_NONE
    }

    private fun closeQuietly(port: UsbSerialPort) {
        try {
            port.close()
        } catch (e: Exception) {
            // Already closed or the device is gone
        }
    }

    private fun closeConnection(deviceId: Int) {
        val connection = connections.remove(deviceId) ?: return

        connection.ioManager?.let {
            it.listener = null
            it.stop()
        }
        closeQuietly(connection.port)
        onOpenCountChanged()
    }

    private fun closeAll() {
        for (deviceId in connections.keys.toList()) {
            closeConnection(deviceId)
        }
    }

    private fun onOpenCountChanged() {
        mainHandler.post {
            if (!destroyed) {
                activity.onSerialActive(connections.isNotEmpty())
            }
        }
    }

    private fun resolve(callId: Int, valueJson: String = "null") {
        runJs("window.__escAndroid&&window.__escAndroid.result($callId,null,$valueJson)")
    }

    private fun reject(callId: Int, message: String) {
        runJs("window.__escAndroid&&window.__escAndroid.result($callId,${JSONObject.quote(message)})")
    }

    private fun runJs(script: String) {
        mainHandler.post {
            if (!destroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    companion object {
        const val JS_NAME = "EscAndroid"

        private const val TAG = "UsbSerialBridge"
        private const val ACTION_USB_PERMISSION = "app.offline.escconfigurator.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 2000
    }
}

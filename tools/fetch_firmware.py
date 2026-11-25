"""Builds the offline firmware pack that gets bundled into the APK.

Downloads the release lists and hex files the web app would otherwise fetch
from GitHub at runtime and stores them under firmware-pack/, using the same
"key" scheme as OfflineStore.kt (keep the two in sync):

    key = host + url-decoded path, every char outside [A-Za-z0-9._/-] -> "_"

What is bundled by default:

    Bluejay   - newest stable release (plus the list of the last 8 releases)
    AM32      - newest stable release (plus the list of releases)
    BLHeli_S  - 16.7

Everything else is still available in the app while the phone is online and
is then kept on the phone.

Usage:
    python tools/fetch_firmware.py [--bluejay N] [--am32 N] [--blheli-s KEY ...]
                                   [--dry-run]

Set GITHUB_TOKEN to avoid the anonymous GitHub API rate limit (optional).
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "firmware-pack"
SOURCES = ROOT / "web" / "src" / "sources"

USER_AGENT = "esc-configurator-android-pack"
RELEASE_FIELDS = ("tag_name", "name", "prerelease", "published_at")

# (repository, per_page used by the web app - only cosmetic here)
BLUEJAY = ("bird-sanctuary/bluejay", 8)
AM32 = ("am32-firmware/AM32", 100)

def key_for(url):
    parts = urllib.parse.urlsplit(url)
    location = parts.netloc.lower() + urllib.parse.unquote(parts.path)

    return re.sub(r"[^A-Za-z0-9._/-]", "_", location)


def request(url, accept=None):
    headers = {"User-Agent": USER_AGENT}
    if accept:
        headers["Accept"] = accept

    token = os.environ.get("GITHUB_TOKEN")
    if token and urllib.parse.urlsplit(url).netloc == "api.github.com":
        headers["Authorization"] = f"Bearer {token}"

    # The web app builds some URLs with plain spaces in them
    safe_url = urllib.parse.quote(url, safe=":/?&=%")

    return urllib.request.urlopen(urllib.request.Request(safe_url, headers=headers), timeout=60)


def fetch_releases(repo, per_page):
    """Returns the full release list and stores the slimmed down copy."""
    url = f"https://api.github.com/repos/{repo}/releases?per_page={per_page}&page=1"
    with request(url, "application/vnd.github+json") as response:
        releases = json.load(response)

    slim = []
    for release in releases:
        entry = {field: release[field] for field in RELEASE_FIELDS if release.get(field) is not None}
        # The web app only checks whether a release has assets at all
        entry["assets"] = [{}] if release.get("assets") else []
        slim.append(entry)

    target = PACK / "api.github.com" / "repos" / repo / "releases.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(slim, separators=(",", ":")), encoding="utf-8")

    return releases


def hex_assets(releases, count):
    """Download URLs of all hex files of the newest `count` stable releases."""
    stable = [r for r in releases if not r.get("prerelease") and not r.get("draft") and r.get("assets")]
    stable.sort(key=lambda r: r.get("published_at") or "", reverse=True)

    urls = []
    for release in stable[:count]:
        files = [a for a in release["assets"] if a["name"].lower().endswith(".hex")]
        size = sum(a.get("size", 0) for a in files)
        print(f"  {release['tag_name']}: {len(files)} hex files, {size / 1e6:.1f} MB")
        urls += [a["browser_download_url"] for a in files]

    return urls


def blheli_s_urls(keys):
    versions = json.loads((SOURCES / "BlheliS" / "versions.json").read_text(encoding="utf-8"))
    layouts = json.loads((SOURCES / "BlheliS" / "escs.json").read_text(encoding="utf-8"))["layouts"]

    # Same transformation as getFirmwareUrl() in sources/Blheli/index.js
    names = sorted({re.sub(r"[\s-]", "_", layout["name"]).upper() for layout in layouts.values()})

    urls = []
    for key in keys:
        version = next((v for v in versions if v["key"] == key), None)
        if version is None:
            sys.exit(f"Unknown BLHeli_S version '{key}', known: {', '.join(v['key'] for v in versions)}")

        print(f"  {version['name']}: up to {len(names)} hex files")
        urls += [version["url"].replace("{0}", name) for name in names]

    return urls


def download(url):
    """Returns (status, bytes) - status is 'ok', 'cached', 'missing' or 'failed'."""
    target = PACK / key_for(url)
    if target.is_file() and target.stat().st_size > 0:
        return "cached", target.stat().st_size

    for attempt in range(3):
        try:
            with request(url) as response:
                data = response.read()
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                # Not every layout exists for every firmware version
                return "missing", 0
            error = e
        except Exception as e:  # noqa: BLE001 - network hiccup, retry
            error = e
    else:
        print(f"  FAILED {url}: {error}", file=sys.stderr)
        return "failed", 0

    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    partial.write_bytes(data)
    partial.replace(target)

    return "ok", len(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bluejay", type=int, default=1, metavar="N", help="stable Bluejay releases to bundle")
    parser.add_argument("--am32", type=int, default=1, metavar="N", help="stable AM32 releases to bundle")
    parser.add_argument("--blheli-s", nargs="*", default=["16.7"], metavar="KEY",
                        help="BLHeli_S versions to bundle (keys from web/src/sources/BlheliS/versions.json)")
    parser.add_argument("--dry-run", action="store_true", help="only show what would be downloaded")
    args = parser.parse_args()

    urls = []

    print("Bluejay")
    urls += hex_assets(fetch_releases(*BLUEJAY), args.bluejay)
    print("AM32")
    urls += hex_assets(fetch_releases(*AM32), args.am32)
    print("BLHeli_S")
    urls += blheli_s_urls(args.blheli_s)

    if args.dry_run:
        print(f"{len(urls)} files would be downloaded")
        return

    totals = {"ok": 0, "cached": 0, "missing": 0, "failed": 0}
    size = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        for done, (status, length) in enumerate(pool.map(download, urls), start=1):
            totals[status] += 1
            size += length
            if done % 200 == 0:
                print(f"  {done}/{len(urls)}")

    print(
        f"{totals['ok']} downloaded, {totals['cached']} already there, "
        f"{totals['missing']} do not exist upstream, {totals['failed']} failed - "
        f"{size / 1e6:.1f} MB in {PACK}"
    )
    if totals["failed"]:
        sys.exit(1)


if __name__ == "__main__":
    main()

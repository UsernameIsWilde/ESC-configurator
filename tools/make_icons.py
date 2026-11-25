"""Generates the Android launcher icons from the web app's logo files.

Usage: python tools/make_icons.py   (needs Pillow)
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / ".toolchain" / "pylib"))

from PIL import Image  # noqa: E402

RES = ROOT / "android" / "app" / "src" / "main" / "res"
PUBLIC = ROOT / "web" / "public"

# density -> (legacy icon size, adaptive layer size)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def resized(source, size):
    return Image.open(source).convert("RGBA").resize((size, size), Image.LANCZOS)


for density, (legacy, layer) in DENSITIES.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)

    resized(PUBLIC / "logo512.png", legacy).save(folder / "ic_launcher.png")
    # maskable.png already keeps the logo inside the adaptive icon safe zone
    resized(PUBLIC / "maskable.png", layer).save(folder / "ic_launcher_foreground.png")

print("icons written to", RES)

#!/usr/bin/env python3
"""Generate the download site's favicons and Open Graph images (docs/chromite/distribution.md "Site").

Why this script exists
----------------------
The site needs the browser icons (favicon.ico, PNG icons, the Apple touch icon) and one 1200x630 share
image per language. They are derived from the same vector sources as the app icon, so this script reuses
gen_app_icons.py (the size-tier tile drawings, resvg rendering) instead of resizing one bitmap: a 16 px
favicon is the 16 px tier drawn on its own pixel grid, the same image the app uses for 16 px.

The share image is the logo lockup from chromite_logo_pack/, one line of copy in Pretendard (the site's
font) and a crop of the real app screenshot that the home page already shows (site/assets/shots). The copy
names the driver count, not a list of databases: a list of the pre-installed ones read as the only
databases Chromite supports. Colours
are the app's design tokens (docs/chromite/design-spec.md): canvas #F7F8FA, ink #1E1F22, secondary text
#6C707E, border #DFE1E5. Flat colours only, no gradient washes.

Outputs
-------
site/favicon.ico                         16, 32 and 48 px (PNG entries; browsers read them, unlike IconExe)
site/assets/icons/icon-32.png, icon-192.png
site/assets/icons/apple-touch-icon.png   180 px on an opaque navy square (iOS adds its own corners)
site/assets/og/og-ko.png, og-en.png      1200x630

Usage
-----
    D:\\Tools\\_venvs\\chromite-branding\\Scripts\\python tools/branding/gen_site_images.py

Same venv as gen_app_icons.py (resvg-py, Pillow, fonttools; docs/chromite/branding.md). The Pretendard
SemiBold/Regular web subsets in site/assets/fonts are used for the copy, so the share image needs no
other font.

Change history:
- 2026-11-07 Created (site favicons and Open Graph images).
- 2026-11-08 Share image copy says "more than 100 drivers, many databases" instead of four database names
  (user feedback: the list implied Chromite works only with those four).
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_app_icons as icons  # noqa: E402  (the shared icon drawings and renderer)

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "site"
FONTS = SITE / "assets" / "fonts"

CANVAS = "#F7F8FA"
INK = "#1E1F22"
INK_2 = "#6C707E"
LINE = "#DFE1E5"

OG_COPY = {
    "ko": (["100가지가 넘는 드라이버로", "여러 데이터베이스를", "한 창에서 다룹니다"],
           "Windows와 macOS용 무료 오픈 소스"),
    "en": (["More than 100 drivers,", "many databases,", "one window"],
           "Free and open source, for Windows and macOS"),
}


def tile(size):
    """The app tile at a favicon size, transparent corners (the tier drawing for that size)."""
    return icons.icon_png(size)


def write_icons():
    (SITE / "assets" / "icons").mkdir(parents=True, exist_ok=True)
    sizes = [16, 32, 48]
    imgs = [tile(s) for s in sizes]
    imgs[-1].save(SITE / "favicon.ico", format="ICO", sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
    tile(32).save(SITE / "assets" / "icons" / "icon-32.png", optimize=True)
    tile(192).save(SITE / "assets" / "icons" / "icon-192.png", optimize=True)
    # iOS shows the touch icon on an opaque square and rounds it itself: the tile fills the square
    # on the tile's darkest colour, so the rounded corners of the drawing do not show as white.
    touch = Image.new("RGBA", (180, 180), icons.TILE_DARK)
    touch.alpha_composite(tile(180))
    touch.convert("RGB").save(SITE / "assets" / "icons" / "apple-touch-icon.png", optimize=True)


def lockup(width):
    svg = (ROOT / "chromite_logo_pack" / "chromite_wordmark_light.svg").read_text(encoding="utf-8")
    probe = icons.render(svg, 1196, 310)
    height = round(width * probe.height / probe.width)
    return icons.render(svg, width, height)


def screenshot_crop(lang, w, h):
    """The left part of the home page's app screenshot (Object Explorer, SQL editor, results)."""
    shot = Image.open(SITE / "assets" / "shots" / f"{lang}-light.webp").convert("RGBA")
    scale = 0.62
    shot = shot.resize((round(shot.width * scale), round(shot.height * scale)), Image.LANCZOS)
    return shot.crop((0, 0, min(w, shot.width), min(h, shot.height)))


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius=radius, fill=255)
    out = im.copy()
    out.putalpha(mask)
    return out


def write_og(lang):
    W, H = 1200, 630
    og = Image.new("RGBA", (W, H), CANVAS)
    draw = ImageDraw.Draw(og)

    # the real app window, running off the right and bottom edges
    x0, y0 = 650, 150
    crop = screenshot_crop(lang, W - x0 + 20, H - y0 + 20)
    frame = Image.new("RGBA", (crop.width + 2, crop.height + 2), LINE)
    frame.alpha_composite(crop, (1, 1))
    og.alpha_composite(rounded(frame, 14), (x0, y0))

    logo = lockup(470)
    og.alpha_composite(logo, (64, 92))

    semi = ImageFont.truetype(str(FONTS / "Pretendard-SemiBold.subset.woff2"), 36)
    regular = ImageFont.truetype(str(FONTS / "Pretendard-Regular.subset.woff2"), 24)
    lines, small = OG_COPY[lang]
    y = 92 + logo.height + 56
    for line in lines:
        draw.text((72, y), line, font=semi, fill=INK)
        y += 50
    draw.text((72, y + 22), small, font=regular, fill=INK_2)

    (SITE / "assets" / "og").mkdir(parents=True, exist_ok=True)
    og.convert("RGB").save(SITE / "assets" / "og" / f"og-{lang}.png", optimize=True)


def main():
    write_icons()
    for lang in OG_COPY:
        write_og(lang)
    for p in [SITE / "favicon.ico", *sorted((SITE / "assets" / "icons").glob("*.png")), *sorted((SITE / "assets" / "og").glob("*.png"))]:
        print(f"{p.relative_to(ROOT)}  {p.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()

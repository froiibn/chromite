#!/usr/bin/env python3
"""Generate the Chromite app icon, logo lockups and every image derived from them.

Why this script exists
----------------------
The Chromite icon (user-supplied design, 2026-11-06: navy rounded tile, white 3-tier
database cylinder, white warship sailing out of its lower right, two cyan-to-blue wave
strokes) is kept as vector geometry in this file. The script writes the SVG sources to
chromite_logo_pack/ and renders every raster the products use from those SVGs, so the
images never drift apart and a change is one edit plus one run. The SVG files are
generated, never edited by hand (same rule as gen_ui_icons.py).

Detail tiers
------------
One drawing does not work from 16 to 1024 px, so there are four tiers. The small tiers
are drawn on the pixel grid of each size they are used at (CYL_PX): the cylinder sides
are whole columns and every band edge crosses the centre column on a whole row, so the
gaps stay solid navy instead of grey anti-aliasing.

  full    128 px and up   the supplied design redrawn: mast with yard, radar and pole,
                          windows, deck slit, wake and wave (1000-unit grid)
  simple  40, 48, 64 px   same composition, no windows/radar/pole, wider gaps, thicker waves
  mark    24, 32 px       cylinder, plain hull with one deckhouse and mast, one bold wave
  tiny    16, 20 px       cylinder and one wave (a ship is mush at this size)
  glyph   16 px           cylinder and wave in currentColor, no tile (monochrome uses)

The Windows .ico and the Swing window icons use the tile filling the square (corner
radius 23.8 %, measured from the supplied image). macOS uses the Apple
grid: an 824 px squircle tile centred in 1024 px with a soft drop shadow; its 16/32/64 px
entries are drawn on the pixel grid of the tile inside that margin.

Windows .ico (IconExe)
----------------------
During the product build, Tycho/p2 (BrandingIron) brands the Eclipse launcher
template (launcher.exe -> chromite.exe) with org.eclipse.pde.internal.swt.tools.IconExe:
https://github.com/eclipse-equinox/p2/blob/master/bundles/org.eclipse.equinox.p2.publisher.eclipse/src/org/eclipse/pde/internal/swt/tools/IconExe.java

IconExe has two hard requirements that a default Pillow `Image.save(..., format="ICO")`
does not meet (Pillow stores every entry as 32-bit PNG):

1. Every entry must be an uncompressed BMP/DIB (BITMAPINFOHEADER + XOR bitmap +
   1-bit AND mask). WinICOFileFormat.loadInfoHeader() reads each entry as a
   BITMAPINFOHEADER and fails with "Error 40" (SWT.ERROR_INVALID_IMAGE) when
   biPlanes != 1, which is what a PNG signature looks like. One PNG entry makes the
   whole .ico unloadable, so no launcher icon is replaced at all.
   IconExe also reads the entries sequentially, so image data must follow the
   directory order. The ICONDIRENTRY colour count must be 0 (= 256), 2, 8, 16 or 32.
2. IconExe.replaceIcons() overwrites a launcher RT_ICON resource only with an image
   of exactly the same width, height and bit depth, in place. The Eclipse 4.x
   launcher carries these 7 icons (IconExe.java "Note 3"; also printed by the build):
       256x256 32-bit, 48x48 32-bit, 32x32 32-bit, 16x16 32-bit,
       48x48 8-bit (256-colour palette), 32x32 8-bit, 16x16 8-bit
   The 8-bit entries must have a full 256-colour palette so the rewritten resource
   has the same size as the original one.

The .ico therefore has exactly those 7 entries (the layout of upstream DBeaver's
chromite.ico). 32-bit entries keep the alpha channel; 8-bit entries are quantised to
256 colours with the transparency in the AND mask (alpha < 128 = transparent).

The macOS .icns is written directly as well, with PNG entries for every size including
16 px (icp4) and 32 px (icp5), which Pillow's ICNS writer leaves out.

Outputs (file names are kept; only the contents are generated)
---------------------------------------------------------------
chromite_logo_pack/            SVG sources (chromite_icon*.svg, chromite_glyph16.svg,
                               chromite_wordmark_{light,dark}.svg) and the 6 PNGs
product/swing/icons/           chromite.ico, chromite.icns, chromite_back.bmp (installer)
product/swing/icons-sources/icon_*.png   macOS iconset
plugins/com.seizia.chromite.ui.swing/icons/  chromite{16,20,24,32,40,48,64,128,256}.png,
                               brand/lockup-{light,dark}.svg (empty document area, drawn by JSVG)

Usage
-----
    python tools/branding/gen_app_icons.py [--inter-jar PATH]

Needs Python 3.10+ with the pip packages resvg-py (SVG renderer), Pillow and fonttools
(see docs/chromite/branding.md for the venv). The horizontal lockup (chromite_wordmark_*.svg,
chromite_full_*.png, Swing brand/lockup-*.svg) keeps the lettering of the original Chromite logo: "CHROMITE"
is hand-built geometry traced from the phase-1 logo (TITLE_GLYPHS, no font), and the
subtitle uses Inter SemiBold (SIL OFL 1.1) outlines placed on the original letter
positions. Inter is read from the FlatLaf fonts jar that the Swing bundle
build copies to plugins/com.seizia.chromite.ui.swing/lib/flatlaf-fonts-inter.jar; pass
--inter-jar to use another copy (e.g. from the local Maven repository).

Change history:
- 2026-11-06 Created as gen_launcher_ico.py: the phase-1 icon was all PNG entries, so
  IconExe left the 7 original Eclipse icons in chromite.exe.
- 2026-11-06 Renamed to gen_app_icons.py and extended to the whole icon set: the icon
  is now the user-supplied design redrawn as vector geometry with size tiers, and every
  derived image (ico, icns, window icons, About, splash, installer, logo pack) is
  rendered from the generated SVG sources instead of the phase-1 raster logo.
- 2026-11-06 Lockup text taken from the original logo instead of Inter: "CHROMITE" rebuilt
  as italic geometric glyphs with the blue accent bar on the E, a thin divider, and the
  letter-spaced subtitle on the original letter positions (user decision).
- 2026-11-06 Swing bundle gets the lockup as SVG (icons/brand/lockup-{light,dark}.svg) for the empty
  document area that replaces the Welcome tab.
- 2026-11-07 SWT frontend removed (docs/chromite/swt-removal.md): no more SWT window/About/splash
  images; the product icons moved from product/community to product/swing.
"""
import argparse
import io
import math
import struct
import sys
import zipfile
from pathlib import Path

import resvg_py
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "chromite_logo_pack"

# ---------------------------------------------------------------------------- palette
# Colours were sampled from the supplied image and fitted (tile and cylinder shading are
# radial gradients whose centre/scale were least-squares fitted to the source pixels).
TILE_STOPS = [(0, "#0D417B"), (0.32, "#0B3C74"), (0.49, "#072C58"), (0.66, "#042145"),
              (0.83, "#031C3C"), (1, "#021B3C")]
CYL_STOPS = [(0, "#7DB7F2"), (0.2, "#ADD3F8"), (0.4, "#D3E8FC"), (0.6, "#EDF6FE"), (0.8, "#FCFDFE"),
             (1, "#FFFFFF")]
WAKE_STOPS = [(0, "#00DDF9"), (0.40, "#06E4FD"), (0.56, "#4DEBFC"), (0.67, "#84F3FE"), (0.77, "#C6F7FE"),
              (0.88, "#F2FDFF"), (1, "#FFFFFF")]
WAVE_STOPS = [(0, "#0A86F2"), (0.28, "#00B2FD"), (0.46, "#00D4FF"), (0.72, "#00A2FF"), (1, "#0866F0")]
WHITE = "#FFFFFF"
TILE_DARK = "#021B3C"

WIN_RADIUS = 0.238      # tile corner radius / tile size, measured from the supplied image
MAC_TILE, MAC_OFF = 824, 100  # Apple icon grid (1024 canvas)


# ---------------------------------------------------------------------------- svg helpers
def n(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def svg_doc(w, h, vb, inner, extra=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{n(w)}" height="{n(h)}" viewBox="{vb}"{extra}>'
            f'{inner}</svg>\n')


def stops(ss):
    return "".join(f'<stop offset="{n(o)}" stop-color="{c}"/>' for o, c in ss)


class Xf:
    """Maps the 1000-unit design grid of the tile to output coordinates (scale + offset)."""

    def __init__(self, k=1.0, ox=0.0, oy=0.0):
        self.k, self.ox, self.oy = k, ox, oy

    def p(self, x, y):
        return self.ox + x * self.k, self.oy + y * self.k

    def s(self, v):
        return v * self.k

    def pt(self, x, y):
        a, b = self.p(x, y)
        return f"{n(a)},{n(b)}"


def poly_d(xf, ps):
    return "M" + " L".join(xf.pt(x, y) for x, y in ps) + " Z"


def cubic(xf, seg, rev=False):
    p0, p1, p2, p3 = seg[::-1] if rev else seg
    return f"C{xf.pt(*p1)} {xf.pt(*p2)} {xf.pt(*p3)}"


def rrect_d(x, y, w, h, r):
    return (f"M{n(x + r)},{n(y)} H{n(x + w - r)} A{n(r)},{n(r)} 0 0 1 {n(x + w)},{n(y + r)} "
            f"V{n(y + h - r)} A{n(r)},{n(r)} 0 0 1 {n(x + w - r)},{n(y + h)} H{n(x + r)} "
            f"A{n(r)},{n(r)} 0 0 1 {n(x)},{n(y + h - r)} V{n(y + r)} A{n(r)},{n(r)} 0 0 1 {n(x + r)},{n(y)} Z")


def squircle_d(x0, y0, size, exp=5.0, steps=360):
    """Superellipse close to Apple's continuous-corner icon tile."""
    a = size / 2
    cx, cy = x0 + a, y0 + a
    ps = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        ps.append((cx + a * math.copysign(abs(c) ** (2 / exp), c), cy + a * math.copysign(abs(s) ** (2 / exp), s)))
    return "M" + " L".join(f"{n(x)},{n(y)}" for x, y in ps) + " Z"


# ---------------------------------------------------------------------------- bezier helpers
def bez(seg, t):
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = seg
    u = 1 - t
    return (u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
            u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3)


def split(seg, t):
    """de Casteljau subdivision: (left, right) control polygons."""
    def lp(a, b):
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    p0, p1, p2, p3 = seg
    a, b, c = lp(p0, p1), lp(p1, p2), lp(p2, p3)
    d, e = lp(a, b), lp(b, c)
    f = lp(d, e)
    return (p0, a, d, f), (f, e, c, p3)


def t_at_x(seg, x):
    lo, hi = 0.0, 1.0
    for _ in range(60):
        m = (lo + hi) / 2
        if bez(seg, m)[0] < x:
            lo = m
        else:
            hi = m
    return (lo + hi) / 2


def sub_x(seg, x0, x1):
    """Part of a left-to-right cubic between x0 and x1."""
    ta, tb = t_at_x(seg, x0), t_at_x(seg, x1)
    _, right = split(seg, ta)
    sub, _ = split(right, (tb - ta) / (1 - ta))
    return sub


def shift(seg, dy):
    return tuple((x, y + dy) for x, y in seg)


# ---------------------------------------------------------------------------- full-tier geometry
# 1000-unit grid = the tile. Traced from the supplied image (tile cropped and normalised to
# 1000x1000); curves are cubic fits of the traced edge points (max error < 2 units).
# Cylinder sides, face centre and band edges sit on the 1/128 grid (whole pixels at 128 px
# and up); the traced values were 239-749, 248, 286/429/572 and 105.
CYL_CX, CYL_RX, CYL_RY = 492.19, 257.81, 95.0
FACE_CY = 250.0
BAND_TOPS = (289.06, 429.69, 570.31)
BAND_H = 101.56

DECK = ((486, 696), (633, 669.9), (785.2, 644.5), (935, 657))       # sheer line to the bow tip
HULL_BOT = ((470, 757), (591.3, 739.4), (717.5, 743.4), (832, 792))  # keel line, stern to stem
WAKE_TOP = ((85, 836), (163.8, 806.0), (242.1, 775.2), (326, 762))
WAKE_BOT = ((85, 836), (214.0, 811.5), (338.8, 771.3), (470, 757))
WAVE_TOP = (((202, 867), (342.4, 837.8), (474.0, 775), (620, 775)),
            ((620, 775), (716.3, 775), (809.0, 806.1), (895, 846)))
WAVE_BOT = (((202, 867), (357.5, 872.1), (504.2, 808), (660, 808)),
            ((660, 808), (741.9, 808), (817.0, 827.8), (895, 846)))

# Superstructure outline from the stern corner over the stepped deckhouses to the forward
# end of the deck slit. Left: aft deckhouse, notch, three stepped tiers; right: bridge wing
# lip and the tiers stepping down to the bow.
SUPER_FULL = [(326, 762), (349, 720), (446, 702), (481, 633), (519, 633), (523, 645), (533, 645), (545, 614),
              (566, 614), (572, 579), (599, 579), (605, 553), (710, 553), (710, 559), (694, 562), (698, 578),
              (728, 578), (733, 613), (755, 615)]
MAST_FULL = [("poly", [(653, 553.5), (667, 479), (677, 479), (677, 553.5)]),   # tapered mast
             ("poly", [(622, 500), (722, 493), (720, 502), (619, 509)]),       # yard
             ("rrect", (662, 466, 26, 13, 6.5)),                                # radar
             ("rrect", (675, 442, 5.5, 26, 2.75))]                              # pole
WINDOWS_FULL = [[(609, 563), (628, 563), (623, 578), (604, 578)],
                [(577, 588), (619, 588), (603, 632), (595, 632), (587, 613), (571, 613)],
                [(661, 590), (686, 590), (688, 599), (660, 599)],
                [(698, 590), (717, 590), (719, 599), (699, 599)]]


def hull_d(xf, upper, slit, slit_x0=512.0):
    """Hull and superstructure as one outline. The deck slit is part of the outline (not a
    hole) because it opens to the sky at its forward end; its upper edge is the sheer line
    moved up by the slit width, so the slit keeps one width and tapers only at the stern."""
    slit_x1 = upper[-1][0] + 12
    slit_top = shift(sub_x(DECK, slit_x0, slit_x1), -slit)
    d = "M" + " L".join(xf.pt(x, y) for x, y in upper) + f" L{xf.pt(*slit_top[3])}"
    d += " " + cubic(xf, slit_top, rev=True) + f" L{xf.pt(*DECK[0])} " + cubic(xf, DECK)
    d += f" L{xf.pt(*HULL_BOT[3])} " + cubic(xf, HULL_BOT, rev=True) + " Z"
    return d


def parts_d(xf, parts):
    out = []
    for kind, v in parts:
        if kind == "poly":
            out.append(poly_d(xf, v))
        else:
            x, y, w, h, r = v
            (px, py), k = xf.p(x, y), xf.k
            out.append(rrect_d(px, py, w * k, h * k, r * k))
    return " ".join(out)


def wake_d(xf, top=WAKE_TOP, bot=WAKE_BOT):
    return f"M{xf.pt(*top[0])} " + cubic(xf, top) + f" L{xf.pt(*bot[3])} " + cubic(xf, bot, rev=True) + " Z"


def wave_d(xf, top=WAVE_TOP, bot=WAVE_BOT):
    return (f"M{xf.pt(*top[0][0])} " + cubic(xf, top[0]) + " " + cubic(xf, top[1]) + " " +
            cubic(xf, bot[1], rev=True) + " " + cubic(xf, bot[0], rev=True) + " Z")


def thicken(top, bot, dt, db):
    """Moves the inner control points of a two-segment stroke outline apart (tips stay)."""
    def mv(segs, dy):
        a, b = segs
        return ((a[0], (a[1][0], a[1][1] + dy), (a[2][0], a[2][1] + dy), (a[3][0], a[3][1] + dy)),
                ((b[0][0], b[0][1] + dy), (b[1][0], b[1][1] + dy), (b[2][0], b[2][1] + dy), b[3]))
    return mv(top, -dt), mv(bot, db)


def band_d(cx, rx, ry, top, h):
    return (f"M{n(cx - rx)},{n(top)} A{n(rx)},{n(ry)} 0 0 0 {n(cx + rx)},{n(top)} "
            f"V{n(top + h)} A{n(rx)},{n(ry)} 0 0 1 {n(cx - rx)},{n(top + h)} Z")


# ---------------------------------------------------------------------------- composition
def gradients(pid, xf):
    """Tile, cylinder, wake and wave gradients mapped through xf (same look at every tier)."""
    tx, ty = xf.p(-250, -400)
    cx, cy = xf.p(550, 650)
    wx0, _ = xf.p(85, 0)
    wx1, _ = xf.p(470, 0)
    vx0, _ = xf.p(202, 0)
    vx1, _ = xf.p(895, 0)
    return (f'<radialGradient id="{pid}tile" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="{n(xf.s(2041))}" '
            f'gradientTransform="translate({n(tx)} {n(ty)}) scale(1 0.8)">{stops(TILE_STOPS)}</radialGradient>'
            f'<radialGradient id="{pid}cyl" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="{n(xf.s(508))}" '
            f'gradientTransform="translate({n(cx)} {n(cy)}) scale(1 0.8)">{stops(CYL_STOPS)}</radialGradient>'
            f'<linearGradient id="{pid}wake" gradientUnits="userSpaceOnUse" x1="{n(wx0)}" y1="0" x2="{n(wx1)}" y2="0">'
            f'{stops(WAKE_STOPS)}</linearGradient>'
            f'<linearGradient id="{pid}wave" gradientUnits="userSpaceOnUse" x1="{n(vx0)}" y1="0" x2="{n(vx1)}" y2="0">'
            f'{stops(WAVE_STOPS)}</linearGradient>')


def compose(pid, xf, cyl, ship_paths, windows, wake, wave, halo, solid=False):
    """Cylinder (cut by a uniform halo around the ship and waves so they read on top of it),
    then waves, ship and windows. The halo is a mask, so the gap shows the tile behind.
    solid=True fills the bands plain white: the shading costs contrast at 16-32 px."""
    defs = gradients(pid, xf)
    cut = " ".join(p for p in ship_paths + [wake, wave] if p)
    defs += (f'<mask id="{pid}ko" maskUnits="userSpaceOnUse" x="-10%" y="-10%" width="120%" height="120%">'
             f'<rect x="-10000" y="-10000" width="20000" height="20000" fill="#FFFFFF"/>'
             f'<path d="{cut}" fill="#000000" stroke="#000000" stroke-width="{n(2 * halo)}" '
             f'stroke-linejoin="round"/></mask>') if cut else ""
    face, bands = cyl
    band_fill = WHITE if solid else f"url(#{pid}cyl)"
    body = f'<path d="{face}" fill="{WHITE}"/>' + "".join(f'<path d="{b}" fill="{band_fill}"/>' for b in bands)
    if cut:
        body = f'<g mask="url(#{pid}ko)">{body}</g>'
    if wave:
        body += f'<path d="{wave}" fill="url(#{pid}wave)"/>'
    if wake:
        body += f'<path d="{wake}" fill="url(#{pid}wake)"/>'
    if ship_paths:
        body += f'<path d="{" ".join(ship_paths)}" fill="{WHITE}"/>'
    if windows:
        body += f'<path d="{windows}" fill="url(#{pid}tile)"/>'
    return defs, body


def ellipse_d(cx, cy, rx, ry):
    return (f"M{n(cx - rx)},{n(cy)} A{n(rx)},{n(ry)} 0 1 0 {n(cx + rx)},{n(cy)} "
            f"A{n(rx)},{n(ry)} 0 1 0 {n(cx - rx)},{n(cy)} Z")


def art_full(pid, xf):
    k = xf.k
    cx, _ = xf.p(CYL_CX, 0)
    face = ellipse_d(cx, xf.p(0, FACE_CY)[1], CYL_RX * k, CYL_RY * k)
    bands = [band_d(cx, CYL_RX * k, CYL_RY * k, xf.p(0, t)[1], BAND_H * k) for t in BAND_TOPS]
    ship = [hull_d(xf, SUPER_FULL, 15.0), parts_d(xf, MAST_FULL)]
    windows = " ".join(poly_d(xf, w) for w in WINDOWS_FULL)
    return compose(pid, xf, (face, bands), ship, windows, wake_d(xf), wave_d(xf), halo=20 * k)


# Cylinder on the pixel grid of each small size:
#   g: (left, right, face_cy, face_ry, band_ry, band_h, gap, bands)
# The face bottom and every band edge cross the centre column on a whole pixel row, so the
# 1-2 px gaps render as solid navy rows instead of grey anti-aliasing; the sides are whole
# columns. Proportions follow the full tier (face top ~0.15, last band bottom ~0.77 of the tile).
CYL_PX = {
    64: (16, 48, 15, 6, 6, 7, 2, 3),
    52: (13, 39, 12, 5, 5, 6, 2, 3),     # macOS 64 px entry (tile box 52 px)
    48: (12, 36, 11, 4, 4, 5, 2, 3),
    38: (9, 28, 8.5, 3.5, 3.5, 4, 2, 3),  # macOS 48 px (iconset only)
    40: (10, 30, 9.5, 3.5, 3.5, 4, 2, 3),
    32: (8, 24, 7.5, 2.5, 2.5, 3, 1, 3),
    26: (6, 20, 6, 2, 2, 3, 1, 3),       # macOS 32 px entry
    24: (6, 18, 5.5, 1.5, 1.5, 2, 1, 3),
    20: (5, 15, 4.5, 1.5, 1.5, 2, 1, 2),
    16: (4, 12, 3.5, 1.5, 1, 2, 1, 2),
    12: (3, 9, 3, 1, 1, 2, 1, 2),        # macOS 16 px entry
}


def cyl_px(g, ox, oy):
    """Cylinder of a g-px tile placed at (ox, oy), from CYL_PX."""
    left, right, face_cy, face_ry, band_ry, h, gap, count = CYL_PX[g]
    cx, rx = ox + (left + right) / 2, (right - left) / 2
    face = ellipse_d(cx, oy + face_cy, rx, face_ry)
    top0 = face_cy + face_ry + gap - band_ry
    bands = [band_d(cx, rx, band_ry, oy + top0 + i * (h + gap), h) for i in range(count)]
    return face, bands


# Simplified ship for the simple tier: three deckhouse steps, mast with yard only.
def super_simple(a, c, d):
    """a, c, d = tops of the aft deckhouse, middle tier and top tier (design units)."""
    return [(326, 762), (349, 720), (446, 702), (481, a), (540, a), (560, c), (592, c), (602, d), (708, d),
            (716, c), (730, c), (744, a), (757, a)]


def art_simple(pid, g, ox=0.0, oy=0.0):
    """40-64 px: snapped cylinder, ship without windows/radar/pole, thicker gaps and waves."""
    k = g / 1000
    xf = Xf(k, ox, oy)
    u = 1000 / g                                   # design units per pixel
    # deckhouse tops on whole pixel rows
    a, c, d = (round(y / u) * u for y in (628, 584, 548))
    slit = max(1.0, 0.024 * g) * u
    mast_w = max(1.3, 0.02 * g) * u
    yard_h = max(1.2, 0.02 * g) * u
    mast = [("poly", [(650, d + 0.5), (668, 470), (668 + mast_w, 470), (682, d + 0.5)]),
            ("poly", [(612, 503), (726, 495), (726, 495 + yard_h), (612, 503 + yard_h)])]
    ship = [hull_d(xf, super_simple(a, c, d), slit, slit_x0=520), parts_d(xf, mast)]
    wtop, wbot = thicken(WAVE_TOP, WAVE_BOT, 7, 9)
    wake_top = (WAKE_TOP[0], (WAKE_TOP[1][0], WAKE_TOP[1][1] - 6), (WAKE_TOP[2][0], WAKE_TOP[2][1] - 4), WAKE_TOP[3])
    halo = max(1.5, 0.03 * g)
    return compose(pid, xf, cyl_px(g, ox, oy), ship, "", wake_d(xf, top=wake_top), wave_d(xf, wtop, wbot), halo)


# Mark tier: plain hull with one slanted deckhouse and a mast. The stern starts further
# forward than in the full ship so the halo cuts only the lower right of the cylinder (the
# stepped stern over the bands turned into a ragged notch at 24-32 px).
# Deckhouse top and mast edges are whole pixels at 32 px (19 px row, 21-22 px column).
HULL_MARK = [(470, 772), (482, 712), (545, 704), (575, 593.75), (671.9, 593.75), (671.9, 500), (703.1, 500),
             (703.1, 593.75), (738, 593.75), (766, 668), (935, 652), (832, 792)]
HULL_MARK_BOT = ((832, 792), (717.5, 743.4), (591.3, 739.4), (470, 772))


def art_mark(pid, g, ox=0.0, oy=0.0):
    """24-32 px: snapped cylinder, plain ship silhouette, one bold wave."""
    xf = Xf(g / 1000, ox, oy)
    wtop, wbot = thicken(WAVE_TOP, WAVE_BOT, 18, 22)
    hull = "M" + " L".join(xf.pt(x, y) for x, y in HULL_MARK) + " " + cubic(xf, HULL_MARK_BOT) + " Z"
    return compose(pid, xf, cyl_px(g, ox, oy), [hull], "", "", wave_d(xf, wtop, wbot), 1.0, solid=True)


TINY_WAVE = ((((80, 905), (300, 860), (450, 790), (620, 790)), ((620, 790), (760, 790), (840, 830), (920, 880))),
             (((80, 905), (320, 930), (480, 905), (660, 905)), ((660, 905), (780, 905), (850, 900), (920, 880))))


def art_tiny(pid, g=16, ox=0.0, oy=0.0):
    """16 and 20 px: cylinder (face + 2 bands) and one wave under it; a ship is mush here."""
    xf = Xf(g / 1000, ox, oy)
    return compose(pid, xf, cyl_px(g, ox, oy), [], "", "", wave_d(xf, *TINY_WAVE), 1.0, solid=True)


# ---------------------------------------------------------------------------- documents
def tile_doc(pid, g, art):
    """Windows/window icon: the tile fills the square."""
    defs, body = art
    tile = f'<path d="{rrect_d(0, 0, g, g, WIN_RADIUS * g)}" fill="url(#{pid}tile)"/>'
    return svg_doc(g, g, f"0 0 {n(g)} {n(g)}", f"<defs>{defs}</defs>{tile}{body}")


def windows_svg(size):
    """Tier drawing for a Windows/window-icon size, authored on that size's pixel grid."""
    if size >= 128:
        return tile_doc("f", 1000, art_full("f", Xf()))
    if size >= 40:
        return tile_doc("s", size, art_simple("s", size))
    if size >= 24:
        return tile_doc("m", size, art_mark("m", size))
    return tile_doc("t", size, art_tiny("t", size))


def mac_box(size):
    """Apple-grid tile box (offset, size) in pixels; small sizes snap it to whole pixels."""
    if size >= 128:
        return MAC_OFF * size / 1024, MAC_TILE * size / 1024
    off = round(MAC_OFF * size / 1024)
    return off, size - 2 * off


def macos_svg(size):
    """macOS icon: Apple-grid squircle tile. 128 px and up share the 1024 px drawing; 16-64 px
    entries are drawn on the pixel grid of their tile box."""
    if size >= 128:
        size, (off, ts), pid = 1024, mac_box(1024), "f"
        defs, body = art_full(pid, Xf(ts / 1000, off, off))
    else:
        (off, ts), pid = mac_box(size), ("s" if size >= 48 else ("m" if size >= 32 else "t"))
        art = {"s": art_simple, "m": art_mark, "t": art_tiny}[pid]
        defs, body = art(pid, ts, off, off)
    tile = squircle_d(off, off, ts)
    shadow = ""
    if size >= 1024:
        defs += ('<filter id="sh" x="-10%" y="-10%" width="120%" height="125%" color-interpolation-filters="sRGB">'
                 '<feDropShadow dx="0" dy="10" stdDeviation="11" flood-color="#000000" flood-opacity="0.30"/></filter>')
        # the shadow is a separate black copy: filtering the gradient tile itself made resvg
        # round the dark gradient through 8-bit linearRGB and band visibly
        shadow = f'<path d="{tile}" fill="#000000" filter="url(#sh)"/>'
    inner = (f"<defs>{defs}</defs>{shadow}<path d=\"{tile}\" fill=\"url(#{pid}tile)\"/>"
             f'<path d="{tile}" fill="none" stroke="#FFFFFF" stroke-opacity="0.10" stroke-width="{n(2 * size / 1024)}"/>'
             f"{body}")
    return svg_doc(size, size, f"0 0 {size} {size}", inner)


def glyph_svg(color="currentColor"):
    """Monochrome 16 px glyph: cylinder face + 2 bands and a wave stroke, no tile."""
    # face bottom and band edges cross the centre column on whole rows (5, 6, 8, 9, 11)
    face = ellipse_d(8, 3, 5, 2)
    bands = [band_d(8, 5, 2, 4, 2), band_d(8, 5, 2, 7, 2)]
    body = f'<path d="{face}" fill="{color}"/>' + "".join(f'<path d="{b}" fill="{color}"/>' for b in bands)
    xf = Xf(0.016, 0, 0)
    top = (((70, 930), (300, 900), (450, 820), (620, 820)), ((620, 820), (760, 820), (850, 860), (930, 905)))
    bot = (((70, 930), (320, 950), (480, 915), (660, 915)), ((660, 915), (780, 915), (860, 920), (930, 905)))
    body += f'<path d="{wave_d(xf, top, bot)}" fill="{color}"/>'
    return svg_doc(16, 16, "0 0 16 16", body)


# ---------------------------------------------------------------------------- lockup
class Inter:
    """Inter SemiBold (SIL OFL 1.1) from the FlatLaf fonts jar, for the lockup subtitle."""

    def __init__(self, jar):
        with zipfile.ZipFile(jar) as z:
            data = z.read("com/formdev/flatlaf/fonts/inter/Inter-SemiBold.otf")
        self.font = TTFont(io.BytesIO(data))
        self.gs = self.font.getGlyphSet()
        self.cmap = self.font.getBestCmap()
        self.upm = self.font["head"].unitsPerEm
        self.hmtx = self.font["hmtx"]

    def glyph(self, ch, size, cx, y):
        """svg path d of one glyph with its ink centred on cx and its baseline on y."""
        s = size / self.upm
        gn = self.cmap[ord(ch)]
        bp = BoundsPen(self.gs)
        self.gs[gn].draw(bp)
        x0, _, x1, _ = bp.bounds
        sp = SVGPathPen(self.gs)
        self.gs[gn].draw(TransformPen(sp, (s, 0, 0, -s, cx - (x0 + x1) / 2 * s, y)))
        return sp.getCommands()


def fillet_d(pts):
    """Closed outline through (x, y, r) vertices; r > 0 rounds that corner with a circular arc."""
    out = []
    count = len(pts)
    for i, (x, y, r) in enumerate(pts):
        if r <= 0:
            out.append((x, y, None))
            continue
        px, py, _ = pts[i - 1]
        nx, ny, _ = pts[(i + 1) % count]
        ax, ay = px - x, py - y
        bx, by = nx - x, ny - y
        la, lb = math.hypot(ax, ay), math.hypot(bx, by)
        ang = math.acos(max(-1.0, min(1.0, (ax * bx + ay * by) / (la * lb))))
        d = r / math.tan(ang / 2)
        p1 = (x + ax / la * d, y + ay / la * d)
        p2 = (x + bx / lb * d, y + by / lb * d)
        sweep = 1 if (-ax) * by - (-ay) * bx > 0 else 0
        out.append((p1[0], p1[1], None))
        out.append((p2[0], p2[1], (r, sweep)))
    d = f"M{n(out[0][0])},{n(out[0][1])}"
    for x, y, arc in out[1:]:
        d += f" A{n(arc[0])},{n(arc[0])} 0 0 {arc[1]} {n(x)},{n(y)}" if arc else f" L{n(x)},{n(y)}"
    return d + " Z"


# "CHROMITE" of the original Chromite logo (phase-1 chromite_full_*.png), rebuilt as geometry.
# Traced from the 1275x395 original, measured upright (the italic slant removed) and cleaned
# to one cap height (93), one stem width (20), one bar height (19), outer corner radius 23 and
# counter radius 5-6. Coordinates: x as in the original image, h = height above the baseline
# (y 218 in the original); the lockup slants them back by the original 0.17 (9.6 deg).
# The E's white part is only the lower two bars; its top bar is the blue accent stroke.
TITLE_GLYPHS = [
    # C: rounded box with the right side open between h 35 and 62
    [(529, 62, 0), (529, 93, 23), (431, 93, 23), (431, 0, 23), (529, 0, 23), (529, 35, 0), (510, 35, 0),
     (510, 19, 5), (451, 19, 5), (451, 74, 5), (510, 74, 5), (510, 62, 0)],
    # H
    [(542, 0, 0), (542, 93, 0), (562, 93, 0), (562, 57, 0), (617, 57, 0), (617, 93, 0), (637, 93, 0), (637, 0, 0),
     (617, 0, 0), (617, 38, 0), (562, 38, 0), (562, 0, 0)],
    # R: stem, rounded bowl, straight leg; counter below
    [(652, 0, 0), (652, 93, 0), (746, 93, 23), (746, 30, 22), (719, 30, 0), (753, 0, 0), (723, 0, 0), (689, 30, 0),
     (672, 30, 0), (672, 0, 0)],
    [(672, 50, 0), (725, 50, 5), (725, 74, 5), (672, 74, 0)],
    # O
    [(758, 0, 23), (758, 93, 23), (859, 93, 23), (859, 0, 23)],
    [(779, 19, 6), (779, 74, 6), (839, 74, 6), (839, 19, 6)],
    # M: the V stops 6 above the baseline
    [(873, 0, 0), (873, 93, 0), (891, 93, 0), (932, 37, 0), (973, 93, 0), (991, 93, 0), (991, 0, 0), (971, 0, 0),
     (971, 54, 0), (934.5, 6, 0), (929.5, 6, 0), (893, 54, 0), (893, 0, 0)],
    # I
    [(1007.5, 0, 0), (1007.5, 93, 0), (1027.5, 93, 0), (1027.5, 0, 0)],
    # T
    [(1040, 74, 0), (1040, 93, 0), (1121, 93, 0), (1121, 74, 0), (1090.5, 74, 0), (1090.5, 0, 0), (1070.5, 0, 0),
     (1070.5, 74, 0)],
    # E (white part): stem with the bottom and middle bars
    [(1136, 0, 0), (1136, 57, 0), (1219.5, 57, 0), (1219.5, 38, 0), (1156, 38, 0), (1156, 19, 0), (1219.5, 19, 0),
     (1219.5, 0, 0)],
]
TITLE_BASE, TITLE_SLANT = 218.0, 0.17
# "DATABASE WORKBENCH": ink centre of each of the 17 letters in the original (cap 246-271).
# The original letters are not a font on disk, so Inter SemiBold letters of the same cap
# height and weight are placed on these centres, which keeps the original's spacing.
SUB_TEXT = "DATABASEWORKBENCH"
SUB_CENTERS = [451, 488.5, 522.5, 555.5, 593.5, 630.5, 666.5, 702, 767, 811.5, 852, 889.5, 926, 962, 999.5, 1039.5, 1079]
# Accent stroke (the E's top bar) in original image coordinates; its ends lean 0.5, more
# than the letters, as in the original.
TITLE_ACCENT = [(1147, 143.5), (1231, 143.5), (1240.25, 125), (1156.25, 125)]
ACCENT_STOPS = [(0, "#1D95FD"), (1, "#1478F5")]
# Colours of the original lockup: dark = white title, grey subtitle and divider; light = navy.
LOCKUP_INK = {True: ("#FFFFFF", "#B9BCC6", "#BDC3CF"), False: ("#18253B", "#18253B", "#18253B")}
LOCKUP_DARK_BG = "#01041A"     # background of the original dark lockup
LIGHT_BG = "#FFFFFF"


def title_d():
    return " ".join(fillet_d([(x, TITLE_BASE - h, r) for x, h, r in g]) for g in TITLE_GLYPHS)


def lockup_svg(inter, dark, bg=None, scale=1.0, pad=30.0):
    """Horizontal lockup in the layout of the original logo (its pixel coordinates): the tile
    icon, a thin divider (x 384-387), "CHROMITE" (cap 125-218) and "DATABASE WORKBENCH"
    (cap 246-271, Inter SemiBold letters on the original letter centres)."""
    pid = "ld" if dark else "ll"
    title, sub, rule = LOCKUP_INK[dark]
    sym = 250
    sx, sy = 355 - sym, 198 - sym / 2
    defs, body = art_full(pid, Xf(sym / 1000, sx, sy))
    tile = f'<path d="{rrect_d(sx, sy, sym, sym, WIN_RADIUS * sym)}" fill="url(#{pid}tile)"/>'
    size = 25 / 0.727                               # Inter cap height = 0.727 em
    sd = " ".join(inter.glyph(ch, size, cx, 271) for ch, cx in zip(SUB_TEXT, SUB_CENTERS))
    defs += (f'<linearGradient id="{pid}acc" gradientUnits="userSpaceOnUse" x1="1147" y1="0" x2="1241" y2="0">'
             f'{stops(ACCENT_STOPS)}</linearGradient>')
    shear = (f"translate(0 {n(TITLE_BASE)}) skewX({n(-math.degrees(math.atan(TITLE_SLANT)))}) "
             f"translate(0 {n(-TITLE_BASE)})")
    accent = "M" + " L".join(f"{n(x)},{n(y)}" for x, y in TITLE_ACCENT) + " Z"
    x0, y0, x1, y1 = sx - pad, sy - pad, 1241 + pad, sy + sym + pad
    rect = f'<rect x="{n(x0)}" y="{n(y0)}" width="{n(x1 - x0)}" height="{n(y1 - y0)}" fill="{bg}"/>' if bg else ""
    inner = (f"<defs>{defs}</defs>{rect}{tile}{body}"
             f'<rect x="384.5" y="128" width="3" height="146" fill="{rule}"/>'
             f'<path d="{title_d()}" fill="{title}" fill-rule="evenodd" transform="{shear}"/>'
             f'<path d="{accent}" fill="url(#{pid}acc)"/>'
             f'<path d="{sd}" fill="{sub}"/>')
    w, h = x1 - x0, y1 - y0
    return svg_doc(w * scale, h * scale, f"{n(x0)} {n(y0)} {n(w)} {n(h)}", inner)


# ---------------------------------------------------------------------------- rendering
def render(svg, w, h=None):
    data = resvg_py.svg_to_bytes(svg_string=svg, width=w, height=h or w)
    return Image.open(io.BytesIO(bytes(data))).convert("RGBA")


def icon_png(size):
    return render(windows_svg(size), size)


def mac_png(size):
    return render(macos_svg(size), size)


def on_canvas(im, w, h, bg, x=None, y=None):
    out = Image.new("RGBA", (w, h), bg)
    out.alpha_composite(im, ((w - im.width) // 2 if x is None else x, (h - im.height) // 2 if y is None else y))
    return out


# ---------------------------------------------------------------------------- .ico / .icns
ICO_ENTRIES = [(16, 8), (16, 32), (32, 8), (32, 32), (48, 8), (48, 32), (256, 32)]


def and_mask(alpha, size, threshold):
    """1-bit AND mask, bottom-up, rows padded to 4 bytes; bit set = transparent."""
    stride = ((size + 31) // 32) * 4
    out = bytearray()
    for y in range(size - 1, -1, -1):
        row = bytearray(stride)
        for x in range(size):
            if alpha[y * size + x] < threshold:
                row[x >> 3] |= 0x80 >> (x & 7)
        out += row
    return bytes(out)


def bitmap_info_header(size, depth, colors_used):
    xor_stride = ((size * depth + 31) // 32) * 4
    and_stride = ((size + 31) // 32) * 4
    size_image = (xor_stride + and_stride) * size
    # biSize, biWidth, biHeight (XOR + AND), biPlanes, biBitCount, biCompression (BI_RGB),
    # biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant
    return struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, depth, 0, size_image, 0, 0, colors_used, 0)


def dib_32(img):
    size = img.width
    px = img.tobytes()  # RGBA
    xor = bytearray()
    for y in range(size - 1, -1, -1):
        row = px[y * size * 4:(y + 1) * size * 4]
        for x in range(size):
            r, g, b, a = row[x * 4:x * 4 + 4]
            xor += bytes((b, g, r, a))
    alpha = img.getchannel("A").tobytes()
    return bitmap_info_header(size, 32, 0) + bytes(xor) + and_mask(alpha, size, 1)


def dib_8(img, bg_color):
    size = img.width
    alpha = img.getchannel("A").tobytes()
    # Composite onto the tile colour so the palette is spent on visible pixels and
    # anti-aliased edges that stay opaque blend into navy, not black.
    flat = Image.new("RGB", img.size, bg_color)
    flat.paste(img, (0, 0), img)
    q = flat.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    pal = q.getpalette()[:256 * 3]
    pal += [0] * (256 * 3 - len(pal))
    palette = b"".join(bytes((pal[i * 3 + 2], pal[i * 3 + 1], pal[i * 3], 0)) for i in range(256))
    idx = q.tobytes()
    stride = ((size * 8 + 31) // 32) * 4
    xor = bytearray()
    for y in range(size - 1, -1, -1):
        row = bytearray(stride)
        for x in range(size):
            # Transparent pixels get index 0; the AND mask hides them.
            row[x] = idx[y * size + x] if alpha[y * size + x] >= 128 else 0
        xor += row
    return bitmap_info_header(size, 8, 256) + palette + bytes(xor) + and_mask(alpha, size, 128)


def build_ico(images):
    tile_rgb = tuple(int(TILE_DARK[i:i + 2], 16) for i in (1, 3, 5))
    data = [dib_32(images[s]) if d == 32 else dib_8(images[s], tile_rgb) for s, d in ICO_ENTRIES]
    header = struct.pack("<HHH", 0, 1, len(ICO_ENTRIES))
    offset = 6 + 16 * len(ICO_ENTRIES)
    directory = b""
    for (size, depth), blob in zip(ICO_ENTRIES, data):
        dim = 0 if size >= 256 else size
        # bWidth, bHeight, bColorCount (0 = 256 or true colour), bReserved, wPlanes, wBitCount, dwBytesInRes, dwImageOffset
        directory += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, depth, len(blob), offset)
        offset += len(blob)
    return header + directory + b"".join(data)


# OSType -> pixel size; all entries are PNG (supported by macOS 10.7+)
ICNS_ENTRIES = [("icp4", 16), ("icp5", 32), ("ic11", 32), ("ic12", 64), ("ic07", 128), ("ic08", 256),
                ("ic13", 256), ("ic09", 512), ("ic14", 512), ("ic10", 1024)]


def png_bytes(im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    return b.getvalue()


def build_icns(mac):
    body = b""
    for ostype, size in ICNS_ENTRIES:
        blob = png_bytes(mac[size])
        body += ostype.encode("ascii") + struct.pack(">I", len(blob) + 8) + blob
    return b"icns" + struct.pack(">I", len(body) + 8) + body


# ---------------------------------------------------------------------------- main
def find_inter(arg):
    cands = [Path(arg)] if arg else []
    cands.append(ROOT / "plugins" / "com.seizia.chromite.ui.swing" / "lib" / "flatlaf-fonts-inter.jar")
    for c in cands:
        if c.is_file():
            return c
    sys.exit("Inter fonts jar not found: build the Swing bundle once (it copies lib/flatlaf-fonts-inter.jar) "
             "or pass --inter-jar <path to flatlaf-fonts-inter-4.1.jar>")


def write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def save_png(im, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)


WIN_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512, 1024]
MAC_SIZES = [16, 32, 64, 128, 256, 512, 1024]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--inter-jar", help="flatlaf-fonts-inter jar for the lockup subtitle")
    args = ap.parse_args()
    inter = Inter(find_inter(args.inter_jar))

    # SVG sources
    svgs = {"chromite_icon.svg": windows_svg(1024), "chromite_icon_macos.svg": macos_svg(1024),
            "chromite_glyph16.svg": glyph_svg(),
            "chromite_wordmark_light.svg": lockup_svg(inter, False),
            "chromite_wordmark_dark.svg": lockup_svg(inter, True)}
    for s in (64, 48, 40, 32, 24, 20, 16):
        svgs[f"chromite_icon_{s}.svg"] = windows_svg(s)
    for s in (64, 32, 16):
        svgs[f"chromite_icon_macos_{s}.svg"] = macos_svg(s)
    for name, text in svgs.items():
        write_text(PACK / name, text)

    win = {s: icon_png(s) for s in WIN_SIZES}
    mac = {s: mac_png(s) for s in MAC_SIZES}

    # logo pack PNGs (names kept from phase 1)
    sym = win[256]
    for name, bg in (("dark", LOCKUP_DARK_BG), ("light", LIGHT_BG), ("transparent", (0, 0, 0, 0))):
        save_png(on_canvas(sym.resize((320, 320), Image.LANCZOS), 360, 395, bg), PACK / f"chromite_symbol_{name}.png")
    # the transparent lockup keeps the original's light-on-dark ink, like the phase-1 file
    for name, dark, bg in (("dark", True, LOCKUP_DARK_BG), ("light", False, LIGHT_BG), ("transparent", True, None)):
        wm = render(lockup_svg(inter, dark), 1196, None)
        save_png(on_canvas(wm, 1275, 395, bg or (0, 0, 0, 0)), PACK / f"chromite_full_{name}.png")

    # Windows launcher icon, macOS app icon and iconset
    icons = ROOT / "product" / "swing" / "icons"
    (icons / "chromite.ico").write_bytes(build_ico(win))
    (icons / "chromite.icns").write_bytes(build_icns(mac))
    srcs = ROOT / "product" / "swing" / "icons-sources"
    for s in (16, 32, 48, 64, 128, 256, 512):
        save_png(mac[s] if s in mac else mac_png(s), srcs / f"icon_{s}x{s}.png")
    for s in (16, 32, 128, 256, 512):
        save_png(mac[2 * s], srcs / f"icon_{s}x{s}@2x.png")

    # Installer side image: the icon near the top of a navy panel in the tile gradient
    back = render(svg_doc(165, 314, "0 0 165 314",
                          f'<defs>{gradients("b", Xf(0.314, -76, 0))}</defs>'
                          f'<rect width="165" height="314" fill="url(#btile)"/>'), 165, 314)
    back.alpha_composite(win[128].resize((112, 112), Image.LANCZOS), (27, 56))
    back.convert("RGB").save(icons / "chromite_back.bmp", "BMP")

    # Swing frontend window icons and logo
    swing = ROOT / "plugins" / "com.seizia.chromite.ui.swing" / "icons"
    for s in (16, 20, 24, 32, 40, 48, 64, 128, 256):
        save_png(win[s], swing / f"chromite{s}.png")
    # Horizontal lockup for the empty document area, tight to the artwork (the panel centres it) and without a
    # background, so it sits on the editor background of either theme
    write_text(swing / "brand" / "lockup-light.svg", lockup_svg(inter, False, pad=0))
    write_text(swing / "brand" / "lockup-dark.svg", lockup_svg(inter, True, pad=0))
    print("ok")


if __name__ == "__main__":
    main()

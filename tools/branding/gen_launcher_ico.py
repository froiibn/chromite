#!/usr/bin/env python3
"""Generate the Windows launcher icon product/community/icons/dbeaver.ico for Chromite.

Why this script exists
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

This script writes exactly those 7 entries (the same layout as upstream DBeaver's
dbeaver.ico) from the same artwork as the phase-1 branding: chromite_symbol_dark.png
centred at 84% height on a navy rounded square (corner radius 20%), rendered at
1024 px and downscaled with LANCZOS. 32-bit entries keep the alpha channel; 8-bit
entries are quantised to 256 colours with the transparency in the AND mask
(alpha < 128 = transparent).

Usage (needs Pillow):
    python tools/branding/gen_launcher_ico.py [REPO_ROOT] [OUTPUT_ICO]
REPO_ROOT defaults to the repository containing this script and OUTPUT_ICO to
REPO_ROOT/product/community/icons/dbeaver.ico.

Change history:
- 2026-11-06 Created: the phase-1 icon was all PNG entries, so IconExe left the
  7 original Eclipse icons in chromite.exe.
"""
import struct
import sys
from pathlib import Path

from PIL import Image, ImageDraw

# (size, bit depth) in the order the entries are written.
ENTRIES = [(16, 8), (16, 32), (32, 8), (32, 32), (48, 8), (48, 32), (256, 32)]
MASTER_SIZE = 1024


def master_icon(root):
    """Navy rounded-square tile with the Chromite symbol centred (same as phase 1)."""
    sym = Image.open(root / "chromite_logo_pack" / "chromite_symbol_dark.png").convert("RGB")
    bg_color = sym.getpixel((3, 3))
    size = MASTER_SIZE
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.2), fill=255)
    tile.paste(Image.new("RGBA", (size, size), bg_color + (255,)), (0, 0), mask)
    h = int(size * 0.84)
    w = int(sym.width * h / sym.height)
    s = sym.resize((w, h), Image.LANCZOS)
    x, y = (size - w) // 2, (size - h) // 2
    tile.paste(s, (x, y), mask.crop((x, y, x + w, y + h)))
    return tile, bg_color


def and_mask(alpha, n, threshold):
    """1-bit AND mask, bottom-up, rows padded to 4 bytes; bit set = transparent."""
    stride = ((n + 31) // 32) * 4
    out = bytearray()
    for y in range(n - 1, -1, -1):
        row = bytearray(stride)
        for x in range(n):
            if alpha[y * n + x] < threshold:
                row[x >> 3] |= 0x80 >> (x & 7)
        out += row
    return bytes(out)


def bitmap_info_header(n, depth, colors_used):
    xor_stride = ((n * depth + 31) // 32) * 4
    and_stride = ((n + 31) // 32) * 4
    size_image = (xor_stride + and_stride) * n
    # biSize, biWidth, biHeight (XOR + AND), biPlanes, biBitCount, biCompression (BI_RGB),
    # biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant
    return struct.pack("<IiiHHIIiiII", 40, n, n * 2, 1, depth, 0, size_image, 0, 0, colors_used, 0)


def dib_32(img):
    n = img.width
    px = img.tobytes()  # RGBA
    xor = bytearray()
    for y in range(n - 1, -1, -1):
        row = px[y * n * 4:(y + 1) * n * 4]
        for x in range(n):
            r, g, b, a = row[x * 4:x * 4 + 4]
            xor += bytes((b, g, r, a))
    alpha = img.getchannel("A").tobytes()
    return bitmap_info_header(n, 32, 0) + bytes(xor) + and_mask(alpha, n, 1)


def dib_8(img, bg_color):
    n = img.width
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
    stride = ((n * 8 + 31) // 32) * 4
    xor = bytearray()
    for y in range(n - 1, -1, -1):
        row = bytearray(stride)
        for x in range(n):
            # Transparent pixels get index 0; the AND mask hides them.
            row[x] = idx[y * n + x] if alpha[y * n + x] >= 128 else 0
        xor += row
    return bitmap_info_header(n, 8, 256) + palette + bytes(xor) + and_mask(alpha, n, 128)


def build_ico(master, bg_color):
    images = []
    for n, depth in ENTRIES:
        img = master.resize((n, n), Image.LANCZOS)
        images.append(dib_32(img) if depth == 32 else dib_8(img, bg_color))
    header = struct.pack("<HHH", 0, 1, len(ENTRIES))
    offset = 6 + 16 * len(ENTRIES)
    directory = b""
    for (n, depth), data in zip(ENTRIES, images):
        dim = 0 if n >= 256 else n
        # bWidth, bHeight, bColorCount (0 = 256 or true colour), bReserved, wPlanes, wBitCount, dwBytesInRes, dwImageOffset
        directory += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, depth, len(data), offset)
        offset += len(data)
    return header + directory + b"".join(images)


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "product" / "community" / "icons" / "dbeaver.ico"
    master, bg_color = master_icon(root)
    out.write_bytes(build_ico(master, bg_color))
    print("wrote", out, [f"{n}x{n}/{d}bit" for n, d in ENTRIES])


if __name__ == "__main__":
    main()

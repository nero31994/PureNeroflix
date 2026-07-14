#!/usr/bin/env python3
"""
gen_launcher_icons.py — Regenerate all mipmap launcher icons from a single source image.
Run from repo root: python3 gen_launcher_icons.py
"""
from PIL import Image, ImageDraw
import os

SRC = "app/src/main/res/drawable-import/source_icon.png"
RES = "app/src/main/res"

# Standard Android mipmap sizes (in px) per density bucket
DENSITIES = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

# Foreground source for adaptive-style usage (drawable, not mipmap)
FOREGROUND_SIZE = 432  # standard adaptive icon foreground canvas

def make_square_icon(img, size):
    """Resize image to fit a square canvas, padding with transparency if needed."""
    img = img.convert("RGBA")
    # Fit image within square while preserving aspect ratio
    img.thumbnail((size, size), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = ((size - img.width) // 2, (size - img.height) // 2)
    canvas.paste(img, offset, img)
    return canvas

def make_round_icon(img, size):
    """Apply circular mask for the _round variant."""
    square = make_square_icon(img, size)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(square, (0, 0), mask)
    return result

def main():
    if not os.path.exists(SRC):
        print(f"ERROR: source image not found at {SRC}")
        return

    src_img = Image.open(SRC)
    print(f"Source image loaded: {src_img.size}, mode={src_img.mode}")

    for density, size in DENSITIES.items():
        mipmap_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(mipmap_dir, exist_ok=True)

        # Square icon
        square = make_square_icon(src_img, size)
        square_path = os.path.join(mipmap_dir, "ic_launcher.png")
        square.save(square_path, "PNG")

        # Round icon
        round_icon = make_round_icon(src_img, size)
        round_path = os.path.join(mipmap_dir, "ic_launcher_round.png")
        round_icon.save(round_path, "PNG")

        print(f"  {density}: {size}x{size} -> ic_launcher.png + ic_launcher_round.png")

    # Foreground drawable (used by some adaptive-icon configs / splash screens)
    foreground = make_square_icon(src_img, FOREGROUND_SIZE)
    fg_path = os.path.join(RES, "drawable", "ic_launcher_foreground.png")
    foreground.save(fg_path, "PNG")
    print(f"  drawable: {FOREGROUND_SIZE}x{FOREGROUND_SIZE} -> ic_launcher_foreground.png")

    print("\nAll launcher icons regenerated successfully.")

if __name__ == "__main__":
    main()

#!/bin/bash
# Generate extension icons using ImageMagick - Neon style, transparent bg

cd "$(dirname "$0")/.."

for size in 16 48 128; do
  # Font fills most of the icon (90%)
  font_size=$((size * 90 / 100))

  # Glow blur radius scales with size
  blur_radius=$((size / 6))
  if [ $blur_radius -lt 1 ]; then blur_radius=1; fi

  # Create transparent background with bright neon green 學 character
  magick -size ${size}x${size} xc:transparent \
    -fill '#00ff88' \
    -font "Heiti-SC-Medium" \
    -pointsize $font_size \
    -gravity center \
    -annotate +0+0 "學" \
    \( +clone -channel A -blur 0x${blur_radius} -level 0,50% +channel \) \
    -compose DstOver -composite \
    icons/icon${size}.png

  echo "Created icon${size}.png"
done

echo "Done!"

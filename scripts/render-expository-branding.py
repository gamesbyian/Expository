#!/usr/bin/env python3
"""Render the v1 branding SVGs to 1536px-wide PNGs (requires cairosvg)."""
from pathlib import Path
import re
import cairosvg

assets = Path(__file__).resolve().parents[1] / "assets" / "branding"
source_names = (
    "magnifier-disk-stack-centred.svg",
    "expository-v1-icon-square.svg",
    "draft-artwork/magnifier-v1-echoes-horizontal.svg",
    "draft-artwork/magnifier-v1-echoes-vertical.svg",
)
for name in source_names:
    src = assets / name
    match = re.search(r'viewBox="([^"]+)"', src.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"Missing SVG viewBox: {src}")
    _, _, width, height = map(float, match.group(1).split())
    target = src.with_suffix(".png")
    cairosvg.svg2png(url=str(src), write_to=str(target),
                     output_width=1536, output_height=round(1536 * height / width))
    print(target.relative_to(assets))

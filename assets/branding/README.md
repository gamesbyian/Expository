# Expository branding

## Canonical v1

The approved **[full master](magnifier-disk-stack-centred.svg)** is the reoriented pastel magnifier, with a tightly framed SVG canvas. The magnifier and coloured echoes are mirrored and rotated 90° counterclockwise compared with the original experiment, but the pastel disk stack retains its original tilt.

The [square icon SVG](expository-v1-icon-square.svg) uses exactly the same geometry and colours, with a tighter square viewBox `109 194 920 920`, intentionally clipping the handles as in the approved icon crop. The master uses `71 154 1379 992`, retaining the full magnifier and its pastel echoes. Neither embeds raster imagery.

The earlier canonical disk-stack master is archived as [magnifier-disk-stack-centred-original-v1.svg](draft-artwork/magnifier-disk-stack-centred-original-v1.svg).

## Exploration variants

Two additional parallel-echo studies are retained in [draft-artwork/](draft-artwork/):

- [Horizontal-only](draft-artwork/magnifier-v1-echoes-horizontal.svg): offsets visually right by +69.12, +138.24, +207.36, with no vertical offsets or rotations.
- [Vertical-only](draft-artwork/magnifier-v1-echoes-vertical.svg): offsets visually down by +69.12, +138.24, +207.36, with no horizontal offsets or rotations.

These two are **draft variants**, not substitutes for canonical v1. All variants leave the disk stack and white foreground lens unchanged. Because the v1 glass is represented by `matrix(0 1 1 0 0 0)`, its local x/y coordinates are swapped on display; therefore horizontal-only echoes use internal `translate(0, n)`, and vertical-only echoes use `translate(n, 0)`.

The PNG exports can be regenerated from the SVGs using [scripts/render-expository-branding.py](../../scripts/render-expository-branding.py). Designation as brand assets does not mean app launcher icons or favicons have been deployed.

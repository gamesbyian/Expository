# Black-lens monochrome-inverted pastel repository variant

[Editable SVG](magnifier-four-layer-half-spacing-pastel-lens-inverted.svg) derives from the [previous pastel-lens composition](magnifier-four-layer-half-spacing-pastel-lens.svg) and leaves the white magnifier and red, blue, yellow shadow geometry untouched.

Changes are **limited to the lens contents**:

- Lens background: exactly `#000000`.
- Cylinder outlines, tier dividers, top rim, status dots, and dotted connectors: white `#FFFFFF` rather than near-black.
- Folder outline strokes: white; formerly white folder fills: solid black. No pastel colour changes (`#F2ABB0`, `#A1CAEB`, `#F8DF9D`, `#FFF0BD`).
- Disk status indicators moved from `cx=120` on the left to **`cx=585` on the right**, retaining each original `cy` and `r=21`.
- Lower folder transform translation changed from `(203, 511)` to **`(207, 513)`**, equivalent to +2 ticks right and +1 tick down.
- Upper connector group: `translate(-6 2)`, equivalent to 3 ticks left and 1 tick down.
- Lower connector group: `translate(0 -2)`, equivalent to 1 tick up.

**One tick = 2 SVG units**, roughly 1.8 output pixels at the current 1536px render framing. Connector coordinates remain unchanged relative to their group and retain editable per-circle `cx`/`cy` values. Source cylinders, folders and backdrop remain vector geometry, with no embedded raster.

Both earlier SVGs are preserved as separate versions. This is branding artwork, not a change to either shipped application's interface.

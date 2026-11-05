# Enlarged pastel-lens magnifier

[Editable SVG](magnifier-four-layer-half-spacing-pastel-lens-expanded.svg) is a variant of the previous [inverted black-lens composition](magnifier-four-layer-half-spacing-pastel-lens-inverted.svg). No earlier asset is modified.

- **Tick size:** 2 source-coordinate SVG units.
- **Before global resizing:** bottom folder translation moves from `(207 513)` to `(211 515)`: +4 right, +2 down. Upper connector dots translation moves from `(-6 2)` to `(-12 4)`: -6 left, +2 down. The lower connector stays at `(0 -2)`.
- **Everything inside the lens** is enclosed in the separate editable `#lens-artwork-layout` group. Its transform is `translate(6 0) translate(546.102 466.631) scale(1.1) translate(-546.102 -466.631)`. The 110% enlargement is anchored at the original lens centre (546.102, 466.631) and followed by +6 horizontal SVG units (3 ticks). The fixed foreground lens clipping path is **not** enlarged, so oversized artwork is deliberately cropped behind the white rim.
- Outer echo colours now match storage stack tiers exactly: **red** `#F2ABB0`, **blue** `#A1CAEB`, **yellow** `#F8DF9D`. White foreground magnifier, all echo angles and offsets, lens's black background, and the pastel stack itself remain intact.
- Every cylinder path, folder, connector dot, ring and shadow is still native, editable SVG. No raster embed or scripted effect.

To make further position and scale changes, edit `#lens-artwork-layout`; to tweak individual details, edit `#lower-folder-placement` and `#upper-dotted-connector`. The same shared magnifier silhouette remains reusable for all four echoes.

This is a standalone artwork variant, not a replacement for the deployed web/Android application icons.

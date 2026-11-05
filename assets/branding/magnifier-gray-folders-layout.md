# Gray folders and open spacing variant

This [editable SVG](magnifier-four-layer-half-spacing-pastel-lens-gray-folders.svg) derives directly from [the previous pastel-expanded composition](magnifier-four-layer-half-spacing-pastel-lens-expanded.svg), preserving it unchanged.

Edits inside the lens:
- Middle folder: `translate(205 367)` → `translate(240 383)`, moving it 35 SVG units right and 16 down before the shared 110% lens scale.
- Lower folder: `translate(211 515)` → `translate(195 575)`, moving it 16 SVG units left and 60 down. Intentional crop at the lower lens boundary keeps visual separation from the middle folder.
- Top dotted connector: `translate(-12 4)` → `translate(-35 4)`, moving it 23 SVG units left to rebalance the gap to the top folder.
- The reusable `#folder-outline` definition has `stroke-width="27"` (exactly 25% less than 36) and `fill="#A6A6A6"` (light-medium neutral gray). Its white outline remains unchanged in color.

All adjustments are editable independently in named SVG groups. The outer white magnifier and its pastel red/blue/yellow echoes, the pastel disk stack, lens scale and clip, dots, and the true-black lens background are otherwise unchanged. This is a standalone branding exploration; it is not deployed as the app's icon.

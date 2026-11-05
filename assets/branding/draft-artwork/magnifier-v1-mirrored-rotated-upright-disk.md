# Reoriented magnifier with original disk orientation (draft)

[SVG](magnifier-v1-mirrored-rotated-upright-disk.svg) is a separate experimental derivative of [canonical v1](../magnifier-disk-stack-centred.svg). The canonical file is unchanged.

The **magnifier and all three echoes** are first mirrored horizontally and then rotated 90° counterclockwise about the nominal 1536-unit canvas centre. The resulting net coordinate map is exactly `(x,y) → (y,x)`, implemented as a matrix `matrix(0 1 1 0 0 0)` on the magnifier/echo groups, black lens and white foreground rim. The foreground lens clip is transformed identically.

The **disk stack is not mirrored or rotated** by that operation: it retains the original independent `rotate(-11 ...)` positioning and internal source paths, including pastel fills, three white dots and stroke widths. Only its parent placement group is translated `(-79.471, +79.471)`, matching the relocated lens centre. The enlarged `viewBox="-40 -90 1800 1800"` keeps the full yellow echo visible.

The PNG rendition is generated directly from the vector source with a 1536×1536 target viewport. Neither the running application icon nor the canonical v1 logo is changed.

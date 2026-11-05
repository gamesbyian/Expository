> Historical draft explorations. See [canonical v1](../magnifier-disk-stack-centred.svg).

# Alternate magnifier explorations: disk-only and rising echoes

Both files are independent, editable standalone SVG variants derived from [the accepted gray-folder artwork](magnifier-four-layer-half-spacing-pastel-lens-gray-folders.svg). The accepted source remains unchanged.

## [Disk-stack only](../magnifier-disk-stack-centred.svg)

Remove the three folders and dotted connectors from the inner lens, preserving the actual source cylinder paths and all pastel tier fills. The original cylinder retains its **−11° tilt**. To fit it fully inside the white lens, use `repository-cylinder-placement="rotate(-11 546.102 466.631) translate(366.072 149.411) scale(0.51)"` and remove the earlier 110% shared lens contents scaling. The disk stack is centred on lens pivot (546.102,466.631), using cylinder nominal centre (353,622). Disk stroke width **38 → 30.4** (−20%), while status indicator dot radii stay **21** in the source coordinate space. White rim, black backdrop, and existing pastel echoes remain unchanged.

## [Rising counterclockwise echoes](magnifier-rising-counterclockwise-echoes.svg)

Keep the complete gray-folder lens interior unchanged and maintain each echo's x-offset, reversing its y-offset and rotation around the same lens pivot:

| Layer | Offset x | Offset y | Rotation |
| --- | ---: | ---: | ---: |
| White | 0 | 0 | 0° |
| Pastel red | +69.12 | −69.12 | −9.5° |
| Pastel blue | +138.24 | −138.24 | −19° |
| Pastel yellow | +207.36 | −207.36 | −28.5° |

The `viewBox` is changed to `-110 -275 1720 1720` to keep the upward-facing pastel echoes fully inside the square rendering without modifying their path geometry or transforms.

These are branding explorations, not new deployed app icon assets. Each variation retains native SVG paths and named editable group transforms.

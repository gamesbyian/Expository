> Historical draft explorations. See [canonical v1](../magnifier-disk-stack-centred.svg).

# Pastel repository in the half-spacing magnifier

[Editable composition SVG](magnifier-four-layer-half-spacing-pastel-lens.svg) combines the untouched original [half-spacing magnifier](magnifier-four-layer-half-spacing.svg) with the actual vector paths from [repository storage and pastel folders](repository-storage-folders-pastel.svg). All input source files remain intact.

The white rim, black exterior, and red/blue/yellow shadow positions remain mathematically identical to the half-spacing version: 9.5° / 19° / 28.5° clockwise and 4.5% / 9% / 13.5% diagonal offsets. Only the foreground *lens interior* changes, to pale blue-gray `#EBF0F4` supporting the black outlined source art.

## Independent SVG editing controls

- `repository-cylinder-placement`: `rotate(-11 403 481) translate(255 220) scale(0.42)`
- `upper-folder-placement`: `rotate(11 640 315) translate(219 233) scale(0.43)`
- `middle-folder-placement`: `rotate(-8 635 450) translate(205 367) scale(0.44)`
- `lower-folder-placement`: `rotate(13 625 593) translate(203 511) scale(0.43)`
- `upper-dotted-connector` / `lower-dotted-connector`: edit each dot's `cx`, `cy`, and radius
- `lens-interior`: edit its `fill` to choose a lighter/darker backdrop

All three folders reuse `#folder-outline`, so shape modifications update all three. Cylinder subpaths (including each pastel tier and indicator) remain independently editable. SVG transform order is right-to-left: scale, translate, then rotate around the stated lens-space pivot.

The complete scene is clipped by `#foreground-lens-clip`, a copy of the magnifier's precise `#lens-opening` path. Some objects intentionally extend beyond this crop to suggest magnification. If the lens contour is edited, update both paths together. There are no embedded raster files, external dependencies or scripts.

This is a standalone logo exploration, **not a change to the live app's logo or UI**.

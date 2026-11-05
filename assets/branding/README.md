# Four-layer magnifying-glass artwork

[Editable master SVG](magnifier-four-layer.svg) uses a single vector silhouette defined once as `#magnifier`, traced from the owner's supplied white-on-black magnifying glass. The white foreground and all three hard-edged shadows are instances of that same path. There are no embedded raster images or generative effects.

The SVG is **standalone design artwork**, not an instruction to change the deployed web explorer or Android app icon.

## Edit placements

The 1536 × 1536 original is the coordinate reference. The lens pivot is **(546.102, 466.631)**. Each shadow rotates a further **19° clockwise** and translates another **138.24 units (9% of 1536)** right and down relative to the white source. The layers are painted back-to-front so yellow is behind blue, blue behind red, and red behind white.

| Layer | Angle from white | Horizontal offset | Vertical offset | Colour |
| --- | ---: | ---: | ---: | --- |
| White | 0° | 0 | 0 | `#FFFFFF` |
| Red | 19° | 138.24 | 138.24 | `#FF0000` |
| Blue | 38° | 276.48 | 276.48 | `#0000FF` |
| Yellow | 57° | 414.72 | 414.72 | `#FFFF00` |

For a different arrangement, edit the `transform` on each named `<use>` in the SVG, preserving the common pivot. In each transform, the **rotation is applied first**, then the translation. For a changed outline, edit the `#magnifier` path only; every layer updates identically. The separate `#lens-opening` path is used once as a **foreground black knockout**, keeping the white lens opening black rather than allowing underlying shadows to show through. If editing the lens outline itself, update that knockout path as well.

The wider square `viewBox="-104 5 1720 1720"` keeps the fully rotated yellow handle visible without modifying the shadow geometry. The SVG uses a solid black backdrop for faithful preview; it can be removed if a transparent version is desired.

The traced base is an approximately one-pixel-accurate polygon simplification of the supplied raster artwork; the repeated layers and rotations/translations are mathematically exact.

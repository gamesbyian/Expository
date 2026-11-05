# Expository branding

## Approved canonical v1

**[Full v1 master SVG](magnifier-disk-stack-centred.svg)** uses the white magnifier and upright pastel storage stack with **horizontal-only, unrotated** red, blue and yellow echoes. Each layer shares the same editable magnifier silhouette. Visual horizontal offsets are 69.12, 138.24 and 207.36 SVG units to the right; there are no relative vertical offsets or rotations. The outer mirror/rotation used to orient the glass is part of the shared base geometry and unchanged.

**[Square v1 icon SVG](expository-v1-icon-square.svg)** uses the very same illustration, cropped to `viewBox="71 170 920 920"` to match the approved square-icon reference. The full master uses `viewBox="71 154 1209 961"`. Both have solid black backgrounds and preserve the storage cylinder's original angle, colour and indicator dots.

The previously canonical diagonal-echo design and its matching square crop are archived byte-for-byte as [former full master](draft-artwork/magnifier-v1-reoriented-diagonal-echoes.svg) and [former icon](draft-artwork/expository-v1-icon-square-reoriented.svg). Other superseded variants, including the vertical-only study and the source horizontal-only experiment, remain in [draft-artwork/](draft-artwork/) for comparison. Drafts are not the approved logo.

## Raster exports

Run `python scripts/render-expository-branding.py` at the repository root (with `cairosvg` installed) to render PNG files directly from the canonical SVGs and the axis-aligned variants. The PNG rendering preserves the vector geometry; the square icon is 1536×1536 and the full logo uses its original aspect ratio.

**Live consumers:** [PR #489](https://github.com/gamesbyian/Expository/pull/489) connected this approved square artwork to the Pages header and SVG/favicon links in `index.html`, and installed the corresponding platform-native Android launcher VectorDrawable through the app manifest. Source wiring and compilation are verified; an already installed Android APK retains its old embedded icon until replaced, and physical launcher/phone visual acceptance is not implied.

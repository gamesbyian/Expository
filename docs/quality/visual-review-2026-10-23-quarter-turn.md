# Quarter-turn image inspection visual review · 2026-10-23

**Evidence class: manually viewed Chrome screenshot of synthetic GitHub fixture, not physical Pixel or owner artistic approval.**

- Targeted Actions visual run: https://github.com/gamesbyian/Expository/actions/runs/40044501956
- Source checkout SHA: `2bfd9d0a7538b5f8ea92a9d1372bcb88584a6528` (temporary PR merge ref)
- Browser: Google Chrome 155.0.8059.39, emulated 393×852 portrait viewport
- Fixture: `synthetic-github-v1`, first-party generated SVG image with horizontal canvas and centered circular motif
- Capture: `2026-10-24T00:20:47.589Z`
- Screenshot: `expository-rotate-393.png`, SHA-256 `0728f6895e1ccec4c0276259498a3d29f8f1e83de3f6d12a598f3dbd62222c55`
- Offline atlas artifact: `expository-visual-atlas` ID `12541001161` (short-retention ZIP with receipt)

**Viewed result:** Quarter-turned 90° SVG text orientation demonstrates a real clockwise transform, not just changed button text. The image is centered horizontally in the tall, bounded stage with balanced checkerboard margins. Labels and controls are legible, the close button is visible, and no obvious overlap or horizontal page overflow occurs at this synthetic 393px frame. An earlier capture was rejected because the image frame clung to the stage's left edge; a CSS centering correction was made and the screenshot was recaptured.

**Automated behavioral evidence:** Pure tests verify intrinsic dimension swaps at 0°, 90°, 180°, 270°, zoom scale, four-click return to original DOM layout, and unmodified source Blob bytes. Chrome tests verify quarter-turn transform, fitted rectangle within stage, horizontal/vertical centering within tolerance, rotated zoom, full-cycle reset, original image orientation, and retained native fullscreen and pinch/pan behavior. All targeted checks passed.

**Known limits:** Rotation is a local preview transformation and does not edit or rewrite source bytes/metadata. Tested on synthetic SVG, not all raster formats or animated images. Intrinsic dimensions are available only after image decode; listener updates recalculate geometry. Real Pixel 6 Pro device touch, landscape, Android system fullscreen restrictions, accessibility at 200% text and TalkBack, and owner visual acceptance remain outstanding. The capture is not evidence of those gates.

**Acceptance:** inspected synthetic viewport, viewed-no-findings for this exact quarter-turn state. Device acceptance **not** completed.

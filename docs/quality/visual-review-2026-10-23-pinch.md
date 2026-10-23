# Expository Pinch Inspector · Synthetic visual review (2026-10-23)

**Evidence class: viewed Chromium screenshot; not a real-device or owner-approved reference.**

- Run: https://github.com/gamesbyian/Expository/actions/runs/40035117038
- Checkout source SHA: `2a068a9818de35ff3ccfaa4bb43425b997ed0de8` (PR merge checkout)
- Fixture: `synthetic-github-v1`
- Browser: Google Chrome 155.0.8059.39, emulated 393 × 852 CSS-pixel mobile viewport
- Screenshot: `expository-pinch-393.png`
- Screenshot SHA-256: `b74b7a3bb523dd7928587ba675118062e4ded8f24abb18d9482271f9c8c14f46`
- Artifact: `expository-visual-atlas` (ID `12536627415`, seven-day retention)
- Capture timestamp: `2026-10-23T22:40:35.544Z`

**Inspected finding:** Zoom label reads 180%. The teal-circle fixture visibly enlarges and clips within its intentionally scrollable image stage, with horizontal pan affordance. Header, close control, +/- zoom and reset are visible and legible. The instructional hint wraps but remains readable. No page-level overflow or obvious overlap is present in this one captured state. **Viewed-no-findings for this exact synthetic frame**, not a blanket visual/design approval.

**Structural evidence:** Targeted Chrome regression also exercised synthetic two-touch pinch (100%→180%), one-finger drag changing the image-stage scroll position, reset to 100%, and return to natural scroll chaining. Unit tests enforce 1× to 4× bounds.

**Unverified:** actual physical Pixel 6 Pro fingers/gestures, native browser gesture arbitration on Android Chrome, TalkBack, orientation changes, 200% text and visual preferences. Do not mark them passed. Keep the screenshot atlas manual-only for ordinary future changes.

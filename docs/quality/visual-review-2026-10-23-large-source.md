# Windowed source navigation · synthetic Chrome visual review

**Evidence class:** viewed real Chromium screenshot using synthetic GitHub fixtures, not physical Android device or owner approval.

- Run: https://github.com/gamesbyian/Expository/actions/runs/40040601736 (successful targeted retry)
- Source checkout SHA: `71323ea18b3c5d7aba518d54d73801af1265ba18` (PR merge ref)
- Fixture: `synthetic-github-v1`, generated 1,205-line `docs/huge.ts`
- Browser: Google Chrome 155.0.8059.39, 393×852 emulated phone viewport
- Capture: `2026-10-23T23:38:35.570Z`
- Screenshot: `expository-large-source-393.png`, SHA-256 `7fec5563016bd2a4f86b584464dee56081859601080118c53d61741cf11bdcaf`
- Offline screenshot atlas artifact: `12539216471`, short-retention GitHub Actions ZIP

**Viewed state:** `huge.ts` is open in the existing inspector. Synthetic TypeScript syntax highlighting and visible line numbers around line 1,101 are aligned, and `export function distantSymbol() {}` is highlighted after searching for that symbol beyond the first 500 displayed rows. Modal close/source actions remain visible. No obvious horizontal clipping or overlapping code lines in this particular image. This capture is focused on the *jump destination*, not the control panel at scroll position zero.

**Automated behavior:** Chromium checked that the first render has 500 code rows, Next moves to 501–1,000, Go to Line 1,205 reaches the last 205-row window, and searching for a symbol beyond the original window changes the source window and highlights exactly one matching line. All checks passed. Unit tests cover an independent 2,605-line example, 500-match cap, source-truncation warning and inert text rendering.

**Verdict:** viewed-no-findings for this narrow synthetic scrolled code view. The full product is *not* certified for real Pixel Chrome, 200% text, TalkBack, 500k-character truncation involving Unicode boundaries, very large real files or private-token performance. The loaded preview still caps content at 500k characters and the heuristic symbol outline only scans its first 2,600 lines. Beyond-cap file access requires download, not implied full-source inspection.

The ordinary CI job was restored to cheap Node/unit validation, and the visual atlas workflow should be left manually dispatched only.

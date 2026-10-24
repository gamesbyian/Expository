# Expository visual review and acceptance

**Three distinct classes:** (1) *design intention/reference* (owner-approved targets only); (2) *observed Chromium capture* from actual Expository code running with synthetic fixture responses; (3) *real device/human acceptance* on installed mobile browser, with recorded device/build and observation. A generated/archived screenshot is class 2 only when the capture actually ran; running a structural validator cannot establish its appearance. No class automatically promotes to another.

## Canonical visual surfaces

| ID | Scenario | Viewport / intended check |
|---|---|---|
| X01 | Initial/public repository browsing | 360×780, 393×852, 412×892 portrait; readable actions, no horizontal page overflow |
| X02 | Folder tree / navigation drawer | portrait with side panel; accessible expand/collapse, no blocked touch targets |
| X03 | Dense image gallery | 393×852 and 1440×900; labels, zoom density and no missing/duplicated items |
| X04 | File preview and code | 360×780, 393×852; modal focus, close control, raw actions, safe line numbers |
| X05 | Before/after reveal | 393×852; image wipe and visible slider without page scrolling |
| X06 | Two-image comparison | 360×780 and desktop; stacked and side-by-side readability |
| X07 | Pixel difference | 393×852; sample bound/error states and discernible changes |
| X08 | Story Mode | 360×780; dated events, evidence links, partial API unavailable |
| X09 | JSON and CSV | 393×852; data display and horizontal internal scrolling only |
| X10 | Contact sheet | after E02; selected file count, overflow/caps/export state |
| X11 | Accessibility | reduced motion, keyboard focus, text-scale and contrast; compare to DOM/ARIA evidence |
| X12 | Production security | public/private switch, token purge and untrusted Markdown/SVG; use only synthetic credentials in captures |
| X13 | File-size treemap | 393×852 and 1440×900; size-weighted regions, safe labels, accessible 44px list and explicit partial-index disclosure |
| X14 | Long source preview | 393×852; 500-line DOM window, 1,200+-line navigation, match highlighting, honest 500k-character preview cap, keyboard focus |
| X15 | Native image fullscreen | 393×852 image viewer; fullscreen entry/exit, retained close and zoom controls, Escape preserving file preview, non-support fallback and focus restoration |
| X16 | Image rotation | 393×852 raster/SVG at 90° and 270°, fit/crop and orientation, zoom at rotated angle, return to 0°, fullscreen interoperability, original-file byte preservation |

## Evidence receipt

Each visual audit should identify exact *source SHA*, fixture identifier, capture timestamp, viewport width/height and DPR, browser engine/version if known, screenshot name and SHA-256, automated check outcome and separate **viewed-by-human/owner** status. If any part is unknown, write "not captured", not a guessed value.

Open the offline `review/index.html` atlas from the workflow artifact. It groups PNGs by scene, permits zoom and side-by-side selection, and labels every plate **synthetic Chromium screenshot, not device evidence**. Compare an old atlas and new atlas as *separate* evidence sets unless source/viewport/fixture registration matches. Assess clipping, tap-area occlusion, legibility, hierarchy, motion state, and contrast manually.

Do not add agent-created visual references as approved baselines. Do not publish private repository images or token-bearing network captures. A screenshot may be structurally correct but artistically poor; document both, with a concrete next action.

**Decision classes:** `uninspected`, `automated-pass`, `viewed-with-findings`, `viewed-no-findings`, `owner-approved`. An automated-pass result alone never implies viewed.

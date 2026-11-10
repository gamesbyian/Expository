# Website Preview: cross-environment HTML document rendering

Status: **Full linked-resource Website Preview still proposed P1, not an Android beta P0 gate.** A **separate static precursor** shipped in web PR [#677](https://github.com/gamesbyian/Expository/pull/677): opt-in inert HTML iframe, raw source default, no relative asset/link resolution and no network requests. Do not count it as fulfilling the cross-environment user promise below.
Scope: Expository read-only GitHub Pages explorer and separate Expository Transfer Android application. This specification adds requirements; it does not change existing permission, release or validation gates.

## User promise

When a user selects a website document such as `index.html`, `about.htm`, or `page.xhtml` and chooses **Website Preview**, Expository displays a rendered webpage, not merely highlighted source. Images, stylesheets, fonts and navigation links resolve against files actually accessible in **the same selected environment**. For example, `/public_html/index.html` referencing `./style.css`, `images/logo.png` and `about.html` renders the first two and opens the third within the preview when those files exist.

Both apps retain **Source** or **Raw** inspection, and existing static/inert gallery thumbnails remain unchanged. Missing dependencies show a useful broken-resource indication, not fabricated content or an implicit cross-environment search.

### Formats and routing

- Initial document candidates: `.html`, `.htm`; `.xhtml` only with separate strict XML parsing and security coverage. Other XML, SVG, markdown and templating formats are **not** silently executable HTML. Optional recognized `.mhtml`/`.mht` archives require a separate bounded parser, MIME/security plan and acceptance before support is promised.
- Resolve relative references from the **current document directory**, including `./`, `../`, percent encoding, query/hash fragments, URLs in stylesheets (including CSS `url(...)` and `@import`), `srcset`, responsive images, and standard browser navigation. Root-relative `/foo` resolves against an explicitly defined preview site root, defaulting to the current selected browse root, not an arbitrary host filesystem root. Show the chosen site root, allow selection of an authorized ancestor/root, and never silently guess a different one.
- Same-environment means the pinned GitHub repo **and commit/branch snapshot**, the chosen SFTP/FTPS/FTP connection and scoped remote root, or the user-authorized Android document tree/provider and its grant. Do not cross repositories, branches, accounts, remote connections, local trees, or cloud profiles when resolving references. Detect and decline traversal beyond the authorized root, including after URL decoding, Unicode normalization and provider-specific path resolution.
- Internal links to existing HTML documents navigate in the preview, with Back/Forward and an inspectable current path. Links to other supported file types open the ordinary file viewer or ask permission to download; navigation must not mutate or upload files. Same-document fragments should work. Clearly distinguish external HTTPS links and request explicit user action before opening them outside the isolated viewer. Block dangerous schemes, unexpected redirects and attempts to escape scope.
- A file whose extension suggests HTML may still be binary or too large: bounded reads and MIME/sniff checks must produce honest Source/Download or error fallback. Files in archives are out of scope until a safe archive-member resolver is separately validated.

## Trust model

Untrusted HTML must **never execute in the application origin or acquire application credentials**. Preview is opt-in per file (not on directory listing or thumbnails) and uses a distinct sandboxed origin / isolated WebView boundary. Android must use a hardened asset-loading or request-interception boundary mapped only to explicitly authorized source reads; do not give arbitrary `file://` access or global storage permissions. The GitHub browser must not navigate a token-bearing request through an iframe or expose its in-memory GitHub PAT to document code.

**Initial capability is rendered HTML + CSS + images + fonts with JavaScript disabled**. Forms, service workers, storage, downloads, popups, autoplay, mixed content, external requests, cookies, clipboard, geolocation, device APIs and browser-origin network access are disabled unless a future separate security design deliberately permits them. Do not promise that every modern SPA works under this mode. If a trusted-content JavaScript mode is considered later, it needs its own explicit opt-in, security design and browser/Android acceptance; it is not part of P1 acceptance.

Use a **resource broker**: parse/normalize requests, validate the pinned source/root and file type/budgets, retrieve through the app's *existing authenticated read adapter*, and return bytes to the isolated renderer without exposing upstream credentials, provider URLs, session tokens or application-private caches. External HTTP(S) subresources and runtime navigations are blocked by default even if the original HTML refers to a CDN; tell users when content cannot be reproduced offline. A remote-site live-browser mode would be a different feature.

Treat symlinks, case sensitivity, redirects, provider document identities and logical paths as backend-specific capability checks. A `../` link may navigate within authorized root, but never through it. Access denied / missing / interrupted is distinct from a broken HTML document.

## Efficiency and lifecycle

- **Lazy, demand-driven resources**: do not pre-download the directory/tree; request only visible/needed resources and reuse permitted existing per-session caches. Limit concurrent fetches, per-resource and aggregate bytes, redirects, nested CSS imports, navigation depth and renderer lifetime, with explicit truncation/error disclosure. Measure these limits in synthetic stress tests and tune separately for mobile and GitHub API rate limits.
- Snapshot isolation: GitHub references remain pinned to selected repo/ref/commit (prefer immutable blob/SHA for resolved assets where possible); stop or invalidate pending responses when repo, branch, source root, account or session changes. For FTP-like sources, if a file changes during rendering, display a refresh-needed indication rather than silently mixing content where detectable.
- Android: use streaming and cancellation, honor metered data and background lifecycle, avoid unconditional disk copies or durable storage of remote content, retain SAF grant scope, and release WebView, byte buffers and resource handles when preview exits. Errors must never trigger blind retry of credentials.
- Accessible Preview/Source switch, path breadcrumb, Back/Forward, reload, missing-asset diagnostics and screen-reader focus. Works in Single and Dual modes without altering either pane's selection/scroll state. In Dual, the preview is bound to the pane and environment where it was opened.

## Implementation slices

1. **Shared contract and fixture corpus**: deterministic mini-sites with relative/parent/root paths, CSS imports, images, `srcset`, fonts, hashes, UTF-8/percent-encoded names, missing resources and malicious cross-root links. Define read-only `SiteResourceResolver` semantics independently of transport.
2. **Web GitHub explorer**: sandboxed isolated preview renderer, GitHub blob/tree-backed broker, strict origin separation and tests proving PAT/sibling repository data cannot leak. Keep source and existing inert thumbnails. No credentials persisted; avoid extra production infrastructure unless security design demonstrates necessity.
3. **Native Android**: isolated WebView with JS off, scoped resource bridge over the existing file/provider adapters, then verify SAF, SFTP and FTPS independently; plain FTP only when explicitly authorized in existing app policy. Same UI and resolver semantics across Single/Dual.
4. **Expand only from evidence**: document unsupported HTML features and compatibility; optional richer formats or JS are separate proposals.

## Acceptance examples

- **A1 Visual integrity:** a three-page site with nested CSS imports, relative images/fonts and CSS `url()` displays correctly; screenshots and asset counts match a known fixture within documented rendering differences.
- **A2 Navigation:** internal `about.html#team`, `../docs/start.html`, query and fragment navigation work when authorized; Back/Forward, source mode and file identity remain correct.
- **A3 Root and environment isolation:** links that attempt `../../`, encoded traversal, absolute drive/UNC paths, unrelated GitHub repo/branch, different FTP profile, provider switch or expired SAF grant never return data.
- **A4 Security:** scripts do not run, event handlers cannot execute, forms cannot submit, service workers/storage cannot persist, external subresource requests are blocked; no app/PAT/FTP credentials, private repo paths or document bytes leak across sessions, URLs, logs or error messages.
- **A5 Resource honesty:** missing image/CSS, unsupported cross-origin CDN, incomplete tree, 401/403/429, stalled remote reads, large file and disconnected source produce bounded, actionable diagnostics, not fabricated layouts or endless retries.
- **A6 Performance:** opening a page with hundreds of unrelated sibling files fetches only required assets; concurrency/budget and cancellation are measurable; no directory-wide bulk download, resource leak or excessive repeated API traffic.
- **A7 Native parity:** representative site works via SAF, SFTP and FTPS in Single and Dual; switching pane/root/profile while preview is open cancels old reads without mixing assets.
- **A8 Device accessibility:** Android and mobile Chrome portrait, large text, TalkBack/keyboard/focus, Back/Forward and reduced motion inspected on actual device before claiming user acceptance.

## Tracking and priority

Treat this as **P1 after core browse/transfer reliability**, *not* a release blocker for current Android P0. The web explorer can pursue the isolated read-only version independently. Update both strategic roadmaps when implementation is scheduled; no existing Android P01-P12 promises are reduced or replaced. Design review must settle the sandbox-origin and resource-broker architecture **before** opening a general HTML execution path.

Existing context: `FEATURE-ROADMAP.md`, `AGENTS.md`, `docs/WORK-QUEUE.md`, `docs/android-transfer/CURRENT-CONTRACT.md`, `docs/android-transfer/PRODUCT-PROPOSAL.md` and `android-transfer/WORK-QUEUE.md`.

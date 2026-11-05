# Smart Merge: qualification of independently picked SAF roots

**Accepted inert relationship diagnostic (2026-11-05):** [Merged #514](https://github.com/gamesbyian/Expository/pull/514) implements only `OVERLAP` / `UNKNOWN`; an exact same-authority opaque root alias refuses with no IPC. All other directional queries require an *independently observed* `Root.FLAG_SUPPORTS_IS_CHILD` capability and default to disabled; consistent positive answers are overlap/refusal evidence, while negative, unsupported, inconsistent, stale, revoked or canceled answers remain UNKNOWN. [Exact-head API35 synthetic-provider E2](https://github.com/gamesbyian/Expository/actions/runs/42097589744) executed **25/25 tests, zero skipped**, at `f88625d5`, with JVM, Android compile/lint and web checks green. It used injected positive query callbacks, **not real cross-grant provider-positive OS acceptance**. There is no live root capability fetch, runtime UI wiring, `DISJOINT_VERIFIED` state, planner exception, writer or persisted grant. Genuine same-authority separately picked E2 and physical/provider E4 remain unqualified.

**Status (2026-11-05): accepted inert diagnostic plus outstanding qualification, not shipped disjointness or merge authority.** Owner: native T4 Smart Merge (#384); shared command/recovery owners remain #324/#325. This document follows the accepted conservative M2 planner gate [#500](https://github.com/gamesbyian/Expository/pull/500), which refuses differently rooted, same-provider SAF source/destination pairs until disjointness is *proved*. It does not relax the gate.

## Platform facts and why the current refusal is correct

Android's `DocumentsContract.isChildDocument(ContentResolver, Uri parent, Uri child)` is public from API 29. `DocumentsProvider.isChildDocument(parentId, childId)` is a provider implementation hook; its default implementation returns `false`. A provider can expose multiple roots, multiple visible parents for a document, and opaque IDs that do not encode ancestor paths. A provider may need network or authentication to answer even a metadata query. An independent `ACTION_OPEN_DOCUMENT_TREE` result grants access only to the picked subtree, not general authority to inspect another root or invent its parent chain.

Official references:
- [DocumentsContract, isChildDocument (API 29)](https://developer.android.com/reference/android/provider/DocumentsContract#isChildDocument(android.content.ContentResolver,%20android.net.Uri,%20android.net.Uri))
- [DocumentsProvider, isChildDocument (API 21)](https://developer.android.com/reference/android/provider/DocumentsProvider#isChildDocument(java.lang.String,%20java.lang.String))
- [AOSP DocumentsProvider implementation and default false](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/provider/DocumentsProvider.java)

**Crucial evidence distinction:** `true` may provide a useful positive *overlap* observation after the caller has verified matching provider scope and OS grants. A returned `false` alone is *not* a disjointness certificate: it is also the provider default when the method is not implemented. Exceptions, missing flags, denied grants, timeouts, stale roots and ambiguous provider scope must remain `UNKNOWN`, not magically become `NO`.

## Proposed bounded, read-only adapter

1. Accept only two currently independently picked, transient Android SAF tree grants with real result flags; require the same explicitly validated authority, nonblank bounded IDs, a fresh exact current basket and destination identity, and original proof-expiry/clock checks.
2. Compare exact opaque directory IDs first. Equality means `OVERLAP` without any extra provider request. Continue using the existing #496 guard for exact source-directory/root/parent aliases.
3. Perform the directional child relationship query only when this *specific* provider/root genuinely advertises the applicable capability and both document URIs are authorized in that scope. Record `OVERLAP` for a trustworthy affirmative result. Query both source→destination and destination→source directions; a relationship in either direction forbids Merge.
4. On any failure, unavailable capability, negative/ambiguous answer or changed grant, return `UNKNOWN`. The planner's existing `DESTINATION_SAF_SEPARATION_UNPROVEN` gate remains active. Do not treat display paths, labels, root ordering or URI string prefixes as proof.
5. Keep a future distinct `DISJOINT_VERIFIED` state **unreachable** until concrete providers and their authorization/capability contracts have passed independent adversarial testing and acceptance criteria. Do not implement a general-purpose allowlist based only on vendor name or one successful device.
6. Never mutate data to probe case sensitivity or root ancestry. No sentinel files, temporary folders, rename probes, hidden writes, auto-deletion, persisted picker grants, background retries or broad-storage permissions.

## Evidence matrix for an eventual implementation

| Scenario | Expected observation | Planner outcome |
|---|---|---|
| Identical source/destination opaque directory within one authority | Positive exact alias | Block as inside source |
| Different root ID, provider confirms source ancestor of destination | Positive descendant | Block as inside source |
| Different root ID, provider confirms destination ancestor of source | Positive ancestor | Block and require new independent destination |
| Same authority, default `false` implementation or unspecified capability | Unknown | Keep #500 separation blocker |
| Provider throws, authenticates again, or revokes permission mid-query | Unknown | No preview authorization |
| Different authority with identical opaque ID text | Different scoped identities | Do not fabricate alias; existing independent checks still apply |
| Virtual/aliased document visible under multiple parents | Ambiguous unless proven | Block, do not infer a tree from display paths |
| Root membership changes between checks | Stale observation | Invalidate, re-pick and reobserve |
| Provider returns contradictory results on repeated reads | Unstable | Refuse; log only redacted diagnostic category |

## Required tests before even exposing diagnostics

- E1: pure tri-state model, directions, fail-closed invalidation, exact authority and grant checks, expiry/clock rollback, no false negative `DISJOINT` state.
- E2 synthetic Android providers: a real installed API35 fixture implementing `isChildDocument` positively, an unimplemented/default-negative provider, aliases, shared opaque IDs across authorities, denied queries, deliberate drift, cancellation, provider exception, and two independent genuine DocumentsUI grants. Prove **zero writes and zero recovery records** for every branch.
- E3 controlled adapter diagnostics only, no live mutation. Keep provider-specific behavior and counterexamples documented.
- E4 physical device + third-party providers, including non-file-backed/virtual roots and cloud authorization interruptions; prove whether any *narrow* disjointness certificate is sustainable. Unknown remains blocked.
- E5 signed build acceptance only after the actual review/no-replace command and #324/#325 durable recovery also pass, separately from this read-only ancestry work.

**Next executable slice:** implement an inert `OVERLAP / UNKNOWN` diagnostic probe with synthetic fixture coverage, without connecting any `UNKNOWN` result to a runnable preview or weakening #500. Only then evaluate whether a provider-specific affirmative disjointness witness is representable and worth shipping.

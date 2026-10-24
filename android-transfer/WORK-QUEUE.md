# Native Transfer implementation queue

Current review: https://github.com/gamesbyian/Expository/pull/32

| Gate | State | Next executable proof |
|---|---|---|
| G0 | In progress | Independent Android source/Compose shell and scoped CI present; establish dedicated native repo when repository creation is authorized, debug APK SHA proven in Actions #40071709212, add physical API 37 LAN review and owner privacy review |
| G1 | In progress | Kotlin planner and collision-safe renamer exercised with synthetic JVM scenarios; expand fuzz/fault staging/reconciliation and 40/500 item acceptance; never claim process-death recovery from pure state transitions |
| G2 | In progress · PR #33 | First Local SAF→SAF single file Copy picker and foreground pure stream engine: Android debug APK compiled #40114978482, need real OS/provider/device evidence and lifecycle negatives; full remote Save/Upload and fake E2E still pending |
| G6 | In progress · draft [PR #46](https://github.com/gamesbyian/Expository/pull/46) | Pinned SFTP New Folder uses immutable create-child planner + prewrite journal; 9 synthetic policy scenarios and controlled server MKDIR/post-stat/duplicate refusal + debug APK passed [run #40136358327](https://github.com/gamesbyian/Expository/actions/runs/40136358327). a same-session one-file Move is in [PR #47](https://github.com/gamesbyian/Expository/pull/47), eight planner and live no-overwrite SFTP cases passed [run #40137145475](https://github.com/gamesbyian/Expository/actions/runs/40137145475); split panes, other Move directions and device proof remain |
| G3 | In progress · FTPS E3 proof [run #40125931787](https://github.com/gamesbyian/Expository/actions/runs/40125931787) | FTPS PKIX trust-chain rejection and explicit trust + encrypted list/read verified on synthetic localhost; SFTP pinned client and synthetic correct/wrong-key lab in rebased PR #35 (rebased lab run #40126372217), wider protocol/capability matrix and Android device integration still pending |
| G4 | In progress · merged [#37](https://github.com/gamesbyian/Expository/pull/37), [#39](https://github.com/gamesbyian/Expository/pull/39) | Pinned SFTP browse, one-file remote Save As and exclusive SFTP upload compiled; lab upload/read-back/overwrite refusal passed [run #40128464641](https://github.com/gamesbyian/Expository/actions/runs/40128464641). Device, folders, revocation and large files unverified |
| G5 | In progress · merged #38, integration [#42](https://github.com/gamesbyian/Expository/pull/42) | All three foreground Copy routes now commit private journals before streaming; 12 journal + 8 stream tests and debug APK succeeded [run #40135499036](https://github.com/gamesbyian/Expository/actions/runs/40135499036). Device kill/reboot, resumption and real provider reconciliation unverified |
| G7 | Not started | Compact renamer UI and journaled apply/reconcile against real adapters |
| G8 | Not started | Real Drive SAF/API parent-write capability and distinct Docs conversion/open/share receipts |
| G9 | Not started | Independently installed, reviewed and tested signed beta |

Only the root `docs/android-transfer/RELEASE-READINESS.md` authorizes changing final gate statuses. No release claims from fake endpoints. CI normally runs JVM scenarios alone; debug compilation is a manually selectable workflow job.

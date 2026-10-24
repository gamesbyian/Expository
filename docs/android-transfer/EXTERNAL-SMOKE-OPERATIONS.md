# Secure remote smoke execution

The canonical [live server safety matrix](LIVE-SERVER-TESTING.md) and the native
[security policy](../../android-transfer/SECURITY.md) apply to all runs below.

## What exists
* `SecureFtps` now supports explicit TLS on 21 and implicit TLS on 990 through
  an explicit `FtpsMode` value. Both require valid TLS trust and hostname
  checks, protected data channels and no plaintext downgrade. This does not
  implement opt-in unencrypted FTP.
* The isolated local synthetic FTPS and SFTP labs remain the required E3
  certificate and SSH-key failure proofs.
* `FtpsExternalSmokeSuite` calls the **production Kotlin FTPS adapter** to
  authenticate and list the root of a read-only public test server. Rebex
  profiles additionally fetch up to 64 KiB of its known demo readme, verifying
  the final FTP completion reply. Wing and owner profiles list only.
* `SftpExternalSmokeSuite` calls the **production Kotlin SFTP adapter** with
  a mandatory externally obtained SSH SHA-256 host key. It fetches at most
  64 KiB of Rebex's known public readme if present; other profiles list only.

## Trigger via pull-request comment (authorized owner only)

The repository owner can trigger a live test by leaving **one exact comment**
on any Expository PR after this workflow is merged to `main`:

```text
/transfer-smoke rebex-explicit
/transfer-smoke rebex-implicit
/transfer-smoke wing-implicit
/transfer-smoke owner-explicit
/transfer-smoke owner-implicit
```

This also allows an integration agent with GitHub PR-comment access to invoke
the tests through the owner's connected GitHub identity. The comment creates
an `issue_comment` event, never a scheduled or automatic PR test.

**Security properties:** The event must concern a PR in this repository,
the author must be exactly `gamesbyian`, the complete comment must match
one allowlisted command, and Actions checks out **trusted `main` only**.
Comment text is data in an environment variable, never interpolated as
shell source. The public job has no owner-server credentials. The owner job
uses the same narrowly scoped secret and pinned S60 settings as the manual
workflow. A contributor's PR code cannot be executed with the owner's
credentials through this trigger.

SFTP remains in the manual `workflow_dispatch` path because an
independently confirmed SSH host-key fingerprint must be supplied. Never use
a comment to publish a server password or another secret. Live smoke runs
must remain infrequent and are not required checks for ordinary PRs.

## Manual execution
After merging the source branch into the default branch, open
[Transfer manual external smoke](https://github.com/gamesbyian/Expository/actions/workflows/transfer-live-smoke.yml)
and choose **Run workflow**. Select exactly one profile:
`rebex-explicit`, `rebex-implicit`, `wing-implicit`,
`rebex-sftp`, `wing-sftp`, `owner-explicit`,
`owner-implicit`, or `owner-sftp`.

Rebex and Wing public demo accounts are operator-published, read-only test
accounts. Do not turn the manual workflow into recurring CI. SFTP runs
**require an independently verified SSH public-host-key fingerprint** in
the `SHA256:...` field. Do not learn a fingerprint from a live handshake
and then immediately treat it as authoritative. A missing fingerprint
blocks login. Pin changes are security events requiring confirmation.

For owner testing, only the Expository repository secret named
`FTP_PASSWORD` is required. The workflow recovers host and account without
printing them from an immutable S60 source revision, commit
`417fec65458c500da903184118bf701086161006`, then validates the
host's expected Seedhost suffix. This prevents a moving S60 branch from
redirecting credentials. The secret name matches S60's workflow reference;
its actual presence in Expository is **not verifiable with the available
GitHub connector**. Only the owner job receives the password, and it is not
printed or sent to a public test server. Never paste a secret into a
workflow_dispatch input.

Owner tests attempt FTPS or SFTP **only**, and enumerate only the account's
top-level directory. They do not traverse `/downloads/S60`, download private
files, upload, rename, move, delete, or create folders. If the owner server
offers *only* cleartext FTP on port 21, the secure smoke test fails closed and
the insecure configuration should be assessed separately.

## Evidence and limits
Test fixture SHA, CI run URL, exact protocol and whether a read was actually
verified must accompany any acceptance claim. This workflow proves a server
protocol on a GitHub-hosted JVM; it does not prove Android networking, native
pickers, background recovery or permission handling. No external run has been
observed merely because this workflow exists. External availability and
certificate/host-key rotation can cause legitimate failures.

Tests intentionally suppress raw exception messages and avoid listing private
filenames in the Actions log. Run no scans, uploads, destructive tests or
high-volume workloads on Rebex or Wing. Owner write fixtures require a
separate dedicated sandbox and scoped permission proof.

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

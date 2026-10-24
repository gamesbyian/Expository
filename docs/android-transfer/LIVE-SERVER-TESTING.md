# Expository Transfer: controlled live-server validation (2026-10-24)

## Authority and source
The product owner authorized using their FTP server for testing. The S60 repository README identifies a Seedhost server and existing `/downloads/S60/DVD`, `/downloads/S60/1080p`, and `/downloads/S60/720p` media trees. These **are not test fixtures**. Do not alter, rename, move, overwrite, or delete their contents. The Expository repository reportedly has a newly created password secret; the existence, name and available workflow injection **have not been independently verified**.

## Credential discovery and safeguards
1. Inspect the S60 repository configuration/docs to obtain the precise host, protocol, port, and account **without putting passwords, tokens or full private URLs in logs or committed files**. The README alone does not contain the connection tuple. Do not guess it.
2. Inspect existing Expository Actions workflows for the **actual** secret name; never print or echo its value, and never assume a secret is available to pull_request events or forks.
3. Test against a dedicated, minimal-privilege account and a uniquely named disposable server-side sandbox such as `/expository-transfer-test/<run-id>/`, **only after verifying creation rights and path containment**. If the server operator has not confirmed writable scope, use read-only tests.
4. Require preflight canonicalization and boundary checks for every remote operation. No recursive delete on owner media trees, no destructive cleanup outside the explicit disposable sandbox. Recheck remote path after symlink resolution where the protocol permits; deny unsafe ambiguity.
5. Run potentially destructive compatibility fixtures on disposable **local** protocol servers by default, not on production-like hosting. Use capped file sizes, bounded retries, timeouts, and no secrets in artifacts, traces or screenshots.
6. Do not conflate FTP, FTPS and SFTP. Require validated TLS for FTPS and strict host key verification/TOFU with prominent fingerprint confirmation for SFTP; never downgrade a secure connection silently.

## Verified external read-only smoke-test services
* [Rebex test server](https://test.rebex.net/): `test.rebex.net`, username `demo`, password `password`. FTP TCP/21; explicit FTPS TCP/21; implicit FTPS TCP/990; SFTP TCP/22. All endpoints are read-only, and FTP speed is limited. Vendor documentation expressly advises against running a continuous CI test suite against the shared service.
* [Wing FTP online demo](https://www.wftpserver.com/onlinedemo.htm): `demo.wftpserver.com`, username `demo`, password `demo`. FTP TCP/21; FTPS TCP/990; SFTP TCP/2222. Vendor describes demo accounts as read-only.
* [SFTP.net public host directory](https://www.sftp.net/public-online-sftp-servers) and [FTP directory](https://www.sftp.net/public-online-ftp-servers): discovery sources only. Verify an individual server's operator terms and permissions before adding it to a test matrix. Do not assume permission to upload.

These public demo credentials are intentionally published by their operators. Validate SFTP fingerprints through trustworthy channels, not blind automatic acceptance. External servers are best-effort/manual smoke tests only. Never run fuzzing, load tests, destructive commands, bulk scans, or unbounded retries on shared services.

## Protocol smoke matrix
| Target | Permitted tests | Prohibited |
|---|---|---|
| Rebex FTP | connect, authenticate, list, bounded download, metadata | upload, delete, rename, stress |
| Rebex explicit FTPS | TLS handshake/certificate validation, list, bounded download | insecure fallback, mutation |
| Rebex implicit FTPS | TLS handshake/certificate validation, list, bounded download | insecure fallback, mutation |
| Rebex SFTP | SSH host-key checking, auth, list, bounded download | host-key bypass, mutation |
| Wing FTP/FTPS/SFTP | same non-destructive connection/list/download operations; independent implementation | mutation, stress |
| Owner Seedhost | opt-in, bounded read-only sessions; disposable sandbox writes only after positive scope verification | touching S60 media trees, arbitrary deletes |
| Disposable local servers | permission checks, upload/download, mkdir, rename/move, collisions, cancellation, resume, fault injection, safe cleanup | touching host files outside ephemeral root |

## Implementation and evidence
* Separate fast deterministic unit/fake adapter tests from controlled loopback protocol integration tests, and from **manual opt-in live smoke tests**.
* Record protocol, implementation version where known, Android/desktop runtime, negotiated security properties, transfer size, digest result, cleanup disposition and sanitized failure class; never record credentials, absolute owner paths, private hostnames or sensitive filenames.
* CI must default to local isolated fixtures; gated live runs require explicit workflow dispatch and permission boundaries, not a routine PR or scheduled event.
* Test malformed listings, Unicode filenames, hidden entries, permission errors, directory traversal attempts, interrupted transfers, remote symlink behavior, partial uploads and restart behavior in the disposable local environment.
* Promote successful tests only to the appropriate evidence class: a synthetic planner pass is not live adapter evidence; live desktop-network evidence is not an Android device acceptance.
* Treat real Google Drive/Docs tests as separately scoped OAuth/provider checks; none of the FTP credentials grants permission for them.

## Next verification gate
Confirm S60 connection configuration and precise Actions secret identifier privately, demonstrate read-only listing with TLS/host-key verification, and only then enable isolated write-fixture tests in a designated test directory. If any prerequisite is missing, report it as blocked rather than inferring access or claiming a network pass.

#!/usr/bin/env python3
"""Disposable localhost-only explicit FTPS fixture. Synthetic data only.

Requires pyftpdlib and pyOpenSSL; CI supplies a fresh SAN certificate/key.
Never enable this server outside isolated verification.
"""
import os
from pathlib import Path

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import TLS_FTPHandler
from pyftpdlib.servers import FTPServer


def main() -> None:
    root = Path(os.environ["FTPS_FIXTURE_ROOT"]).resolve(strict=True)
    cert = Path(os.environ["FTPS_TEST_CERT"]).resolve(strict=True)
    key = Path(os.environ["FTPS_TEST_KEY"]).resolve(strict=True)
    if not root.is_dir() or not cert.is_file() or not key.is_file():
        raise RuntimeError("Missing isolated FTPS fixture inputs")

    authorizer = DummyAuthorizer()
    # Read/list/traverse only; no upload, rename, directory creation or delete.
    authorizer.add_user("synthetic", "synthetic-secret", str(root), perm="elr")

    class FixtureHandler(TLS_FTPHandler):
        pass

    FixtureHandler.authorizer = authorizer
    FixtureHandler.certfile = str(cert)
    FixtureHandler.keyfile = str(key)
    FixtureHandler.tls_control_required = True
    FixtureHandler.tls_data_required = True
    FixtureHandler.passive_ports = range(30100, 30116)
    FixtureHandler.banner = "Synthetic explicit FTPS verification only"
    FixtureHandler.timeout = 30
    FixtureHandler.dtp_timeout = 30
    FixtureHandler.max_login_attempts = 2

    server = FTPServer(("127.0.0.1", 2121), FixtureHandler)
    server.max_cons = 4
    server.max_cons_per_ip = 4
    try:
        server.serve_forever(timeout=1, blocking=True, handle_exit=True)
    finally:
        server.close_all()


if __name__ == "__main__":
    main()

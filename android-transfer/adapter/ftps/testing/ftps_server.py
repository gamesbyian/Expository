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

    # Only 127.0.0.1 and explicitly selected disposable test ports.
    # The second fixture models a server whose directory commands refuse
    # listings even though MLST and encrypted RETR remain available.
    listing_denied = os.environ.get("FTPS_FIXTURE_DENY_LISTINGS") == "1"
    control_port = int(os.environ.get("FTPS_FIXTURE_PORT", "2121"))
    if control_port not in (2121, 2122):
        raise ValueError("FTPS fixture allows only two localhost control ports")

    class FixtureHandler(TLS_FTPHandler):
        def ftp_MLSD(self, path):
            if listing_denied:
                self.respond("502 Synthetic MLSD deliberately unavailable")
                return
            return super().ftp_MLSD(path)

        def ftp_LIST(self, path):
            if listing_denied:
                self.respond("502 Synthetic LIST deliberately unavailable")
                return
            return super().ftp_LIST(path)

    FixtureHandler.authorizer = authorizer
    FixtureHandler.certfile = str(cert)
    FixtureHandler.keyfile = str(key)
    FixtureHandler.tls_control_required = True
    FixtureHandler.tls_data_required = True
    FixtureHandler.passive_ports = (
        range(30120, 30136) if listing_denied else range(30100, 30116)
    )
    FixtureHandler.banner = "Synthetic explicit FTPS verification only"
    FixtureHandler.timeout = 30
    FixtureHandler.dtp_timeout = 30
    FixtureHandler.max_login_attempts = 2

    server = FTPServer(("127.0.0.1", control_port), FixtureHandler)
    server.max_cons = 4
    server.max_cons_per_ip = 4
    try:
        server.serve_forever(timeout=1, blocking=True, handle_exit=True)
    finally:
        server.close_all()


if __name__ == "__main__":
    main()

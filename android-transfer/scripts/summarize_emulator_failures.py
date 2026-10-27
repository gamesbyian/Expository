#!/usr/bin/env python3
"""Minimal sanitized API35 instrumentation failure summary for CI.

Only reports test identifiers and source-code frames. Never prints raw
exception messages, server replies, file names, provider URIs or device logs.
The complete XML is already retained as a short-lived workflow artifact.
"""
from __future__ import annotations

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path("android-transfer/app/build/outputs/androidTest-results/connected")
TEST_IDENTIFIER = re.compile(r"[^a-zA-Z0-9_.$#-]")
SOURCE_FRAME = re.compile(r"\\b([A-Za-z][A-Za-z0-9_]*\\.(?:kt|java)):(\\d{1,6})\\b")


def main() -> None:
    count = 0
    paths = sorted(ROOT.rglob("*.xml")) if ROOT.exists() else []
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in root.iter("testcase"):
            failure = next((child for child in case if child.tag in ("failure", "error")), None)
            if failure is None:
                continue
            identifier = ".".join([
                case.attrib.get("classname", "unknown"),
                case.attrib.get("name", "unknown"),
            ])
            identifier = TEST_IDENTIFIER.sub("_", identifier)[:190]
            frames = SOURCE_FRAME.findall(failure.text or "")
            # Frame locations alone, never raw exception strings, device paths,
            # user document names or other untrusted provider data.
            locations = [f"{name}:{line}" for name, line in frames][:6]
            print(f"FAIL {identifier} at {', '.join(locations) or 'no source frame'}")
            count += 1
    if count == 0:
        print("No failed testcase entries found in available Android XML reports.")
    else:
        print(f"Failed Android testcases: {count} (sanitized names and code locations only)")


if __name__ == "__main__":
    main()

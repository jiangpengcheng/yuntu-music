#!/usr/bin/env python3
"""Validate version/tag, signed APK identity and prepare immutable Release assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from build_android import ROOT, BUILD, sdk_root, version

SIGNER = "f0e5139012d6aaef419a02201dc3355936994572fd91946d76f1d344002ebc5d"


def validate_tag(tag, name):
    if tag != "v" + name:
        raise ValueError(f"Tag must equal v{name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    name, code = version()
    validate_tag(args.tag, name)
    metadata = json.loads((BUILD / "build-info.json").read_text())
    if not metadata["release"] or metadata["version"] != name:
        raise ValueError("A matching release-signed build is required")
    apk = Path(metadata["apk"])
    tools = sdk_root() / "build-tools" / os.environ.get("ANDROID_BUILD_TOOLS", "33.0.2")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    for expected in ("name='io.github.yuntumusic.direct'", f"versionName='{name}'",
                     f"versionCode='{code}'", "sdkVersion:'19'", "targetSdkVersion:'23'"):
        if expected not in badging:
            raise ValueError("APK manifest mismatch: " + expected)
    cert = subprocess.check_output([str(tools / "apksigner"), "verify", "--min-sdk-version", "19",
                                    "--verbose", "--print-certs", str(apk)], text=True)
    if SIGNER not in cert or "Verified using v1 scheme (JAR signing): true" not in cert:
        raise ValueError("Release signing certificate or API-19 signature mismatch")
    with zipfile.ZipFile(apk) as archive:
        if archive.read("classes.dex")[:8] != b"dex\n035\0":
            raise ValueError("Wrong DEX version")
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    asset = dist / f"yuntu-music-{name}.apk"
    shutil.copyfile(apk, asset)
    digest = hashlib.sha256(asset.read_bytes()).hexdigest()
    (dist / "SHA256SUMS.txt").write_text(f"{digest}  {asset.name}\n")
    print(f"Verified {args.tag}: {asset.stat().st_size} bytes, SHA256 {digest}")


if __name__ == "__main__":
    main()

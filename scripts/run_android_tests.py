#!/usr/bin/env python3
"""Run deterministic tests on an isolated emulator. Erases this app's emulator data."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android/build"
PACKAGE = "io.github.yuntumusic.direct"
OFFLINE = ["NativeTest", "NativeNetworkTest", "MediaOutputTest", "OverlayTextTest",
           "LargeCardTest", "FloatingLyricsTest", "StartupTest", "OverlayCompatTest"]
FIXTURE = set(OFFLINE) - {"NativeTest", "NativeNetworkTest"}
LIVE = ["NativeLiveTest", "NativePlaybackTest"]


def result_count(output):
    """adb exits zero even for instrumentation failures; require a positive final result."""
    if re.search(r"(?m)^(?:FAIL\b|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)|"
                 r"INSTRUMENTATION_RESULT: (?:failure|shortMsg)=|INSTRUMENTATION_CODE: 0\b", output):
        raise ValueError("Instrumentation reported failure")
    match = re.search(r"(?m)^PASS (\d+)\b|^INSTRUMENTATION_RESULT: count=(\d+)\b", output)
    if not match or int(match.group(1) or match.group(2)) < 1:
        raise ValueError("Missing positive instrumentation result")
    return int(match.group(1) or match.group(2))


@contextmanager
def fixture(log):
    with log.open("w") as output:
        process = subprocess.Popen(["node", str(ROOT / "android/test/fixture.mjs")],
                                   stdout=output, stderr=output)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Fixture exited before becoming ready")
                try:
                    with socket.create_connection(("127.0.0.1", 3211), .1):
                        break
                except OSError:
                    time.sleep(.1)
            else:
                raise TimeoutError("Fixture startup timed out")
            yield
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--include-live", action="store_true", help="Opt into real upstream/CDN tests")
    parser.add_argument("--suite", choices=OFFLINE, action="append", help="Only run selected offline suites")
    args = parser.parse_args()
    adb = os.environ.get("ADB", "adb")
    results = BUILD / "test-results"
    results.mkdir(parents=True, exist_ok=True)

    def device(*command, timeout=300, check=True):
        completed = subprocess.run([adb, "-s", args.serial, *command], text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=timeout, check=check)
        return completed.stdout

    if device("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Refusing to erase app data on a non-emulator device")
    metadata = json.loads((BUILD / "build-info.json").read_text())
    for package in (PACKAGE + ".test", PACKAGE):
        device("uninstall", package, check=False)
    for apk in (metadata["apk"], str(BUILD / "test/tests.apk")):
        output = device("install", "-r", apk)
        if "Success" not in output:
            raise RuntimeError(output)
    original = {}
    for setting in ("size", "density"):
        match = re.search(r"Override \w+: (\S+)", device("shell", "wm", setting))
        original[setting] = match.group(1) if match else "reset"
    summary = []

    def suite(name, label=None):
        label = label or name
        # Fresh state and a fresh fixture avoid test ordering or one-shot-error dependence.
        device("shell", "am", "force-stop", PACKAGE)
        device("shell", "pm", "clear", PACKAGE)
        device("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        output = device("shell", "am", "instrument", "-w", PACKAGE + ".test/" + PACKAGE + "." + name)
        (results / (label + ".log")).write_text(output)
        count = result_count(output)
        if name == "NativeTest":
            subprocess.run(["node", str(ROOT / "android/test/verify-xeapi.cjs"),
                            str(results / (label + ".log"))], check=True)
        summary.append({"suite": label, "checks": count})
        print(f"PASS {label}: {count} checks", flush=True)

    try:
        device("shell", "wm", "size", "1024x600")
        device("shell", "wm", "density", "160")
        for name in (args.suite or OFFLINE) + (LIVE if args.include_live else []):
            if name in FIXTURE:
                with fixture(results / (name + "-fixture.log")):
                    suite(name)
            else:
                suite(name)
        if not args.suite or "OverlayTextTest" in args.suite:
            device("shell", "wm", "size", "1024x280")
            with fixture(results / "OverlayText-short-fixture.log"):
                suite("OverlayTextTest", "OverlayText-short")
    except Exception:
        (results / "failure-logcat.txt").write_text(device("logcat", "-d", "-t", "500", check=False))
        raise
    finally:
        (results / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        for setting, value in original.items():
            device("shell", "wm", setting, value, check=False)
        device("shell", "am", "force-stop", PACKAGE)
    print(f"All {len(summary)} suites passed ({sum(item['checks'] for item in summary)} checks)")


if __name__ == "__main__":
    main()

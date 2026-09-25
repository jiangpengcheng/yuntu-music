#!/usr/bin/env python3
"""Small, pinned API-19 build. Requires Python 3.10+, JDK 17 and Android build tools."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
BUILD = ANDROID / "build"
NS = "{http://schemas.android.com/apk/res/android}"
DEPENDENCIES = {
    "bcprov-jdk15to18-1.83.jar": "org/bouncycastle/bcprov-jdk15to18/1.83/bcprov-jdk15to18-1.83.jar",
    "qrcodegen-1.8.0.jar": "io/nayuki/qrcodegen/1.8.0/qrcodegen-1.8.0.jar",
}


def version(manifest=ANDROID / "AndroidManifest.xml"):
    node = ET.parse(manifest).getroot()
    name, code = node.attrib[NS + "versionName"], int(node.attrib[NS + "versionCode"])
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", name) or code < 1:
        raise ValueError("Invalid Android version")
    return name, code


def verify_file(path, expected, algorithm="sha256"):
    actual = hashlib.new(algorithm, Path(path).read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"Checksum mismatch: {Path(path).name}")


def download(url, target, checksum, algorithm="sha256"):
    target = Path(target)
    if target.exists():
        verify_file(target, checksum, algorithm)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        verify_file(temporary, checksum, algorithm)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def dependencies():
    for line in (ANDROID / "libs/SHA256SUMS").read_text().splitlines():
        checksum, name = line.split()
        download("https://repo.maven.apache.org/maven2/" + DEPENDENCIES[name],
                 ANDROID / "libs" / name, checksum)
    return sorted((ANDROID / "libs").glob("*.jar"))


def sdk_root():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    return Path(configured) if configured else Path.home() / "Library/Android/sdk"


def platform(sdk):
    explicit = os.environ.get("ANDROID_19_JAR")
    if explicit:
        result = Path(explicit).resolve()
        if not result.is_file():
            raise FileNotFoundError("ANDROID_19_JAR does not exist")
        return result
    installed = sdk / "platforms/android-19/android.jar"
    if installed.exists():
        return installed
    archive = ROOT / ".cache/android-19_r04.zip"
    # Google legacy SDK archive checksum. Never compile against a newer SDK as a fallback.
    download("https://dl.google.com/android/repository/android-19_r04.zip", archive,
             "5efc3a3a682c1d49128daddb6716c433edf16e63349f32959b6207524ac04039")
    result = ROOT / ".cache/android-19/android.jar"
    result.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as contents:
        result.write_bytes(contents.read("android-4.4.2/android.jar"))
    return result


def java(name):
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / name) if home else name


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def signing(release):
    if release:
        key = Path(os.environ.get("YUNTU_KEYSTORE_PATH", ANDROID / ".signing/yuntu.jks"))
        password = Path(os.environ.get("YUNTU_KEYSTORE_PASSWORD_FILE", ANDROID / ".signing/password.txt"))
        alias = os.environ.get("YUNTU_KEY_ALIAS", "yuntu")
        if not key.is_file() or not password.is_file():
            raise FileNotFoundError("Release signing material is required; no debug-key fallback")
        return key, password, alias
    key, password = BUILD / "debug.jks", BUILD / "debug-password.txt"
    password.parent.mkdir(parents=True, exist_ok=True)
    password.write_text("android")  # Public debug-only password, never used for releases.
    password.chmod(0o600)
    if not key.exists():
        run(java("keytool"), "-genkeypair", "-keystore", key, "-storetype", "JKS",
            "-alias", "androiddebugkey", "-storepass:file", password, "-keypass:file", password,
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug")
    return key, password, "androiddebugkey"


def clean_dirs(base):
    for name in ("gen", "classes", "dex"):
        shutil.rmtree(base / name, ignore_errors=True)
        (base / name).mkdir(parents=True)


def class_archive(source, target):
    with zipfile.ZipFile(target, "w") as output:
        for path in sorted(source.rglob("*.class")):
            output.write(path, path.relative_to(source))


def pack(base, tools, sign, output):
    shutil.copyfile(base / "resources.apk", base / "unsigned.apk")
    with zipfile.ZipFile(base / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
        archive.write(base / "dex/classes.dex", "classes.dex")
    run(tools / "zipalign", "-f", "4", base / "unsigned.apk", base / "aligned.apk")
    key, password, alias = sign
    run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", alias,
        "--ks-pass", "file:" + str(password), "--min-sdk-version", "19",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "false", "--v4-signing-enabled", "false",
        "--out", output, base / "aligned.apk")
    run(tools / "apksigner", "verify", "--min-sdk-version", "19", "--verbose", output)
    run(tools / "zipalign", "-c", "4", output)
    with zipfile.ZipFile(output) as archive:
        if archive.read("classes.dex")[:8] != b"dex\n035\0":
            raise ValueError("Expected API-19 compatible DEX 035")


def build(release=False, tests=False):
    name, _ = version()
    tools = sdk_root() / "build-tools" / os.environ.get("ANDROID_BUILD_TOOLS", "33.0.2")
    sdk = platform(sdk_root())
    jars = dependencies()
    BUILD.mkdir(parents=True, exist_ok=True)
    sign = signing(release)
    clean_dirs(BUILD)
    run(tools / "aapt", "package", "-f", "-m", "-J", BUILD / "gen", "-M",
        ANDROID / "AndroidManifest.xml", "-S", ANDROID / "res", "-I", sdk, "-F", BUILD / "resources.apk")
    run(java("javac"), "-source", "7", "-target", "7", "-bootclasspath", sdk, "-encoding", "UTF-8",
        "-cp", os.pathsep.join(map(str, jars)), "-d", BUILD / "classes",
        *sorted((ANDROID / "src").rglob("*.java")), *sorted((BUILD / "gen").rglob("*.java")))
    class_archive(BUILD / "classes", BUILD / "classes.jar")
    run(java("java"), "-cp", tools / "lib/d8.jar", "com.android.tools.r8.R8", "--release",
        "--min-api", "19", "--lib", sdk, "--pg-conf", ANDROID / "proguard.pro",
        "--output", BUILD / "dex", BUILD / "classes.jar", *jars)
    output = BUILD / f"yuntu-music-{name}{'' if release else '-debug'}.apk"
    pack(BUILD, tools, sign, output)
    if tests:
        build_tests(tools, sdk, sign)
    metadata = {"version": name, "apk": str(output), "release": release}
    import json
    (BUILD / "build-info.json").write_text(json.dumps(metadata))
    print(f"APK: {output}\nSHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")
    return output


def build_tests(tools, sdk, sign):
    base = BUILD / "test"
    clean_dirs(base)
    run(tools / "aapt", "package", "-f", "-m", "-J", base / "gen", "-M",
        ANDROID / "test/AndroidManifest.xml", "-I", sdk, "-F", base / "resources.apk")
    run(java("javac"), "-source", "7", "-target", "7", "-bootclasspath", sdk, "-encoding", "UTF-8",
        "-cp", BUILD / "classes", "-d", base / "classes", *sorted((ANDROID / "test").rglob("*.java")))
    class_archive(base / "classes", base / "classes.jar")
    run(tools / "d8", "--min-api", "19", "--lib", sdk, "--classpath", BUILD / "classes.jar",
        "--output", base / "dex", base / "classes.jar")
    pack(base, tools, sign, base / "tests.apk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", action="store_true", help="Require the stable release signing key")
    parser.add_argument("--tests", action="store_true", help="Also build offline instrumentation")
    args = parser.parse_args()
    build(args.release, args.tests)


if __name__ == "__main__":
    main()

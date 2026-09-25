"""Build/release boundary regressions; no Android SDK, network or signing secrets needed."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import build_android as build
from prepare_release import validate_tag
from run_android_tests import result_count


class BuildChecks(unittest.TestCase):
    def test_current_manifest_and_release_tag(self):
        name, code = build.version()
        self.assertGreaterEqual(code, 5)
        validate_tag("v" + name, name)

    def test_mismatched_release_tag_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_tag("v9.9.9", "1.0.0")

    def test_modified_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.jar"
            path.write_bytes(b"original")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            build.verify_file(path, digest)
            path.write_bytes(b"modified")
            with self.assertRaises(ValueError):
                build.verify_file(path, digest)

    def test_release_never_falls_back_to_debug_key(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict("os.environ", {
            "YUNTU_KEYSTORE_PATH": str(Path(tmp) / "missing.jks"),
            "YUNTU_KEYSTORE_PASSWORD_FILE": str(Path(tmp) / "missing.txt"),
        }):
            with self.assertRaises(FileNotFoundError):
                build.signing(True)

    def test_instrumentation_positive_formats(self):
        self.assertEqual(result_count("\nPASS 27 large card checks\n"), 27)
        self.assertEqual(result_count("INSTRUMENTATION_RESULT: count=41\nINSTRUMENTATION_CODE: -1"), 41)

    def test_instrumentation_cannot_pass_on_adb_exit_zero(self):
        for text in ("", "INSTRUMENTATION_CODE: -1", "PASS 0 checks", "FAIL after 2",
                     "INSTRUMENTATION_RESULT: count=1\nINSTRUMENTATION_CODE: 0",
                     "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                     "INSTRUMENTATION_RESULT: failure=exception\nPASS 1 checks"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                result_count(text)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Compatibility entry point; see scripts/build_android.py --help."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parents[1] / "scripts/build_android.py"), run_name="__main__")

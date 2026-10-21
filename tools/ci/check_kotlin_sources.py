#!/usr/bin/env python3
"""TongCut Kotlin source integrity guard.

Detects accidental tool-output preambles and unresolved Git merge conflicts
before Gradle. Does not modify source, download dependencies, or inspect media.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app" / "src"

# A narrow set of known invalid tool-generated headers; avoid rejecting
# legitimate Kotlin strings containing ordinary warnings.
INJECTED_TEXT = re.compile(
    r"^(?:Warning: (?:fs|s) was declared with const; "
    r"use let for reassignable variables\.|e: file:///)"
)
CONFLICT_MARKER = re.compile(r"^(?:<{7} |={7}$|>{7} )")


def scan_file(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        data = path.read_text(encoding="utf-8")
    except (UnicodeError, OSError) as exc:
        return [f"{path}: unreadable UTF-8 source: {exc}"]
    if not data.strip():
        return [f"{path}: empty Kotlin file"]
    if data.startswith("\ufeff"):
        return [f"{path}: unexpected UTF-8 BOM"]
    lines = data.splitlines()
    first = next((line.strip() for line in lines if line.strip()), "")
    if not (
        first.startswith("package ")
        or first.startswith("@file:")
        or first.startswith("//")
        or first.startswith("/*")
        or first.startswith("*")
    ):
        errors.append(f"{path}: suspicious Kotlin source preamble: {first[:90]}")
    for number, line in enumerate(lines, 1):
        if INJECTED_TEXT.match(line) or CONFLICT_MARKER.match(line):
            errors.append(f"{path}:{number}: non-Kotlin tool output or merge marker")
    return errors


def main() -> int:
    if not SOURCE.is_dir():
        print("Missing Android Kotlin source tree", file=sys.stderr)
        return 2
    files = sorted(SOURCE.rglob("*.kt"))
    if not files:
        print("No Kotlin source files found", file=sys.stderr)
        return 2
    errors = [error for path in files for error in scan_file(path)]
    print(f"TongCut Kotlin source guard: scanned {len(files)} files")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Source integrity guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
import argparse
import hashlib
import os
import subprocess
import tempfile
import urllib.request
from pathlib import Path

VERSION = "1.37.0"
SHA256 = "834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a"
ARTIFACT = f"google-java-format-{VERSION}-all-deps.jar"
URL = f"https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/{VERSION}/{ARTIFACT}"
ROOT = Path(__file__).resolve().parent.parent
SOURCES = ('src',)


def main():
    parser = argparse.ArgumentParser(description="Format the active mod's Java sources")
    parser.add_argument("--check", action="store_true", help="Exit nonzero if sources need formatting")
    parser.add_argument("--jar", type=Path, help="Use a previously downloaded, checksum-verified formatter")
    args = parser.parse_args()
    paths = sorted(path.relative_to(ROOT).as_posix()
                   for source in SOURCES for path in (ROOT / source).rglob("*.java"))
    if not paths:
        parser.error("No Java sources found")
    with tempfile.TemporaryDirectory() as work:
        jar = args.jar.resolve() if args.jar else Path(work) / ARTIFACT
        if not args.jar:
            with urllib.request.urlopen(URL, timeout=30) as response:
                jar.write_bytes(response.read())
        if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
            parser.error("Formatter SHA-256 differs")
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
        files = Path(work) / "sources.txt"
        files.write_text("\n".join(paths) + "\n", encoding="utf-8")
        mode = ["--dry-run", "--set-exit-if-changed"] if args.check else ["--replace"]
        return subprocess.run(
            [java, "-jar", str(jar), "--aosp", "--skip-sorting-imports",
             "--skip-removing-unused-imports", "--skip-reflowing-long-strings",
             "--skip-javadoc-formatting", "--skip-reordering-modifiers", *mode, f"@{files}"],
            cwd=ROOT,
            check=False,
        ).returncode


if __name__ == "__main__":
    raise SystemExit(main())

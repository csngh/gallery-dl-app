"""Compile a v1 extractor package with the target app's CPython version."""
import argparse
import json
import platform
import py_compile
import sys
import tempfile
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--asset", action="append", type=Path, default=[])
    args = parser.parse_args()
    meta = json.loads(args.manifest.read_text(encoding="utf-8"))
    python = f"{sys.version_info.major}.{sys.version_info.minor}"
    if meta.get("python") != python:
        parser.error(f"Run the packer with Python {meta.get('python')}, not {python}.")
    if meta.get("platform") not in (f"macos-{platform.machine()}", "android", "ios"):
        parser.error("Use a supported target platform and architecture.")
    if args.output.suffix != ".gdlplugin":
        parser.error("The output must end in .gdlplugin.")
    with tempfile.TemporaryDirectory() as folder:
        bytecode = Path(folder) / "extractor.pyc"
        py_compile.compile(
            str(args.source), str(bytecode), dfile="extractor.py",
            doraise=True, invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH,
        )
        with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr("manifest.json", json.dumps(meta))
            package.write(bytecode, "extractor.pyc")
            for asset in args.asset:
                package.write(asset, f"assets/{asset.name}")


if __name__ == "__main__":
    main()

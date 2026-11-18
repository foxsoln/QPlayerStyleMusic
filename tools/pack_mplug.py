#!/usr/bin/env python3
"""Create a custom .mplug (ZIP): python pack_mplug.py sample-plugin demo.local.mplug"""
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


def build(src: Path, target: Path) -> None:
    script = (src / "main.js").read_bytes()
    manifest = json.loads((src / "plugin.json").read_text(encoding="utf-8"))
    if not re.fullmatch(r"[a-z][a-z0-9._-]{2,63}", manifest["id"]):
        raise ValueError("Invalid plugin id")
    if len(script) > 256 * 1024:
        raise ValueError("main.js > 256 KiB")
    manifest["mainSha256"] = hashlib.sha256(script).hexdigest()
    payload = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    if len(payload) > 32 * 1024:
        raise ValueError("plugin.json > 32 KiB")
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(target, "w", compression=ZIP_DEFLATED) as archive:
        archive.writestr("plugin.json", payload)
        archive.writestr("main.js", script)
    if target.stat().st_size > 512 * 1024:
        target.unlink()
        raise ValueError(".mplug > 512 KiB")
    print(f"Created {target} ({target.stat().st_size} bytes), id={manifest['id']}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python pack_mplug.py <plugin-directory> <output.mplug>")
    build(Path(sys.argv[1]), Path(sys.argv[2]))

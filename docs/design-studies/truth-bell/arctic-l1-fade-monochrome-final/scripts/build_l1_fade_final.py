"""Build the two final Composer packages from the editable sources (stdlib only)."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ("truth-bell-l1-dark-obsidian", "truth-bell-l1-light-porcelain")


def build(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for name in VARIANTS:
        source = ROOT / "sources" / name
        document = json.loads((source / "icon.json").read_text(encoding="utf-8"))
        for group in document["groups"]:
            for layer in group["layers"]:
                asset = layer.get("image-name")
                if asset:
                    assert (source / "Assets" / asset).is_file(), asset
        destination = output / (name + ".icon.zip")
        with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
            for file in sorted(source.rglob("*")):
                if not file.is_file():
                    continue
                entry = ZipInfo(f"Truth Bell - {name} (badge).icon/{file.relative_to(source).as_posix()}", (2026, 11, 14, 0, 0, 0))
                entry.compress_type = ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, file.read_bytes())
        print(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "packages")
    build(parser.parse_args().output)

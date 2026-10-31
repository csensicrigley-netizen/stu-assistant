"""Sync product versions; resolve independent capability-probe delivery versions."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
PROBE_PACKAGES = {
    "Liveprobe": "com.stuassistant.app.liveprobe",
    "Huaweiprobe": "com.stuassistant.app.huaweiprobe",
    "Vivoprobe": "com.stuassistant.app.vivoprobe",
    "Vivoidentity": "com.stuassistant.app.vivoprobe",
    "Vivomap": "com.autonavi.minimap",
}


def probe_catalog(root: Path) -> dict:
    data = json.loads((root / "deploy/probe-versions.json").read_text(encoding="utf-8-sig"))
    variants = data.get("variants", {})
    if data.get("schemaVersion") != 1 or not isinstance(variants, dict) or set(variants) != set(PROBE_PACKAGES):
        raise ValueError("Expected schemaVersion 1 and exactly the standalone probe variants")
    floors = data.get("lastDeliveredVersionCodes")
    if (not isinstance(floors, dict) or set(floors) != set(PROBE_PACKAGES.values()) | {"com.stuassistant.app.haptics"} or
            any(type(code) is not int or not 1 <= code <= 2_100_000_000 for code in floors.values())):
        raise ValueError("Expected the per-package historical delivery floors")
    for variant, entry in variants.items():
        code = entry.get("versionCode")
        suffix = "vivoidentity" if variant == "Vivomap" else variant.lower()
        if entry.get("applicationId") != PROBE_PACKAGES[variant]:
            raise ValueError(f"Unexpected package identity for {variant}")
        if type(code) is not int or not 1 <= code <= 2_100_000_000:
            raise ValueError(f"Invalid Android versionCode for {variant}")
        probe_name = entry.get("versionName")
        if not isinstance(probe_name, str) or not re.fullmatch(rf"[\d.]+-{suffix}\.\d+", probe_name):
            raise ValueError(f"Probe versionName must identify its test variant: {variant}")
    # These two APKs form one experiment and differ only in package identity.
    control, amap = variants["Vivoidentity"], variants["Vivomap"]
    if (control["versionName"], control["versionCode"]) != (amap["versionName"], amap["versionCode"]):
        raise ValueError("Vivo identity pair must share a probe version")
    return data


def probe_versions(root: Path) -> dict[str, dict]:
    return probe_catalog(root)["variants"]


def recorded_deliveries(root: Path) -> list[dict]:
    return [dict(package=package, versionCode=code, apk="probe-versions.json delivery ledger")
            for package, code in probe_catalog(root)["lastDeliveredVersionCodes"].items()]


def resolve_variant(root: Path, variant: str, product: tuple[str, int]) -> dict:
    probes = probe_versions(root)
    name, code = product
    product_version = {"versionName": name, "versionCode": code}
    if variant in probes:
        result = dict(probes[variant])
        result.update(channel="device-probe", isProductBuild=False, functionalUpgrade=False)
        file_name = result["versionName"]
        if variant in ("Vivoidentity", "Vivomap"):
            identity = "control" if variant == "Vivoidentity" else "amap"
            file_name = re.sub(r"-vivoidentity\.(\d+)$", rf"-vivoidentity-{identity}.\1", file_name)
    elif variant in ("Release", "Haptics"):
        suffix = "-native.1" if variant == "Haptics" else ""
        result = dict(versionName=name + suffix, versionCode=code,
                      applicationId="com.stuassistant.app" + (".haptics" if suffix else ""),
                      channel="verification" if suffix else "production", isProductBuild=True)
        file_name = result["versionName"]
    else:
        raise ValueError(f"Unknown delivery variant: {variant}")
    result.update(variant=variant, baseProductVersion=product_version,
                  artifactName=f"STU-assistant-{file_name}.apk")
    return result


def validate_upgrade(targets: list[dict], shipped: list[dict]) -> None:
    """Android's downgrade/duplicate floor applies only to the same package."""
    for target in targets:
        matching = [entry for entry in shipped if entry["package"] == target["applicationId"]]
        if not matching:
            continue
        highest = max(matching, key=lambda entry: int(entry["versionCode"]))
        if target["versionCode"] <= int(highest["versionCode"]):
            raise ValueError(
                f'{target["applicationId"]}: versionCode {target["versionCode"]} must exceed '
                f'{highest["versionCode"]} already delivered in {highest["apk"]}; '
                'increment this package\'s version only, not unrelated product/probe versions'
            )


def sync(root: Path, write: bool = False) -> tuple[str, int]:
    gradle = (root / "web/android/app/build.gradle").read_text(encoding="utf-8-sig")
    name_match = re.search(r'\bversionName\s+"([\d.]+)"', gradle)
    code_match = re.search(r'\bversionCode\s+(\d+)', gradle)
    if not name_match or not code_match:
        raise ValueError("Android versionName/versionCode is missing")
    name, code = name_match[1], int(code_match[1])
    if code < 1:
        raise ValueError("versionCode must be positive")
    target = root / "web/src/lib/version.ts"
    current = target.read_text(encoding="utf-8-sig")
    updated, names = re.subn(r'APP_VERSION_NAME\s*=\s*"[^"]+"', f'APP_VERSION_NAME = "{name}"', current)
    updated, codes = re.subn(r'APP_VERSION_CODE\s*=\s*\d+', f'APP_VERSION_CODE = {code}', updated)
    if names != 1 or codes != 1:
        raise ValueError("Expected exactly one web version constant pair")
    metadata = root / "backend/app/latest_version.json"
    data = json.loads(metadata.read_text(encoding="utf-8-sig"))
    mismatch = (updated != current or data.get("channel") != "product" or
                (data.get("versionName"), data.get("versionCode")) != (name, code))
    if mismatch and not write:
        raise ValueError("Version numbers differ; run python deploy/sync-version.py --write")
    if write and mismatch:
        target.write_text(updated, encoding="utf-8", newline="\n")
        data.update(versionName=name, versionCode=code, channel="product")
        metadata.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return name, code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Generate constants; otherwise check without editing")
    parser.add_argument("--variant", action="append", default=[], help="Resolve a delivery variant; repeat for a pair")
    parser.add_argument("--json", action="store_true", help="Print delivery metadata for packaging")
    parser.add_argument("--shipped-apks", type=Path, help="JSON array of actual APK package/version headers")
    args = parser.parse_args()
    if args.write and args.variant:
        parser.error("--write updates product metadata; resolve delivery variants without --write")
    name, code = sync(ROOT, args.write)
    probe_versions(ROOT)
    builds = [resolve_variant(ROOT, variant, (name, code)) for variant in args.variant]
    if args.shipped_apks:
        if not builds:
            parser.error("--shipped-apks requires --variant")
        validate_upgrade(builds, recorded_deliveries(ROOT) +
                         json.loads(args.shipped_apks.read_text(encoding="utf-8-sig")))
    if args.json:
        print(json.dumps({"productVersion": {"versionName": name, "versionCode": code}, "builds": builds}))
    else:
        print(f"Product version consistent: {name} ({code}); independent probe metadata valid")

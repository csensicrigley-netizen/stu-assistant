"""Prevent probe APKs from advancing product updates or unrelated package floors."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("version_policy", DEPLOY / "sync-version.py")
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class VersionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for folder in ("deploy", "web/android/app", "web/src/lib", "backend/app"):
            (self.root / folder).mkdir(parents=True)
        self.catalog = json.loads((DEPLOY / "probe-versions.json").read_text(encoding="utf-8"))
        self.write_catalog()
        (self.root / "web/android/app/build.gradle").write_text(
            'defaultConfig { versionCode 55; versionName "1.0.54" }', encoding="utf-8")
        (self.root / "web/src/lib/version.ts").write_text(
            'export const APP_VERSION_NAME = "1.0.54";\nexport const APP_VERSION_CODE = 55;\n', encoding="utf-8")
        self.metadata = dict(versionName="1.0.54", versionCode=55, channel="product", notes=["Product fix"])
        self.write_metadata()

    def write_catalog(self):
        (self.root / "deploy/probe-versions.json").write_text(json.dumps(self.catalog), encoding="utf-8")

    def write_metadata(self):
        (self.root / "backend/app/latest_version.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def resolve(self, variant):
        return POLICY.resolve_variant(self.root, variant, POLICY.sync(self.root))

    def test_probe_bump_cannot_change_web_or_product_update_version(self):
        before = {path: (self.root / path).read_bytes() for path in
                  ("web/src/lib/version.ts", "backend/app/latest_version.json")}
        self.catalog["variants"]["Huaweiprobe"].update(versionName="1.0.57-huaweiprobe.2", versionCode=100)
        self.write_catalog()
        self.assertEqual(POLICY.sync(self.root, write=True), ("1.0.54", 55))
        self.assertEqual(self.resolve("Huaweiprobe")["versionCode"], 100)
        for path, content in before.items():
            # JSON indentation may be normalized by sync, but its data is unchanged.
            actual = (self.root / path).read_bytes()
            if path.endswith("json"):
                self.assertEqual(json.loads(actual), json.loads(content))
            else:
                self.assertEqual(actual, content)

    def test_product_build_uses_product_version_not_highest_probe(self):
        build = self.resolve("Release")
        self.assertEqual((build["versionName"], build["versionCode"]), ("1.0.54", 55))
        self.assertTrue(build["isProductBuild"])
        self.assertEqual(self.resolve("Haptics")["channel"], "verification")

    def test_probe_receipt_carries_test_classification_and_product_base(self):
        build = self.resolve("Vivoidentity")
        self.assertEqual(build["channel"], "device-probe")
        self.assertFalse(build["functionalUpgrade"])
        self.assertFalse(build["isProductBuild"])
        self.assertEqual(build["baseProductVersion"], dict(versionName="1.0.54", versionCode=55))
        self.assertEqual(build["artifactName"], "STU-assistant-1.0.60-vivoidentity-control.1.apk")
        self.assertEqual(self.resolve("Vivomap")["artifactName"], "STU-assistant-1.0.60-vivoidentity-amap.1.apk")

    def test_unrelated_probe_does_not_raise_product_floor(self):
        POLICY.validate_upgrade([self.resolve("Release")], [
            dict(package="com.stuassistant.app.vivoprobe", versionCode=1000, apk="probe.apk")])

    def test_same_package_equal_or_lower_version_is_blocked(self):
        target = self.resolve("Vivoidentity")
        for code in (61, 62):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "must exceed"):
                POLICY.validate_upgrade([target], [
                    dict(package=target["applicationId"], versionCode=code, apk="existing.apk")])

    def test_same_package_greater_version_is_allowed(self):
        target = self.resolve("Vivoidentity")
        POLICY.validate_upgrade([target], [dict(package=target["applicationId"], versionCode=60, apk="old.apk")])

    def test_fresh_checkout_still_remembers_delivered_probe_floor(self):
        recorded = POLICY.recorded_deliveries(self.root)
        with self.assertRaisesRegex(ValueError, "must exceed 61"):
            POLICY.validate_upgrade([self.resolve("Vivoprobe")], recorded)
        POLICY.validate_upgrade([self.resolve("Release")], recorded)
        future = self.resolve("Vivoidentity") | {"versionCode": 62}
        POLICY.validate_upgrade([future], recorded)
        with self.assertRaisesRegex(ValueError, "must exceed 55"):
            POLICY.validate_upgrade([self.resolve("Haptics")], recorded)

    def test_malformed_recorded_floor_fails_closed(self):
        original = deepcopy(self.catalog)
        for value in (None, {}, {"com.stuassistant.app.haptics": True}):
            with self.subTest(value=value):
                self.catalog = deepcopy(original)
                self.catalog["lastDeliveredVersionCodes"] = value
                self.write_catalog()
                with self.assertRaisesRegex(ValueError, "historical delivery floors"):
                    POLICY.recorded_deliveries(self.root)

    def test_vivo_modes_share_upgrade_floor(self):
        target = self.resolve("Vivoprobe")
        delivered_identity = self.resolve("Vivoidentity")
        with self.assertRaisesRegex(ValueError, "must exceed 61"):
            POLICY.validate_upgrade([target], [dict(package=delivered_identity["applicationId"],
                                                   versionCode=61, apk="identity-control.apk")])

    def test_missing_variant_cannot_fall_back_to_product_version(self):
        del self.catalog["variants"]["Liveprobe"]
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, "exactly"):
            self.resolve("Huaweiprobe")

    def test_probe_must_have_explicit_test_name_and_known_identity(self):
        original = deepcopy(self.catalog)
        for mutation in (dict(versionName="1.0.99"), dict(applicationId="com.stuassistant.app"),
                         dict(versionCode=True), dict(versionCode=0)):
            with self.subTest(mutation=mutation):
                self.catalog = deepcopy(original)
                self.catalog["variants"]["Liveprobe"].update(mutation)
                self.write_catalog()
                with self.assertRaises(ValueError):
                    self.resolve("Liveprobe")

    def test_identity_pair_cannot_diverge(self):
        self.catalog["variants"]["Vivomap"]["versionCode"] += 1
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, "pair"):
            self.resolve("Vivoidentity")

    def test_probe_metadata_cannot_become_product_update_feed(self):
        self.metadata["channel"] = "device-probe"
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, "Version numbers differ"):
            POLICY.sync(self.root)

    def test_unknown_delivery_variant_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Unknown delivery"):
            self.resolve("Vivoidentty")


if __name__ == "__main__":
    unittest.main()

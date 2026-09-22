import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).with_name("generate-overlays.py")
SPEC = importlib.util.spec_from_file_location("generate_overlays", SCRIPT)
generate_overlays = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(generate_overlays)


class GenerateOverlaysTest(unittest.TestCase):
    def load_fixture(self, payload):
        with tempfile.TemporaryDirectory() as tmp:
            patches = pathlib.Path(tmp) / "patches.json"
            patches.write_text(json.dumps(payload))
            with mock.patch.object(generate_overlays, "PATCHES", patches):
                return generate_overlays.load()

    def test_domain_override_wins_case_and_data_prefix_aliases_in_dead_list(self):
        domain, title, native, dead = self.load_fixture(
            {
                "domainOverrides": {"ASTRASCANS": "astracomic.example"},
                "titleOverrides": {},
                "nativeBacked": [],
                "deadSources": ["astrascans", "DD_ASTRASCANS", "unrelated"],
            }
        )

        self.assertEqual({"ASTRASCANS": "astracomic.example"}, domain)
        self.assertEqual(["unrelated"], dead)

    def test_rejects_reason_for_a_source_that_is_not_dead(self):
        with self.assertRaisesRegex(ValueError, "brokenReasons.*live-source"):
            self.load_fixture(
                {
                    "domainOverrides": {},
                    "titleOverrides": {},
                    "nativeBacked": [],
                    "deadSources": [],
                    "brokenReasons": {"live-source": "not actually disabled"},
                }
            )

    def test_nested_data_checkout_updates_local_shared_and_sibling_android(self):
        with tempfile.TemporaryDirectory() as tmp:
            workspace = pathlib.Path(tmp) / "Nyora"
            data_root = workspace / "nyora-shared-datadriven" / "data"
            shared_target = (
                data_root.parent
                / "src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt"
            )
            android_target = (
                workspace
                / "nyora-android/app/src/main/kotlin/com/nyora/hasan72341/core/SourcePatches.kt"
            )
            for target in (shared_target, android_target):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("stale\n")

            data_root.mkdir(parents=True, exist_ok=True)
            patches = data_root / "patches.json"
            patches.write_text(
                json.dumps(
                    {
                        "domainOverrides": {"EXAMPLE": "example.test"},
                        "titleOverrides": {},
                        "nativeBacked": [],
                        "deadSources": [],
                    }
                )
            )
            blocklist = data_root / "blocked-sources.json"

            with (
                mock.patch.object(generate_overlays, "ROOT", data_root),
                mock.patch.object(generate_overlays, "WORKSPACE", data_root.parent),
                mock.patch.object(generate_overlays, "PATCHES", patches),
                mock.patch.object(generate_overlays, "BLOCKLIST_JSON", blocklist),
                mock.patch.object(generate_overlays.sys, "argv", [str(SCRIPT)]),
            ):
                generate_overlays.main()

            self.assertIn('"EXAMPLE" to "example.test"', shared_target.read_text())
            self.assertIn('"EXAMPLE" to "example.test"', android_target.read_text())

    def test_standalone_data_checkout_updates_nyora_workspace_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkout_root = pathlib.Path(tmp)
            data_root = checkout_root / "nyora-data-driven"
            shared_target = (
                checkout_root
                / "Nyora/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt"
            )
            android_target = (
                checkout_root
                / "Nyora/nyora-android/app/src/main/kotlin/com/nyora/hasan72341/core/SourcePatches.kt"
            )
            for target in (shared_target, android_target):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("stale\n")

            data_root.mkdir(parents=True, exist_ok=True)
            patches = data_root / "patches.json"
            patches.write_text(
                json.dumps(
                    {
                        "domainOverrides": {"EXAMPLE": "example.test"},
                        "titleOverrides": {},
                        "nativeBacked": [],
                        "deadSources": [],
                    }
                )
            )
            blocklist = data_root / "blocked-sources.json"

            with (
                mock.patch.object(generate_overlays, "ROOT", data_root),
                mock.patch.object(generate_overlays, "WORKSPACE", checkout_root),
                mock.patch.object(generate_overlays, "PATCHES", patches),
                mock.patch.object(generate_overlays, "BLOCKLIST_JSON", blocklist),
                mock.patch.object(generate_overlays.sys, "argv", [str(SCRIPT)]),
            ):
                generate_overlays.main()

            self.assertIn('"EXAMPLE" to "example.test"', shared_target.read_text())
            self.assertIn('"EXAMPLE" to "example.test"', android_target.read_text())


if __name__ == "__main__":
    unittest.main()

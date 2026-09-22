import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).with_name("build-catalogue.py")
SPEC = importlib.util.spec_from_file_location("build_catalogue", SCRIPT)
build_catalogue = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(build_catalogue)


class BuildCatalogueTest(unittest.TestCase):
    def build_fixture(self, rows, *, patches=None, liveness=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            repo = root / "repo"
            repo.mkdir()
            (repo / "madara.json").write_text(json.dumps({"sources": rows}))
            (root / "patches.json").write_text(
                json.dumps(
                    patches
                    or {
                        "domainOverrides": {},
                        "titleOverrides": {},
                        "deadSources": [],
                        "brokenReasons": {},
                    }
                )
            )
            (root / "liveness.json").write_text(
                json.dumps(liveness or {"deadIds": []})
            )
            with (
                mock.patch.object(build_catalogue, "ROOT", root),
                mock.patch.object(build_catalogue, "REPO", repo),
                mock.patch.object(build_catalogue, "OUT", root / "catalogue.json"),
            ):
                return build_catalogue.build()

    def test_preserves_platform_gating_and_normalizes_invalid_defaults(self):
        payload = self.build_fixture(
            [
                {
                    "id": "UPPER_ID",
                    "name": "Upper",
                    "lang": "",
                    "nsfw": False,
                    "contentType": None,
                    "engine": "madara",
                    "domain": "upper.example",
                    "altDomains": ["mirror.example"],
                    "pageSize": 0,
                    "config": {},
                    "antiBot": "cloudflare",
                    "cfWall": "B",
                    "needsCustomLogic": True,
                }
            ]
        )

        row = payload["sources"][0]
        self.assertEqual("und", row["lang"])
        self.assertNotIn("contentType", row)
        self.assertNotIn("pageSize", row)
        self.assertEqual(["mirror.example"], row["altDomains"])
        self.assertEqual("cloudflare", row["antiBot"])
        self.assertEqual("B", row["cfWall"])
        self.assertIs(True, row["needsCustomLogic"])

    def test_patch_ids_are_case_insensitive_and_dead_sources_are_hidden(self):
        payload = self.build_fixture(
            [
                {
                    "id": "astrascans",
                    "name": "Old Astra",
                    "lang": "en",
                    "engine": "madara",
                    "domain": "old.example",
                    "broken": True,
                    "brokenReason": "old domain",
                    "config": {},
                },
                {
                    "id": "deadrow",
                    "name": "Dead",
                    "lang": "en",
                    "engine": "madara",
                    "domain": "dead.example",
                    "broken": False,
                    "config": {},
                },
                {
                    "id": "extra",
                    "name": "Extra",
                    "lang": "en",
                    "engine": "madara",
                    "domain": "extra.example",
                    "broken": False,
                    "config": {},
                },
            ],
            patches={
                "domainOverrides": {"ASTRASCANS": "astracomic.example"},
                "titleOverrides": {"dd_ASTRAscans": "Astra Comics"},
                "deadSources": ["DEADROW", "DD_extra", "ASTRASCANS"],
                "brokenReasons": {
                    "dd_deadrow": "requires an unsupported signed API",
                },
            },
        )

        rows = {row["id"]: row for row in payload["sources"]}
        self.assertEqual("astracomic.example", rows["astrascans"]["domain"])
        self.assertEqual("Astra Comics", rows["astrascans"]["name"])
        self.assertIs(False, rows["astrascans"]["broken"])
        self.assertNotIn("brokenReason", rows["astrascans"])
        self.assertIs(True, rows["deadrow"]["broken"])
        self.assertEqual(
            "requires an unsupported signed API",
            rows["deadrow"]["brokenReason"],
        )
        self.assertIs(True, rows["extra"]["broken"])
        self.assertEqual(
            "disabled by canonical source health patch",
            rows["extra"]["brokenReason"],
        )
        self.assertEqual(1, payload["liveCount"])

    def test_normalizes_supported_complex_metadata_into_engine_config(self):
        payload = self.build_fixture(
            [
                {
                    "id": "complex",
                    "name": "Complex",
                    "lang": "fr",
                    "engine": "madara",
                    "domain": "complex.example",
                    "config": {},
                    "configComplex": {
                        "sourceLocale": "Locale.ENGLISH",
                        "availableSortOrders": "EnumSet.of(SortOrder.RELEVANCE, SortOrder.UPDATED)",
                        "filterCapabilities": "MangaListFilterCapabilities("
                        "isSearchSupported = true, isSearchWithFiltersSupported = true, "
                        "isMultipleTagsSupported = false, isTagsExclusionSupported = false, "
                        "isYearSupported = true, isAuthorSearchSupported = true)",
                    },
                }
            ]
        )

        config = payload["sources"][0]["config"]
        self.assertEqual("en", config["locale"])
        self.assertEqual(["RELEVANCE", "UPDATED"], config["sortOrders"])
        self.assertEqual(
            {
                "search": True,
                "searchWithFilters": True,
                "multipleTags": False,
                "tagsExclusion": False,
                "year": True,
                "authorSearch": True,
            },
            config["capabilities"],
        )

    def test_normalizes_legacy_madara_selectors_into_typed_selector_block(self):
        payload = self.build_fixture(
            [
                {
                    "id": "legacy-selectors",
                    "name": "Legacy selectors",
                    "lang": "en",
                    "engine": "madara",
                    "domain": "selectors.example",
                    "config": {
                        "selectChapter": "li.chapter",
                        "selectPage": "div.reader-page",
                        "selectBodyPage": "main.reader",
                        "selectDesc": ".summary",
                        "selectGenre": ".genres a",
                        "selectDate": "time",
                        "selectState": ".status",
                        "selectAlt": ".alternative",
                        "selectTestAsync": ".chapters-loaded",
                    },
                }
            ]
        )

        config = payload["sources"][0]["config"]
        self.assertEqual(
            {
                "chapter": "li.chapter",
                "page": "div.reader-page",
                "bodyPage": "main.reader",
                "desc": ".summary",
                "genre": ".genres a",
                "date": "time",
                "state": ".status",
                "alt": ".alternative",
                "testAsync": ".chapters-loaded",
            },
            config["selectors"],
        )
        for legacy_key in (
            "selectChapter",
            "selectPage",
            "selectBodyPage",
            "selectDesc",
            "selectGenre",
            "selectDate",
            "selectState",
            "selectAlt",
            "selectTestAsync",
        ):
            self.assertNotIn(legacy_key, config)

    def test_production_umimanga_static_demo_is_not_advertised_as_live(self):
        payload = build_catalogue.build()

        row = next(row for row in payload["sources"] if row["id"] == "umimanga")
        self.assertIs(True, row["broken"])
        self.assertEqual(
            "upstream is a nonfunctional static recovery demo with no usable pagination or chapters",
            row["brokenReason"],
        )


if __name__ == "__main__":
    unittest.main()

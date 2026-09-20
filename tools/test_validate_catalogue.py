import importlib.util
import json
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).with_name("validate-catalogue.py")
SPEC = importlib.util.spec_from_file_location("validate_catalogue", SCRIPT)
validate_catalogue = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validate_catalogue)


class ValidateCatalogueTest(unittest.TestCase):
    def test_reports_source_id_and_path_for_invalid_row(self):
        schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["id", "pageSize"],
            "properties": {
                "id": {"type": "string"},
                "pageSize": {"type": "integer", "minimum": 1},
            },
        }

        errors = validate_catalogue.validation_errors(
            schema,
            [{"id": "broken-source", "pageSize": 0}],
        )

        self.assertEqual(1, len(errors))
        self.assertEqual("broken-source", errors[0].source_id)
        self.assertEqual("pageSize", errors[0].path)
        self.assertIn("minimum of 1", errors[0].message)

    def test_accepts_valid_rows(self):
        schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["id"],
            "properties": {"id": {"type": "string"}},
        }

        self.assertEqual([], validate_catalogue.validation_errors(schema, [{"id": "OK"}]))

    def test_accepts_mangareader_path_templates_and_browse_search_mode(self):
        schema_path = SCRIPT.parent.parent / "schema" / "SourceDef.schema.json"
        schema = json.loads(schema_path.read_text())
        row = {
            "id": "path-pagination",
            "name": "Path Pagination",
            "lang": "en",
            "engine": "mangareader",
            "domain": "reader.example",
            "config": {
                "listUrl": "/manga",
                "listPage": {
                    "page": {
                        "mode": "PATH",
                        "firstPathTemplate": "{listUrl}/",
                        "pathTemplate": "{listUrl}/page/{page}/",
                        "omitFirst": True,
                    },
                    "search": {"mode": "BROWSE", "param": "title"},
                },
            },
        }

        self.assertEqual([], validate_catalogue.validation_errors(schema, [row]))

    def test_accepts_mangareader_declarative_paged_http_request(self):
        schema_path = SCRIPT.parent.parent / "schema" / "SourceDef.schema.json"
        schema = json.loads(schema_path.read_text())
        row = {
            "id": "ajax-pagination",
            "name": "AJAX Pagination",
            "lang": "en",
            "engine": "mangareader",
            "domain": "reader.example",
            "config": {
                "listUrl": "/browse-manga",
                "listPage": {
                    "pagedRequest": {
                        "fromPage": 2,
                        "url": "/wp-admin/admin-ajax.php",
                        "method": "POST",
                        "browseOnly": True,
                        "headers": {
                            "X-Requested-With": "XMLHttpRequest",
                            "Referer": "https://{domain}{listUrl}/?order={order}",
                        },
                        "form": {
                            "action": "load_more",
                            "page": "{page}",
                            "genre": "{genre}",
                        },
                    },
                },
            },
        }

        self.assertEqual([], validate_catalogue.validation_errors(schema, [row]))


if __name__ == "__main__":
    unittest.main()

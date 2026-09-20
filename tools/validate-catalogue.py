#!/usr/bin/env python3
"""Validate generated catalogue rows against SourceDef.schema.json."""

import json
import pathlib
import sys
from typing import NamedTuple

from jsonschema import Draft202012Validator


ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "schema" / "SourceDef.schema.json"
CATALOGUE = ROOT / "catalogue.json"


class CatalogueValidationError(NamedTuple):
    source_id: str
    path: str
    message: str


def validation_errors(schema, rows):
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    result = []
    for row in rows:
        source_id = str(row.get("id", "<missing-id>"))
        for error in sorted(validator.iter_errors(row), key=lambda item: list(item.path)):
            path = ".".join(str(part) for part in error.path) or "<row>"
            result.append(CatalogueValidationError(source_id, path, error.message))
    return result


def main():
    schema = json.loads(SCHEMA.read_text())
    catalogue = json.loads(CATALOGUE.read_text())
    errors = validation_errors(schema, catalogue.get("sources", []))
    if errors:
        for error in errors:
            print(f"{error.source_id}:{error.path}: {error.message}", file=sys.stderr)
        raise SystemExit(
            f"catalogue schema validation failed: {len(errors)} errors across "
            f"{len({error.source_id for error in errors})} sources"
        )
    print(f"catalogue schema valid ({len(catalogue.get('sources', []))} sources)")


if __name__ == "__main__":
    main()

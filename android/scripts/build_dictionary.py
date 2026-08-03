#!/usr/bin/env python3
"""Convert Mandopop's pre-indexed cedict.json into an indexed SQLite asset."""

from __future__ import annotations

import json
import hashlib
import sqlite3
import sys
from pathlib import Path

# Bump when the schema changes; DictionaryRepository.EXPECTED_USER_VERSION must match.
SCHEMA_VERSION = 2

# Must match FORMAT_VERSION in scripts/preprocess_cedict.js.
FORMAT_VERSION = 2


def build_dictionary(input_path: Path, output_path: Path) -> None:
    source_sha256 = file_sha256(input_path)
    with input_path.open("r", encoding="utf-8") as source:
        dictionary = json.load(source)

    # Fail loudly on an old-format artifact rather than producing a silently empty database.
    version = dictionary.get("v") if isinstance(dictionary, dict) else None
    if version != FORMAT_VERSION:
        raise ValueError(
            f"cedict.json is format v{version!r}, expected v{FORMAT_VERSION}. "
            "Run `npm run dict:build` to regenerate it."
        )
    for required in ("entries", "index"):
        if not isinstance(dictionary.get(required), (list, dict)):
            raise ValueError(f"cedict.json is missing its {required!r} section")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = output_path.with_suffix(".db.tmp")
    if temp_path.exists():
        temp_path.unlink()

    connection = sqlite3.connect(temp_path)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=MEMORY")
        create_schema(connection)
        entry_count, lookup_count = insert_rows(connection, dictionary, source_sha256)
        connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        validate_database(connection, entry_count, lookup_count)
        validate_lookup_content(connection, dictionary)
        connection.execute("VACUUM")
    finally:
        connection.close()

    temp_path.replace(output_path)
    output_path.with_suffix(".sha256").write_text(file_sha256(output_path), encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        CREATE TABLE entries (
            id INTEGER PRIMARY KEY,
            simplified TEXT NOT NULL,
            pinyin TEXT NOT NULL,
            definitions TEXT NOT NULL
        );

        CREATE TABLE lookup_keys (
            key TEXT NOT NULL,
            rank INTEGER NOT NULL,
            entry_id INTEGER NOT NULL,
            CHECK(rank >= 0),
            FOREIGN KEY(entry_id) REFERENCES entries(id),
            PRIMARY KEY(key, rank)
        ) WITHOUT ROWID;

        CREATE TABLE metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        -- The extension only ever looks up English -> Chinese, which lookup_keys covers. Going the
        -- other way (Traverse gives us hanzi, we need the English) would otherwise full-scan every
        -- entry, once per card.
        CREATE INDEX entries_simplified ON entries(simplified);
        """
    )


def insert_rows(
    connection: sqlite3.Connection,
    dictionary: dict,
    source_sha256: str,
) -> tuple[int, int]:
    """Load the normalized artifact.

    Entries arrive already deduplicated and identified by array position, so this is a
    straight copy rather than a second deduplication pass — the generator owns entry identity.
    SQLite ids are 1-based, JSON ids are 0-based; the offset is applied here and nowhere else.
    """
    entries = dictionary["entries"]
    index = dictionary["index"]

    entry_rows: list[tuple[int, str, str, str]] = []
    for position, entry in enumerate(entries):
        simplified, pinyin, definition_list = validate_entry(f"entry[{position}]", 0, entry)
        entry_rows.append(
            (
                position + 1,
                simplified,
                pinyin,
                json.dumps(definition_list, ensure_ascii=False, separators=(",", ":")),
            )
        )

    key_rows: list[tuple[str, int, int]] = []
    key_entry_links: set[tuple[str, int]] = set()
    for key, ids in index.items():
        if not isinstance(key, str) or not key:
            raise ValueError(f"Invalid lookup key: {key!r}")
        if not isinstance(ids, list):
            raise ValueError(f"Invalid index for key {key!r}: expected list")

        for rank, entry_id in enumerate(ids):
            if not isinstance(entry_id, int) or not 0 <= entry_id < len(entries):
                raise ValueError(f"Index for {key!r} rank {rank} points outside entries: {entry_id!r}")

            key_entry = (key, entry_id + 1)
            if key_entry in key_entry_links:
                raise ValueError(f"Duplicate entry for lookup key {key!r} rank {rank}")

            key_entry_links.add(key_entry)
            key_rows.append((key, rank, entry_id + 1))

    with connection:
        connection.executemany(
            "INSERT INTO entries(id, simplified, pinyin, definitions) VALUES (?, ?, ?, ?)",
            entry_rows,
        )
        connection.executemany(
            "INSERT INTO lookup_keys(key, rank, entry_id) VALUES (?, ?, ?)",
            key_rows,
        )
        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            [
                ("schema_version", str(SCHEMA_VERSION)),
                ("source_sha256", source_sha256),
                ("entry_count", str(len(entry_rows))),
                ("lookup_count", str(len(key_rows))),
            ],
        )

    print(
        f"Built {connection.total_changes:,} rows "
        f"({len(entry_rows):,} entries, {len(key_rows):,} lookup links)"
    )
    return len(entry_rows), len(key_rows)


def validate_entry(key: str, rank: int, entry: object) -> tuple[str, str, list[str]]:
    if not isinstance(entry, dict):
        raise ValueError(f"Invalid entry for {key!r} rank {rank}: expected object")

    simplified = entry.get("s")
    pinyin = entry.get("p")
    definitions = entry.get("d")

    if not isinstance(simplified, str) or not simplified:
        raise ValueError(f"Invalid simplified text for {key!r} rank {rank}")
    if not isinstance(pinyin, str) or not pinyin:
        raise ValueError(f"Invalid pinyin for {key!r} rank {rank}")
    if (
        not isinstance(definitions, list)
        or not definitions
        or not all(isinstance(definition, str) and definition for definition in definitions)
    ):
        raise ValueError(f"Invalid definitions for {key!r} rank {rank}")

    return simplified, pinyin, definitions


def validate_database(connection: sqlite3.Connection, entry_count: int, lookup_count: int) -> None:
    integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        raise ValueError(f"SQLite integrity_check failed: {integrity}")

    foreign_key_errors = connection.execute("PRAGMA foreign_key_check").fetchall()
    if foreign_key_errors:
        raise ValueError(f"SQLite foreign_key_check failed: {foreign_key_errors!r}")

    actual_entries = connection.execute("SELECT count(*) FROM entries").fetchone()[0]
    actual_lookups = connection.execute("SELECT count(*) FROM lookup_keys").fetchone()[0]
    if actual_entries != entry_count or actual_lookups != lookup_count:
        raise ValueError(
            "SQLite row count mismatch: "
            f"expected {entry_count}/{lookup_count}, got {actual_entries}/{actual_lookups}"
        )


def validate_lookup_content(
    connection: sqlite3.Connection,
    dictionary: dict,
) -> None:
    """Assert the SQLite English index resolves to exactly what the JSON says.

    This is what guarantees the extension and the Android app cannot drift: both read the same
    artifact, and this check fails the build if the SQLite projection of it ever disagrees.
    """
    entries = dictionary["entries"]
    index = dictionary["index"]

    actual: dict[str, list[tuple[str, str, list[str]]]] = {}
    rows = connection.execute(
        """
        SELECT lookup_keys.key, entries.simplified, entries.pinyin, entries.definitions
        FROM lookup_keys
        JOIN entries ON entries.id = lookup_keys.entry_id
        ORDER BY lookup_keys.key, lookup_keys.rank
        """
    )
    for key, simplified, pinyin, definitions in rows:
        actual.setdefault(key, []).append((simplified, pinyin, json.loads(definitions)))

    if set(actual.keys()) != set(index.keys()):
        missing = set(index.keys()) - set(actual.keys())
        extra = set(actual.keys()) - set(index.keys())
        raise ValueError(f"Lookup key mismatch: missing={len(missing)}, extra={len(extra)}")

    for key, ids in index.items():
        expected = [
            (entries[entry_id]["s"], entries[entry_id]["p"], entries[entry_id]["d"])
            for entry_id in ids
        ]
        if actual[key] != expected:
            raise ValueError(f"Lookup content mismatch for key {key!r}")

    # Completeness is the whole point of the format change: every entry must land in SQLite,
    # including the ones no English key reaches (needed for hanzi -> English lookup).
    stored = connection.execute("SELECT count(*) FROM entries").fetchone()[0]
    if stored != len(entries):
        raise ValueError(f"Entry count mismatch: JSON has {len(entries)}, SQLite has {stored}")


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: build_dictionary.py <cedict.json> <output cedict.db>", file=sys.stderr)
        return 2

    build_dictionary(Path(sys.argv[1]), Path(sys.argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

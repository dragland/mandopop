#!/usr/bin/env python3
"""Convert Mandopop's pre-indexed cedict.json into an indexed SQLite asset."""

from __future__ import annotations

import json
import hashlib
import sqlite3
import sys
from pathlib import Path


def build_dictionary(input_path: Path, output_path: Path) -> None:
    source_sha256 = file_sha256(input_path)
    with input_path.open("r", encoding="utf-8") as source:
        dictionary = json.load(source)

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
        connection.execute("PRAGMA user_version=1")
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
        """
    )


def insert_rows(
    connection: sqlite3.Connection,
    dictionary: dict[str, list[dict]],
    source_sha256: str,
) -> tuple[int, int]:
    entry_ids: dict[tuple[str, str, str], int] = {}
    next_entry_id = 1
    entry_rows: list[tuple[int, str, str, str]] = []
    key_rows: list[tuple[str, int, int]] = []
    key_entry_links: set[tuple[str, int]] = set()

    for key, entries in dictionary.items():
        if not isinstance(key, str) or not key:
            raise ValueError(f"Invalid lookup key: {key!r}")
        if not isinstance(entries, list):
            raise ValueError(f"Invalid entries for key {key!r}: expected list")

        for rank, entry in enumerate(entries):
            simplified, pinyin, definition_list = validate_entry(key, rank, entry)
            definitions = json.dumps(definition_list, ensure_ascii=False, separators=(",", ":"))
            entry_key = (simplified, pinyin, definitions)
            entry_id = entry_ids.get(entry_key)
            if entry_id is None:
                entry_id = next_entry_id
                next_entry_id += 1
                entry_ids[entry_key] = entry_id
                entry_rows.append((entry_id, simplified, pinyin, definitions))

            key_entry = (key, entry_id)
            if key_entry in key_entry_links:
                raise ValueError(f"Duplicate entry for lookup key {key!r} rank {rank}")

            key_entry_links.add(key_entry)
            key_rows.append((key, rank, entry_id))

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
                ("schema_version", "1"),
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
    dictionary: dict[str, list[dict]],
) -> None:
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

    if set(actual.keys()) != set(dictionary.keys()):
        missing = set(dictionary.keys()) - set(actual.keys())
        extra = set(actual.keys()) - set(dictionary.keys())
        raise ValueError(f"Lookup key mismatch: missing={len(missing)}, extra={len(extra)}")

    for key, entries in dictionary.items():
        expected = [
            (entry["s"], entry["p"], entry["d"])
            for entry in entries
        ]
        if actual[key] != expected:
            raise ValueError(f"Lookup content mismatch for key {key!r}")


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: build_dictionary.py <cedict.json> <output cedict.db>", file=sys.stderr)
        return 2

    build_dictionary(Path(sys.argv[1]), Path(sys.argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

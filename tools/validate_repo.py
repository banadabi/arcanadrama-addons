#!/usr/bin/env python3
"""Validate ArcanaDrama source/builds repository rules without changing files."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

REPOSITORY = "https://github.com/banadabi/arcanadrama-addons"
BASE = "https://raw.githubusercontent.com/banadabi/arcanadrama-addons/builds"
PREFIX = "(Arcana Drama) "
REQUIRED = {"url", "status", "version", "name", "internalName", "authors", "description",
            "fileSize", "repositoryUrl", "language", "tvTypes", "apiVersion", "fileHash"}
EXCLUDED = {".git", ".gradle", ".kotlin", "build", ".release-cache", "__pycache__",
            ".published", "dist", "review-bundle"}
_SPEC = importlib.util.spec_from_file_location("arcana_release_rules_engine", Path(__file__).with_name("release.py"))
_RELEASE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_RELEASE)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _root(path):
    path = Path(path)
    if path.is_symlink() or not path.is_dir():
        raise ValueError("Repository roots must be real directories")
    return path.resolve()


def _safe_file(root, relative):
    relative = PurePosixPath(relative)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise ValueError("Unsafe repository path")
    path = root
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("Repository inputs must not use symlinks: " + relative.as_posix())
    if not path.is_file():
        raise ValueError("Required file is missing: " + relative.as_posix())
    return path


def _json(root, relative):
    return json.loads(_safe_file(root, relative).read_text(encoding="utf-8"))


def _files(root):
    """Use only this checkout's index, or a bounded exported staging tree."""
    if (root / ".git").exists():
        actual = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                                check=True, capture_output=True, text=True).stdout.strip()
        if Path(actual).resolve() != root:
            raise ValueError("Git checkout root does not match the selected repository")
        data = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                              check=True, capture_output=True).stdout
        return {os.fsdecode(name) for name in data.split(b"\0") if name}
    result = set()
    for directory, directories, filenames in os.walk(root, followlinks=False):
        directories[:] = [name for name in directories if name not in EXCLUDED]
        for name in list(directories):
            path = Path(directory) / name
            if path.is_symlink():
                result.add(path.relative_to(root).as_posix())
                directories.remove(name)
        for name in filenames:
            result.add((Path(directory) / name).relative_to(root).as_posix())
    return result


def _repo(value):
    if not isinstance(value, dict) or value.get("name") != "ArcanaDrama":
        raise ValueError("Repository name must be ArcanaDrama")
    if type(value.get("manifestVersion")) is not int or value["manifestVersion"] != 1:
        raise ValueError("Repository manifestVersion must remain protocol version 1")
    if value.get("pluginLists") != [BASE + "/plugins.json"]:
        raise ValueError("Repository must point to exactly the single builds plugins.json")


def _positive(value):
    return type(value) is int and value > 0


def _text(value):
    return isinstance(value, str) and bool(value.strip())


def _metadata(entry):
    if not isinstance(entry, dict) or REQUIRED - entry.keys():
        raise ValueError("Plugin metadata is missing required fields")
    name = entry["internalName"]
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name):
        raise ValueError("Invalid internalName")
    if not _text(entry["name"]) or not entry["name"].startswith(PREFIX) or not entry["name"][len(PREFIX):].strip():
        raise ValueError("Plugin display name must use the Arcana Drama prefix")
    if entry["authors"] != ["ArcanaDrama"]:
        raise ValueError("Plugin authors must be exactly ArcanaDrama")
    if not all(_positive(entry[field]) for field in ["version", "apiVersion", "fileSize"]):
        raise ValueError("Plugin version/apiVersion/fileSize must be positive integers")
    if type(entry["status"]) is not int or entry["status"] not in range(4):
        raise ValueError("Plugin status must be a known value 0..3")
    if re.search(r"https?://|www[.]|\b[a-z0-9-]+[.](?:com|net|org|xyz|io|tv|ai|online|site|download|co|tr)\b", entry["name"], re.I):
        raise ValueError("Plugin display name must not contain a source domain")
    allowed_descriptions = {"Türkçe dublaj.", "Türkçe altyazı.", "Türkçe dublaj ve Türkçe altyazı.", "Türkçe altyazı ve çoklu altyazı.", "Türkçe dublaj, Türkçe altyazı ve çoklu altyazı."}
    if entry["description"] not in allowed_descriptions:
        raise ValueError("Plugin description must contain only verified language options")
    if not _text(entry["description"]) or not _text(entry["language"]):
        raise ValueError("Plugin description and language are required")
    if not isinstance(entry["tvTypes"], list) or not entry["tvTypes"] or not all(map(_text, entry["tvTypes"])):
        raise ValueError("Plugin tvTypes must be a nonempty string array")
    if entry["repositoryUrl"] != REPOSITORY:
        raise ValueError("Plugin repositoryUrl must identify the ArcanaDrama repository")
    return name


def validate(main, builds, preview=False):
    main, builds = _root(main), _root(builds)
    main_files, builds_files = _files(main), _files(builds)
    for label, root_path, files in [("main", main, main_files), ("builds", builds, builds_files)]:
        if any(PurePosixPath(name).suffix.lower() == ".zip" for name in files):
            raise ValueError("ZIP files are forbidden in " + label)
        for relative in files:
            _safe_file(root_path, relative)
    for relative in main_files:
        if PurePosixPath(relative).suffix.lower() == ".cs3":
            raise ValueError("CS3 files belong only in builds; none are allowed in main: " + relative)
    root = _json(builds, "repo.json")
    _repo(root)
    entries = _json(builds, "plugins.json")
    if not isinstance(entries, list) or not entries:
        raise ValueError("Builds plugins.json must be a nonempty entry array")
    if not preview:
        main_root = _json(main, "repo.json")
        _repo(main_root)
        if main_root != root or _json(main, "plugins.json") != entries:
            raise ValueError("Main repo/list must mirror the builds JSON")
    identities, hashes = set(), {}
    for entry in entries:
        name = _metadata(entry)
        if name in identities:
            raise ValueError("Duplicate internalName: " + name)
        identities.add(name)
        filename = name + ".cs3"
        data = _safe_file(builds, filename).read_bytes()
        digest = _sha(data)
        if entry["fileHash"] != "sha256-" + digest or entry["fileSize"] != len(data):
            raise ValueError("Actual artifact hash/size mismatch: " + name)
        if entry["url"] != f"{BASE}/{filename}?v={digest}":
            raise ValueError("Artifact URL must contain the exact full hash query: " + name)
        artifact_manifest = _RELEASE._inspect_cs3(data)
        module_manifest = _json(main, name + "/manifest.json")
        if artifact_manifest != module_manifest or artifact_manifest.get("name") != name or artifact_manifest.get("version") != entry["version"]:
            raise ValueError("Module/artifact/list manifest identity or version drift: " + name)
        module_files = {p for p in main_files if PurePosixPath(p).parts[0] == name}
        kotlin = [p for p in module_files if PurePosixPath(p).suffix == ".kt" and "src" in PurePosixPath(p).parts]
        if not kotlin:
            raise ValueError("Module must include actual Kotlin source: " + name)
        for relative in module_files:
            _safe_file(main, relative)
        gradle = [name + "/build.gradle.kts", name + "/build.gradle"]
        if not any(path in module_files for path in gradle):
            raise ValueError("Module Gradle build source is required: " + name)
        hashes[name] = digest
    # Check the actual builds root too, so a newly staged unindexed orphan cannot slip through.
    actual_root_cs3 = {p.name for p in builds.iterdir() if p.suffix.lower() == ".cs3"}
    listed_cs3 = {name + ".cs3" for name in identities}
    tracked_cs3 = {p for p in builds_files if PurePosixPath(p).suffix.lower() == ".cs3"}
    if actual_root_cs3 != listed_cs3 or tracked_cs3 != listed_cs3:
        raise ValueError("Builds CS3 files must be exactly the listed root artifacts; orphan found")
    return {"valid": True, "preview": bool(preview), "plugins": len(entries), "hashes": hashes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main", type=Path, required=True)
    parser.add_argument("--builds", type=Path, required=True)
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    try:
        result = validate(args.main, args.builds, args.preview)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(json.dumps({"valid": False, "error": str(error)}, ensure_ascii=False))
        raise SystemExit(2)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

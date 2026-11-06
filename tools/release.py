#!/usr/bin/env python3
"""Prepare immutable CS3 versions and generate repository files; never publish them."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from urllib.parse import urlsplit
import zipfile

EXCLUDED = {".git", "build", ".gradle", ".kotlin", "artifacts", "receipts", "docs", "__pycache__"}
BUILD_NAMES = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
               "gradle.properties", "gradlew", "gradlew.bat", "release-inputs.json"}


def _json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _identity(manifest):
    name, version = manifest.get("name"), manifest.get("version")
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name):
        raise ValueError("Invalid manifest name")
    if type(version) is not int or version < 1:
        raise ValueError("Invalid manifest version")
    return name, version


def _manifest(module):
    path = Path(module) / "manifest.json"
    if path.is_symlink():
        raise ValueError("Manifest must be a regular file")
    value = json.loads(path.read_text(encoding="utf-8"))
    _identity(value)
    return value


def source_fingerprint(module):
    """Hash executable/build inputs, normalizing JSON and omitting manifest.version."""
    module = Path(module)
    manifest = dict(_manifest(module))
    manifest.pop("version")
    inputs = {"manifest.json": _sha(_json_bytes(manifest))}
    for path in sorted(module.rglob("*")):
        parts = path.relative_to(module).parts
        if any(part in EXCLUDED for part in parts) or path.name == "local.properties":
            continue
        if path.suffix.lower() == ".md" or path.name.lower().startswith(("license", "copying", "notice")):
            continue
        source_dirs = {"src", "compile-api", "libs", "gradle"}
        selected = path.name in BUILD_NAMES | source_dirs or any(p in source_dirs for p in parts[:-1])
        if not selected:
            continue
        if path.is_symlink():
            raise ValueError("Source inputs must not be symlinks")
        if path.is_file():
            inputs[path.relative_to(module).as_posix()] = _sha(path.read_bytes())
    return _sha(_json_bytes(inputs))


def _atomic_write(path, data, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Refusing a symlink destination")
    fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            os.fchmod(stream.fileno(), mode)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _save(path, state):
    _atomic_write(path, _json_bytes(state))


@contextmanager
def _locked_state(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(path) + ".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        if os.fstat(fd).st_uid != os.getuid():
            raise ValueError("Foreign lock ownership")
        fcntl.flock(fd, fcntl.LOCK_EX)
        if path.is_symlink():
            raise ValueError("State must not be a symlink")
        state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"plugins": {}}
        if not isinstance(state, dict) or not isinstance(state.get("plugins"), dict):
            raise ValueError("Invalid release state")
        yield state
    finally:
        os.close(fd)  # Keep the dedicated lock file; never unlink another process's lock.


def _artifact_path(state_path, artifact):
    path = Path(artifact["path"])
    return path if path.is_absolute() else Path(state_path).parent / path


def _artifact_bytes(state_path, artifact, missing_ok=False):
    path = _artifact_path(state_path, artifact)
    if path.is_symlink():
        raise ValueError("Frozen artifact must not be a symlink")
    if not path.exists() and missing_ok:
        return None
    data = path.read_bytes()
    if _sha(data) != artifact["sha256"] or len(data) != artifact["size"]:
        raise ValueError("Frozen artifact integrity mismatch")
    return data


def prepare(module, state_path):
    module, state_path = Path(module), Path(state_path)
    with _locked_state(state_path) as state:
        manifest = _manifest(module)
        name, version = _identity(manifest)
        digest = source_fingerprint(module)
        previous = state["plugins"].get(name)
        if previous and "manifest_update_from" in previous:
            if digest != previous["source_digest"] or version not in {previous["manifest_update_from"], previous["version"]}:
                raise ValueError("Interrupted manifest update conflicts with current source")
            manifest["version"] = previous["version"]
            _atomic_write(module / "manifest.json", _json_bytes(manifest), 0o644)
            del previous["manifest_update_from"]
            _save(state_path, state)
            version = previous["version"]
        if previous and version != previous["version"]:
            raise ValueError("Manifest version drift")
        changed = previous is not None and digest != previous["source_digest"]
        if previous is None or changed:
            entry = {"source_digest": digest, "version": version + (1 if changed else 0)}
            state["plugins"][name] = entry
            if changed:
                entry["manifest_update_from"] = version
                _save(state_path, state)  # Recover either side of the two-file write on the next prepare.
                manifest["version"] = entry["version"]
                _atomic_write(module / "manifest.json", _json_bytes(manifest), 0o644)
                del entry["manifest_update_from"]
            _save(state_path, state)
        else:
            entry = previous
        reused = bool(entry.get("artifact") and _artifact_bytes(state_path, entry["artifact"], missing_ok=True) is not None)
        return {"internalName": name, "version": entry["version"], "source_digest": digest,
                "reused": reused, "build_required": not reused}


def _inspect_cs3(data):
    if len(data) > 30 * 1024 * 1024:
        raise ValueError("CS3 is too large")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        if len(infos) != 2 or {i.filename for i in infos} != {"manifest.json", "classes.dex"}:
            raise ValueError("CS3 must contain exactly manifest.json and classes.dex")
        for info in infos:
            if info.flag_bits & 1 or stat.S_ISLNK(info.external_attr >> 16) or info.file_size > 60 * 1024 * 1024:
                raise ValueError("Invalid CS3 member")
        if archive.getinfo("manifest.json").file_size > 256 * 1024:
            raise ValueError("CS3 manifest is too large")
        manifest = json.loads(archive.read("manifest.json"))
        _identity(manifest)
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("CS3 DEX header missing")
    return manifest


def _freeze(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o444)
    except FileExistsError:
        if path.is_symlink() or path.read_bytes() != data:
            raise ValueError("Frozen cache collision")
        return
    identity = os.fstat(fd)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        current = path.lstat()
        if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
            path.unlink()
        raise


def register(module, artifact_path, state_path, cache_dir):
    module, state_path = Path(module), Path(state_path)
    with _locked_state(state_path) as state:
        manifest = _manifest(module)
        name, version = _identity(manifest)
        entry = state["plugins"].get(name)
        if not entry or "manifest_update_from" in entry or version != entry["version"]:
            raise ValueError("Prepare the exact source/version before registering")
        if source_fingerprint(module) != entry["source_digest"]:
            raise ValueError("Source changed since prepare")
        data = Path(artifact_path).read_bytes()
        if _inspect_cs3(data) != manifest:
            raise ValueError("Artifact manifest differs from current module manifest")
        digest = _sha(data)
        if entry.get("artifact") and entry["artifact"]["sha256"] != digest:
            raise ValueError("Same source/version already has a different frozen artifact")
        frozen = Path(cache_dir).resolve() / name / str(version) / (digest + ".cs3")
        _freeze(frozen, data)
        if source_fingerprint(module) != entry["source_digest"] or _manifest(module) != manifest:
            raise ValueError("Source changed during registration")
        entry["artifact"] = {"sha256": digest, "size": len(data),
                             "path": os.path.relpath(frozen, state_path.resolve().parent)}
        _save(state_path, state)
        return {"internalName": name, "version": version, **entry["artifact"]}


def catalogue(state_path, metadata_path, base_url, out_dir):
    base_url = base_url.rstrip("/")
    url = urlsplit(base_url)
    if url.scheme != "https" or not url.hostname or url.username is not None or url.password is not None or url.query or url.fragment:
        raise ValueError("Base URL must be an absolute HTTPS directory without credentials/query/fragment")
    metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
    if not isinstance(metadata, list) or not metadata:
        raise ValueError("Metadata must be a nonempty entry array")
    with _locked_state(state_path) as state:
        entries, files, names = [], {}, set()
        for item in metadata:
            if not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"].strip():
                raise ValueError("Metadata display name is required")
            name = item.get("internalName")
            _identity({"name": name, "version": 1})
            if name in names or not isinstance(item.get("authors"), list) or not item["authors"] or any(not isinstance(a, str) or not a.strip() for a in item["authors"]):
                raise ValueError("Duplicate internal name or missing authors")
            names.add(name)
            entry = state["plugins"].get(name)
            if not entry or not entry.get("artifact") or "manifest_update_from" in entry:
                raise ValueError("Catalogue entry has no registered artifact")
            data = _artifact_bytes(state_path, entry["artifact"])
            if _identity(_inspect_cs3(data)) != (name, entry["version"]):
                raise ValueError("Frozen manifest/state identity mismatch")
            artifact = entry["artifact"]
            output = dict(item)
            output.update(version=entry["version"], fileHash="sha256-" + artifact["sha256"],
                          fileSize=artifact["size"], url=f"{base_url}/{name}.cs3?v={artifact['sha256']}")
            entries.append(output)
            files[name + ".cs3"] = data
        files["plugins.json"] = _json_bytes(entries)
        files["repo.json"] = _json_bytes({"name": "ArcanaDrama", "manifestVersion": 1,
                                           "pluginLists": [base_url + "/plugins.json"]})
        for name, data in files.items():
            _atomic_write(Path(out_dir) / name, data, 0o644)
        return {"generated": sorted(files), "plugins": len(entries), "published": False}


def pack_cs3(manifest_path, dex_path, out_path):
    """Deterministic future build packaging; do not repackage accepted artifacts."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    _identity(manifest)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in [("manifest.json", _json_bytes(manifest)), ("classes.dex", Path(dex_path).read_bytes())]:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    data = buffer.getvalue()
    _inspect_cs3(data)
    _atomic_write(out_path, data, 0o644)
    return {"sha256": _sha(data), "size": len(data)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ["prepare", "register"]:
        command = commands.add_parser(name)
        command.add_argument("module", type=Path)
        command.add_argument("--state", type=Path, required=True)
        if name == "register":
            command.add_argument("artifact", type=Path)
            command.add_argument("--cache", type=Path, required=True)
    command = commands.add_parser("catalogue")
    for flag in ["state", "metadata", "out"]:
        command.add_argument("--" + flag, type=Path, required=True)
    command.add_argument("--base-url", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.module, args.state)
        elif args.command == "register":
            result = register(args.module, args.artifact, args.state, args.cache)
        else:
            result = catalogue(args.state, args.metadata, args.base_url, args.out)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Release preparation failed: {error}\n")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

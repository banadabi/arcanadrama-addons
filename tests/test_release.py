import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "tools/release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="arcana-release-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.module = self.root / "ArcanaExample"
        self.module.mkdir()
        self.manifest = {"name": "ArcanaExample", "version": 4,
                         "pluginClassName": "dev.arcana.Example", "requiresResources": False}
        self.manifest_path = self.module / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest))
        self.source = self.module / "provider/src/main/kotlin/Example.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("class Example\n")
        (self.module / "build.gradle.kts").write_text("plugins { id(\"com.android.library\") }\n")
        self.state = self.root / "release-state.json"
        self.cache = self.root / ".release-cache"
        self.dex = self.root / "classes.dex"
        self.dex.write_bytes(b"dex\n039\x00" + bytes(112))
        self.artifact = self.root / "candidate.cs3"

    def pack(self, target=None):
        release.pack_cs3(self.manifest_path, self.dex, target or self.artifact)
        return target or self.artifact

    def register(self):
        self.pack()
        return release.register(self.module, self.artifact, self.state, self.cache)

    def test_real_source_bump_and_unchanged_artifact_reuse(self):
        self.assertEqual(release.prepare(self.module, self.state)["version"], 4)
        registered = self.register()
        reused = release.prepare(self.module, self.state)
        self.assertTrue(reused["reused"])
        self.assertFalse(reused["build_required"])
        (self.module / "README.md").write_text("Documentation changed\n")
        ignored = self.module / "provider/build/classes/ignored.class"
        ignored.parent.mkdir(parents=True)
        ignored.write_bytes(b"build output")
        self.assertEqual(release.prepare(self.module, self.state), reused)
        self.source.write_text("class Example { val corrected = true }\n")
        changed = release.prepare(self.module, self.state)
        self.assertEqual(changed["version"], 5)
        self.assertTrue(changed["build_required"])
        self.assertEqual(json.loads(self.manifest_path.read_text())["version"], 5)
        self.assertNotIn("artifact", json.loads(self.state.read_text())["plugins"]["ArcanaExample"])
        self.assertTrue((self.state.parent / registered["path"]).is_file())
        (self.module / "release-inputs.json").write_text('{"builder":"new-sha"}')
        self.assertEqual(release.prepare(self.module, self.state)["version"], 6)

    def test_pending_retry_keeps_version_and_recovers_two_file_update(self):
        first = release.prepare(self.module, self.state)
        self.assertEqual(release.prepare(self.module, self.state), first)
        self.source.write_text("class NewExample\n")
        pending = release.prepare(self.module, self.state)
        self.assertEqual(pending["version"], 5)
        self.assertEqual(release.prepare(self.module, self.state), pending)
        state = json.loads(self.state.read_text())
        entry = state["plugins"]["ArcanaExample"]
        entry.update(version=6, manifest_update_from=5)
        self.state.write_text(json.dumps(state))
        recovered = release.prepare(self.module, self.state)
        self.assertEqual(recovered["version"], 6)
        self.assertEqual(json.loads(self.manifest_path.read_text())["version"], 6)
        self.assertEqual(release.prepare(self.module, self.state), recovered)

    def test_manifest_drift_and_source_change_reject_registration(self):
        release.prepare(self.module, self.state)
        other = self.root / "other-manifest.json"
        other.write_text(json.dumps({**self.manifest, "version": 99}))
        release.pack_cs3(other, self.dex, self.artifact)
        with self.assertRaisesRegex(ValueError, "Artifact manifest"):
            release.register(self.module, self.artifact, self.state, self.cache)
        self.pack()
        self.source.write_text("changed before register\n")
        with self.assertRaisesRegex(ValueError, "Source changed"):
            release.register(self.module, self.artifact, self.state, self.cache)
        self.manifest_path.write_text(json.dumps({**self.manifest, "version": 42}))
        with self.assertRaisesRegex(ValueError, "version drift"):
            release.prepare(self.module, self.state)

    def test_frozen_artifact_reuses_exact_bytes_and_rejects_rebuild_difference(self):
        release.prepare(self.module, self.state)
        first = self.register()
        frozen = self.state.parent / first["path"]
        original = frozen.read_bytes()
        self.assertEqual(release.register(self.module, self.artifact, self.state, self.cache), first)
        self.dex.write_bytes(b"dex\n039\x00" + bytes(113))
        other = self.pack(self.root / "different.cs3")
        with self.assertRaisesRegex(ValueError, "different frozen artifact"):
            release.register(self.module, other, self.state, self.cache)
        self.assertEqual(frozen.read_bytes(), original)
        frozen.unlink()
        pending = release.prepare(self.module, self.state)
        self.assertEqual(pending["version"], 4)
        self.assertTrue(pending["build_required"])
        with self.assertRaisesRegex(ValueError, "different frozen artifact"):
            release.register(self.module, other, self.state, self.cache)
        release.register(self.module, self.artifact, self.state, self.cache)
        self.assertTrue(release.prepare(self.module, self.state)["reused"])

    def test_packaging_is_deterministic_and_has_only_two_members(self):
        first = release.pack_cs3(self.manifest_path, self.dex, self.artifact)
        second_path = self.root / "again.cs3"
        self.manifest_path.write_text(json.dumps(dict(reversed(list(self.manifest.items()))), indent=4))
        second = release.pack_cs3(self.manifest_path, self.dex, second_path)
        self.assertEqual(first, second)
        self.assertEqual(self.artifact.read_bytes(), second_path.read_bytes())
        with zipfile.ZipFile(self.artifact) as archive:
            self.assertEqual(archive.namelist(), ["manifest.json", "classes.dex"])
            self.assertTrue(all(info.date_time == (1980, 1, 1, 0, 0, 0) for info in archive.infolist()))
            self.assertTrue(all(info.external_attr >> 16 == 0o100644 for info in archive.infolist()))

    def test_catalogue_has_exact_hash_size_version_and_stable_root(self):
        release.prepare(self.module, self.state)
        registered = self.register()
        metadata = self.root / "metadata.json"
        labels = {"internalName": "ArcanaExample", "name": "(Arcana Drama) Örnek",
                  "authors": ["ArcanaDrama"], "description": "Türkçe açıklama", "status": 1}
        metadata.write_text(json.dumps([labels]))
        out = self.root / "public"
        base = "https://raw.githubusercontent.com/owner/repo/builds"
        result = release.catalogue(self.state, metadata, base, out)
        self.assertFalse(result["published"])
        entry = json.loads((out / "plugins.json").read_text())[0]
        for key, value in labels.items():
            self.assertEqual(entry[key], value)
        data = self.artifact.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual(entry["version"], 4)
        self.assertEqual(entry["fileHash"], "sha256-" + digest)
        self.assertEqual(entry["fileSize"], len(data))
        self.assertEqual(entry["url"], base + "/ArcanaExample.cs3?v=" + digest)
        self.assertEqual((out / "ArcanaExample.cs3").read_bytes(), data)
        self.assertEqual(set(p.name for p in out.iterdir()), {"ArcanaExample.cs3", "plugins.json", "repo.json"})
        self.assertEqual(json.loads((out / "repo.json").read_text()), {
            "name": "ArcanaDrama", "manifestVersion": 1, "pluginLists": [base + "/plugins.json"]})
        frozen = self.state.parent / registered["path"]
        frozen.chmod(0o600)
        frozen.write_bytes(data + b"corruption")
        with self.assertRaisesRegex(ValueError, "integrity mismatch"):
            release.catalogue(self.state, metadata, base, out)


if __name__ == "__main__":
    unittest.main()

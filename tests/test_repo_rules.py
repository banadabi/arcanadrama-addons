import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1] / "tools"
SPEC = importlib.util.spec_from_file_location("validate_repo", TOOLS / "validate_repo.py")
rules = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rules)


class RepoRulesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="arcana-repo-rules-")
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.main, self.builds = root / "main", root / "builds"
        self.main.mkdir()
        self.builds.mkdir()
        self.module = self.main / "ArcanaExample"
        self.module.mkdir()
        self.manifest = {"name": "ArcanaExample", "version": 1,
                         "pluginClassName": "dev.arcana.Example", "requiresResources": False}
        self.manifest_path = self.module / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest))
        source = self.module / "provider/src/main/kotlin/Example.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class Example\n")
        (self.module / "build.gradle.kts").write_text("plugins {}\n")
        dex = root / "classes.dex"
        dex.write_bytes(b"dex\n039\0" + bytes(112))
        artifact = self.builds / "ArcanaExample.cs3"
        rules._RELEASE.pack_cs3(self.manifest_path, dex, artifact)
        data = artifact.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        self.entries = [{"name": "(Arcana Drama) Örnek", "internalName": "ArcanaExample",
                         "authors": ["ArcanaDrama"], "description": "Gerçek kaynak açıklaması",
                         "version": 1, "apiVersion": 1, "status": 1, "language": "tr",
                         "tvTypes": ["TvSeries"], "repositoryUrl": rules.REPOSITORY,
                         "fileHash": "sha256-" + digest, "fileSize": len(data),
                         "url": rules.BASE + "/ArcanaExample.cs3?v=" + digest}]
        self.root_json = {"name": "ArcanaDrama", "manifestVersion": 1,
                          "pluginLists": [rules.BASE + "/plugins.json"]}
        self.write_json()

    def write_json(self):
        for root in [self.main, self.builds]:
            (root / "plugins.json").write_text(json.dumps(self.entries))
            (root / "repo.json").write_text(json.dumps(self.root_json))

    def test_valid_source_and_exact_builds_fixture(self):
        self.assertEqual(rules.validate(self.main, self.builds)["plugins"], 1)
        self.assertTrue(rules.validate(self.main, self.builds)["valid"])
        (self.main / "plugins.json").write_text("[]")
        self.assertTrue(rules.validate(self.main, self.builds, preview=True)["valid"])
        with self.assertRaisesRegex(ValueError, "mirror"):
            rules.validate(self.main, self.builds)

    def test_zip_source_blob_is_rejected(self):
        (self.module / "source.zip").write_bytes(b"No ZIP blobs belong in the repository")
        with self.assertRaisesRegex(ValueError, "ZIP files"):
            rules.validate(self.main, self.builds)

    def test_hash_and_literal_hash_query_must_match(self):
        self.entries[0]["fileHash"] = "sha256-" + "0" * 64
        self.write_json()
        with self.assertRaisesRegex(ValueError, "hash/size"):
            rules.validate(self.main, self.builds)
        artifact = self.builds / "ArcanaExample.cs3"
        self.entries[0]["fileHash"] = "sha256-" + hashlib.sha256(artifact.read_bytes()).hexdigest()
        self.entries[0]["url"] = rules.BASE + "/ArcanaExample.cs3?v=stale"
        self.write_json()
        with self.assertRaisesRegex(ValueError, "full hash query"):
            rules.validate(self.main, self.builds)

    def test_display_brand_and_author_are_enforced(self):
        self.entries[0]["name"] = "Unbranded Example"
        self.write_json()
        with self.assertRaisesRegex(ValueError, "prefix"):
            rules.validate(self.main, self.builds)
        self.entries[0]["name"] = "(Arcana Drama) Örnek"
        self.entries[0]["authors"] = ["Someone Else"]
        self.write_json()
        with self.assertRaisesRegex(ValueError, "authors"):
            rules.validate(self.main, self.builds)

    def test_module_manifest_version_drift_is_rejected(self):
        self.manifest_path.write_text(json.dumps({**self.manifest, "version": 2}))
        with self.assertRaisesRegex(ValueError, "version drift"):
            rules.validate(self.main, self.builds)

    def test_unlisted_builds_artifact_is_rejected(self):
        (self.builds / "Orphan.cs3").write_bytes(b"not a listed artifact")
        with self.assertRaisesRegex(ValueError, "orphan"):
            rules.validate(self.main, self.builds)

    def test_former_main_alias_is_rejected_without_exception(self):
        aliases = self.main / "plugins"
        aliases.mkdir()
        (aliases / "ArcanaShortizle-4.cs3").write_bytes((self.builds / "ArcanaExample.cs3").read_bytes())
        with self.assertRaisesRegex(ValueError, "none are allowed in main"):
            rules.validate(self.main, self.builds)


if __name__ == "__main__":
    unittest.main()

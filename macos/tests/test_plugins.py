import http.server
import json
import platform
import py_compile
import shutil
import stat
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

from gallery_dl import extractor, version

import gdl_ios
import gdl_plugins
from gdl_plugins import PluginStore


URL = "https://gallery.example/picture/1"
PAYLOAD = b"synthetic plugin download"
SOURCE = r'''
from gallery_dl.extractor.common import Extractor, Message

class Fixture(Extractor):
    category = "fixture"
    subcategory = "image"
    pattern = r"https://gallery\.example/picture/(\d+)$"
    directory_fmt = ("{category}",)
    filename_fmt = "{id}.{extension}"
    archive_fmt = "{id}"

    def items(self):
        data = {"id": self.groups[0], "extension": "jpg"}
        yield Message.Directory, "", data
        yield Message.Url, FILE_URL, data

EXTRACTORS = (Fixture,)
'''


class PluginsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        extractor.extractors()

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.directory = self.folder / "installed"
        self.store = PluginStore(self.directory)
        classes = extractor._cache[:]
        self.addCleanup(extractor._cache.__setitem__, slice(None), classes)
        self.addCleanup(setattr, gdl_plugins, "_store", None)

    def package(self, source=SOURCE, changes=None, extra=None, bad_magic=False):
        manifest = {
            "format": 1, "id": "fixture", "name": "Fixture", "version": "1.0",
            "domains": ["gallery.example"], "platform": f"macos-{platform.machine()}",
            "python": f"{sys.version_info.major}.{sys.version_info.minor}",
            "gallery_dl": version.__version__,
        }
        manifest.update(changes or {})
        script = self.folder / "source.py"
        script.write_text(source, encoding="utf-8")
        bytecode = self.folder / "extractor.pyc"
        py_compile.compile(str(script), str(bytecode), doraise=True)
        archive = self.folder / "test.gdlplugin"
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr("manifest.json", json.dumps(manifest))
            package.writestr("extractor.pyc", b"nope" + bytecode.read_bytes()[4:] if bad_magic else bytecode.read_bytes())
            if extra is not None:
                package.writestr(*extra)
        return archive

    def call(self, action, **args):
        return json.loads(gdl_ios.plugins(json.dumps({"action": action, **args})))

    def test_import_enable_restart_check_download_disable_and_remove(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(PAYLOAD)))
                self.end_headers()
                self.wfile.write(PAYLOAD)

            def log_message(self, *args):
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                self._lifecycle(f"http://127.0.0.1:{server.server_port}/file.jpg")
            finally:
                server.shutdown()
                thread.join()

    def _lifecycle(self, file_url):
        def supported():
            return json.loads(gdl_ios.check(json.dumps({"url": URL})))["supported"]

        start_arg = json.dumps({"directory": str(self.directory)})
        gdl_ios.start_plugins(start_arg)
        self.assertEqual(self.call("list")["plugins"], [])
        archive = self.package(SOURCE + f"\nFILE_URL = {file_url!r}\n")
        installed = self.call("import", path=str(archive))
        self.assertFalse(installed["plugins"][0]["enabled"])
        self.assertFalse(installed["restart_required"])
        self.assertFalse(supported())
        self.assertIn("already installed", self.call("import", path=str(archive))["error"])
        self.assertTrue(self.call("enable", id="fixture")["restart_required"])
        self.assertFalse(supported())

        gdl_ios.start_plugins(start_arg)
        self.addCleanup(gdl_plugins._store.active["fixture"].cleanup)
        self.addCleanup(sys.modules.pop, "_gdl_plugin_fixture", None)
        self.assertTrue(self.call("list")["plugins"][0]["active"])
        self.assertTrue(supported())
        self.assertIsNotNone(extractor.find("https://www.reddit.com/r/test/"))
        download_dir = self.folder / "download"
        result = json.loads(gdl_ios.download(json.dumps({
            "url": URL, "base_dir": str(download_dir), "archive_path": "", "options": {}, "run": "test",
        })))
        self.assertEqual(result["status"], 0, result)
        self.assertEqual((download_dir / "fixture" / "1.jpg").read_bytes(), PAYLOAD)
        self.assertTrue(self.call("disable", id="fixture")["restart_required"])
        self.assertTrue(supported(), "a live registry is not changed")
        self.assertEqual(self.call("remove", id="fixture", enabled=False)["plugins"], [])
        # Reset registrations as a relaunch would, keeping the on-disk state.
        extractor._cache[:] = [cls for cls in extractor._cache if cls.__module__ != "_gdl_plugin_fixture"]
        gdl_ios.start_plugins(start_arg)
        self.assertFalse(supported())

    def test_rejects_bad_platform_versions_and_magic(self):
        for changes, bad_magic, message in [
            ({"platform": "macos-other"}, False, "architecture"),
            ({"python": "0.0"}, False, "Python"),
            ({"gallery_dl": "0.0"}, False, "gallery-dl"),
            (None, True, "bytecode"),
        ]:
            with self.subTest(changes=changes, bad_magic=bad_magic):
                with self.assertRaisesRegex(ValueError, message):
                    self.store.install(self.package(changes=changes, bad_magic=bad_magic))
        self.assertEqual(self.store.list()["plugins"], [])

    def test_copying_a_package_into_storage_does_not_enable_it(self):
        archive = self.package()
        self.directory.mkdir()
        shutil.copyfile(archive, self.directory / "fixture.gdlplugin")
        self.store.activate()
        self.assertEqual(self.store.list()["plugins"], [])
        self.assertIsNone(extractor.find(URL))

    def test_rejects_traversal_symlinks_and_duplicate_members(self):
        symlink = zipfile.ZipInfo("assets/link")
        symlink.create_system = 3
        symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertWarns(UserWarning):
            duplicate = self.package(extra=("manifest.json", "{}"))
        with self.assertRaisesRegex(ValueError, "Unsupported files"):
            self.store.install(duplicate)
        for entry in [("assets/../escape", "x"), (symlink, "/tmp/escape"), ("Extractor.pyc", "x")]:
            with self.subTest(entry=entry):
                with self.assertRaisesRegex(ValueError, "Unsupported files"):
                    self.store.install(self.package(extra=entry))
        self.assertEqual(self.store.list()["plugins"], [])

    def test_broken_enabled_plugin_does_not_break_upstream(self):
        self.store.install(self.package('raise RuntimeError("fixture failed")'))
        self.store.set_enabled("fixture", True)
        with self.assertLogs("gdl_plugins", level="ERROR"):
            self.store.activate()
        listed = self.store.list()["plugins"][0]
        self.assertFalse(listed["active"])
        self.assertIn("fixture failed", listed["error"])
        self.assertIsNotNone(extractor.find("https://www.reddit.com/r/test/"))

    def test_asset_is_available_beside_loaded_bytecode(self):
        asset = zipfile.ZipInfo("assets/example-tool")
        asset.create_system = 3
        asset.external_attr = (stat.S_IFREG | 0o755) << 16
        self.store.install(self.package(extra=(asset, PAYLOAD)))
        self.store.set_enabled("fixture", True)
        self.store.activate()
        folder = self.store.active["fixture"]
        self.addCleanup(folder.cleanup)
        self.addCleanup(sys.modules.pop, "_gdl_plugin_fixture", None)
        unpacked = Path(folder.name) / asset.filename
        self.assertEqual(unpacked.read_bytes(), PAYLOAD)
        self.assertEqual(stat.S_IMODE(unpacked.stat().st_mode), 0o700)

    def test_mobile_targets_reject_other_packages_and_executable_assets(self):
        executable = zipfile.ZipInfo("assets/example-tool")
        executable.create_system = 3
        executable.external_attr = (stat.S_IFREG | 0o755) << 16
        for target in ("android", "ios"):
            with self.subTest(target=target):
                store = PluginStore(self.folder / target, target=target)
                with self.assertRaisesRegex(ValueError, "different platform"):
                    store.install(self.package())
                with self.assertRaisesRegex(ValueError, "Executable assets"):
                    store.install(self.package(changes={"platform": target}, extra=(executable, PAYLOAD)))
                store.install(self.package(changes={"platform": target}))
                self.assertFalse(store.list()["plugins"][0]["enabled"])


if __name__ == "__main__":
    unittest.main()

"""Local, explicitly trusted extractor packages. Registry changes happen only at startup."""
import importlib.machinery
import importlib.util
import json
import logging
import os
import platform
import re
import shutil
import stat
import sys
import tempfile
import threading
import zipfile
from pathlib import Path

from gallery_dl import extractor, text, version
from gallery_dl.extractor.common import Extractor

MAX_PACKAGE = 256 * 1024 * 1024
MAX_MANIFEST = 16 * 1024
MAX_BYTECODE = 4 * 1024 * 1024
ID = re.compile(r"[a-z][a-z0-9_-]{0,63}")
ASSET = re.compile(r"assets/[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
DOMAIN = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
DISABLED = ".disabled.gdlplugin"
ENABLED = ".enabled.gdlplugin"


def _label(value, limit):
    return isinstance(value, str) and 0 < len(value) <= limit and value == value.strip() and value.isprintable()


def inspect(package, target):
    """Read metadata and check compatibility without interpreting any bytecode."""
    members = package.infolist()
    names = {member.filename for member in members}
    if (
        len(members) != len(names) or len({name.lower() for name in names}) != len(names)
        or not {"manifest.json", "extractor.pyc"} <= names or len(members) > 34
        or sum(member.file_size for member in members) > MAX_PACKAGE
        or any(name not in {"manifest.json", "extractor.pyc"} and not ASSET.fullmatch(name) for name in names)
        or any(stat.S_IFMT(member.external_attr >> 16) not in (0, stat.S_IFREG) for member in members)
    ):
        raise ValueError("Unsupported files, duplicate names, or size limit exceeded in the plugin.")
    if package.getinfo("manifest.json").file_size > MAX_MANIFEST or not 16 <= package.getinfo("extractor.pyc").file_size <= MAX_BYTECODE:
        raise ValueError("Invalid manifest or extractor size.")
    try:
        meta = json.loads(package.read("manifest.json"))
    except (ValueError, UnicodeError) as exc:
        raise ValueError("The plugin manifest is not valid JSON.") from exc
    if not isinstance(meta, dict) or type(meta.get("format")) is not int or meta["format"] != 1:
        raise ValueError("Unsupported plugin format.")
    if not isinstance(meta.get("id"), str) or not ID.fullmatch(meta["id"]):
        raise ValueError("The manifest needs a valid plugin ID.")
    if not _label(meta.get("name"), 80) or not _label(meta.get("version"), 32):
        raise ValueError("The manifest needs a name and version.")
    domains = meta.get("domains")
    if (
        not isinstance(domains, list) or not 1 <= len(domains) <= 32
        or any(not isinstance(domain, str) or len(domain) > 253 or not DOMAIN.fullmatch(domain) for domain in domains)
        or len(set(domains)) != len(domains)
    ):
        raise ValueError("List the plugin's supported domains in its manifest.")
    if meta.get("platform") != target:
        raise ValueError(f"This plugin targets a different platform or architecture. This app needs {target}.")
    if not target.startswith("macos-") and any(
        member.filename.startswith("assets/") and (member.external_attr >> 16) & 0o111 for member in members
    ):
        raise ValueError("Executable assets are supported only on Mac.")
    python = f"{sys.version_info.major}.{sys.version_info.minor}"
    if meta.get("python") != python:
        raise ValueError(f"This app needs plugins compiled for Python {python}.")
    if meta.get("gallery_dl") != version.__version__:
        raise ValueError(f"This app needs plugins built for gallery-dl {version.__version__}.")
    with package.open("extractor.pyc") as bytecode:
        if bytecode.read(4) != importlib.util.MAGIC_NUMBER:
            raise ValueError("The extractor bytecode does not match this app's Python runtime.")
    return {key: meta[key] for key in ("id", "name", "version", "domains")}


class PluginStore:
    def __init__(self, directory, target=None, temporary=None):
        self.directory = Path(directory)
        self.target = target or f"macos-{platform.machine()}"
        self.temporary = temporary
        self.lock = threading.Lock()
        self.active = {}
        self.errors = {}
        self.restart_required = False

    def _path(self, key, enabled):
        if not isinstance(key, str) or not ID.fullmatch(key):
            raise ValueError("Invalid plugin ID.")
        return self.directory / (key + (ENABLED if enabled else DISABLED))

    def _installed(self):
        for path in sorted(self.directory.glob("*.gdlplugin")):
            enabled = path.name.endswith(ENABLED)
            if not enabled and not path.name.endswith(DISABLED):
                continue
            key = path.name.removesuffix(ENABLED if enabled else DISABLED)
            if ID.fullmatch(key):
                yield key, path, enabled

    def _inspect(self, path):
        if path.stat().st_size > MAX_PACKAGE:
            raise ValueError("The plugin file is larger than 256 MB.")
        with zipfile.ZipFile(path) as package:
            return inspect(package, self.target)

    def install(self, source):
        source = Path(source)
        if source.suffix.lower() != ".gdlplugin" or not source.is_file():
            raise ValueError("Choose a .gdlplugin file.")
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Validate and install the same local copy. Importing never executes plugin code.
        with tempfile.TemporaryDirectory(dir=self.directory) as folder:
            staged = Path(folder) / "import"
            total = 0
            with source.open("rb") as reader, staged.open("xb") as writer:
                while chunk := reader.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_PACKAGE:
                        raise ValueError("The plugin file is larger than 256 MB.")
                    writer.write(chunk)
            meta = self._inspect(staged)
            key = meta["id"]
            target = self._path(key, False)
            if target.exists() or self._path(key, True).exists():
                raise ValueError("A plugin with this ID is already installed. Remove it before importing a replacement.")
            os.replace(staged, target)

    def set_enabled(self, key, enabled):
        source = self._path(key, not enabled)
        target = self._path(key, enabled)
        if enabled and self._inspect(source)["id"] != key:
            raise ValueError("The installed filename and manifest ID do not match.")
        if target.exists():
            raise ValueError("Another installed plugin already uses this ID.")
        os.replace(source, target)
        self.restart_required = True

    def remove(self, key, enabled):
        self._path(key, enabled).unlink()
        if enabled or key in self.active:
            self.restart_required = True

    def list(self):
        result = []
        for key, path, enabled in self._installed():
            error = self.errors.get(key) if enabled else None
            try:
                meta = self._inspect(path)
                if meta["id"] != key:
                    raise ValueError("The installed filename and manifest ID do not match.")
            except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, NotImplementedError) as exc:
                meta = {"name": key, "version": "", "domains": []}
                error = str(exc)
            result.append({**meta, "id": key, "enabled": enabled, "active": key in self.active, "error": error})
        return {"plugins": result, "restart_required": self.restart_required}

    def activate(self):
        for key, path, enabled in self._installed():
            if not enabled:
                continue
            try:
                self._load(key, path)
            except (Exception, SystemExit) as exc:
                self.errors[key] = f"Could not load plugin: {exc or type(exc).__name__}"
                logging.getLogger(__name__).exception("Could not load an extractor plugin")

    def _load(self, key, path):
        # Give the built-in extractors precedence; an optional plugin extends, not replaces, upstream support.
        extractor.extractors()
        folder = tempfile.TemporaryDirectory(prefix="gdl-plugin-", dir=self.temporary)
        root = Path(folder.name)
        module_name = "_gdl_plugin_" + key
        try:
            with zipfile.ZipFile(path) as package:
                if inspect(package, self.target)["id"] != key:
                    raise ValueError("The installed filename and manifest ID do not match.")
                for member in package.infolist():
                    if member.filename == "manifest.json":
                        continue
                    target = root / member.filename
                    target.parent.mkdir(exist_ok=True)
                    with package.open(member) as reader, target.open("xb") as writer:
                        shutil.copyfileobj(reader, writer)
                    if member.filename.startswith("assets/") and (member.external_attr >> 16) & 0o100:
                        target.chmod(0o700)
            loader = importlib.machinery.SourcelessFileLoader(module_name, str(root / "extractor.pyc"))
            spec = importlib.util.spec_from_loader(module_name, loader)
            module = importlib.util.module_from_spec(spec)
            sys.modules[module_name] = module
            loader.exec_module(module)
            classes = getattr(module, "EXTRACTORS", ())
            if not isinstance(classes, (tuple, list)) or not classes:
                raise ValueError("The plugin does not export any extractor classes.")
            for cls in classes:
                if not isinstance(cls, type) or not issubclass(cls, Extractor) or cls.__module__ != module_name:
                    raise ValueError("The plugin exports an invalid extractor class.")
                cls.pattern = text.re_compile(cls.pattern) if isinstance(cls.pattern, str) else cls.pattern
                if not isinstance(cls.pattern, re.Pattern) or not isinstance(cls.pattern.pattern, str):
                    raise ValueError("The extractor does not have a valid URL pattern.")
            for cls in classes:
                extractor.add(cls)
        except BaseException:
            sys.modules.pop(module_name, None)
            folder.cleanup()
            raise
        self.active[key] = folder


_store = None


def start(arg):
    global _store
    args = json.loads(arg)
    _store = PluginStore(args["directory"], args.get("target"), args.get("temporary"))
    _store.activate()
    return "{}"


def command(arg):
    if _store is None:
        return json.dumps({"error": "Extractor plugins are unavailable."})
    request = json.loads(arg)
    with _store.lock:
        try:
            action = request["action"]
            if action == "import":
                _store.install(request["path"])
            elif action == "enable":
                _store.set_enabled(request["id"], True)
            elif action == "disable":
                _store.set_enabled(request["id"], False)
            elif action == "remove":
                _store.remove(request["id"], request["enabled"])
            elif action != "list":
                raise ValueError("Unknown plugin action.")
            return json.dumps(_store.list())
        except Exception as exc:
            return json.dumps({"error": str(exc) or type(exc).__name__})

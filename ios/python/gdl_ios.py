"""Entry points the iOS app calls. Every function takes and returns a JSON string.

The app runs download() on one thread and polls poll() from another, so progress needs no
Python-to-Swift callback. The listener below is what gdl_bridge.py expects from the host app.
"""
import json
import logging
import threading

import gdl_bridge
from gdl_plugins import command as plugins, start as start_plugins

_lock = threading.Lock()
# Runs a cancel was asked for, and the run that is going now. A cancel names its run, so one that arrives a moment before
# its download starts still stops it, and one that arrives after it ended cannot stop the next.
_cancelled = set()
_current = {"run": None}
_progress = {"handled": 0, "total": 0, "percent": -1, "name": ""}
_files = []


class _Listener:
    def onProgress(self, handled, total, percent, name):
        with _lock:
            _progress.update(handled=handled, total=total, percent=percent, name=name)

    def onFile(self, path, url):
        with _lock:
            _files.append({"path": path, "url": url})

    def onSkipped(self, name, url, folder):
        with _lock:
            _files.append({"skipped": name, "url": url, "folder": folder})

    def isCancelled(self):
        with _lock:
            return _current["run"] in _cancelled


def info(_arg):
    return json.dumps({"gallery_dl": gdl_bridge.gallery_dl_version()})


def check(arg):
    url = json.loads(arg)["url"]
    return json.dumps({"supported": gdl_bridge.is_supported(url), "site": gdl_bridge.site_of(url)})


def preview(arg):
    """The folder gallery-dl would save the first file of a link in, found without downloading anything."""
    args = json.loads(arg)
    try:
        return json.dumps({"folder": gdl_bridge.preview_folder(args["url"], json.dumps(args.get("options", {})))})
    except Exception:
        return json.dumps({"folder": ""})


def resolve(arg):
    """The address each named file of a link has now, found without downloading anything."""
    args = json.loads(arg)
    try:
        return json.dumps({"files": json.loads(gdl_bridge.resolve_files(
            args["url"], json.dumps(args.get("names", [])), json.dumps(args.get("options", {}))))})
    except Exception:
        return json.dumps({"files": {}})


def cancel_resolve(_arg):
    gdl_bridge.cancel_resolve()
    return "{}"


def download(arg):
    args = json.loads(arg)
    run = args.get("run")
    with _lock:
        _current["run"] = run
        _cancelled.intersection_update({run})
        _progress.update(handled=0, total=0, percent=-1, name="")
        _files.clear()
    try:
        return gdl_bridge.download(
            args["url"], args["base_dir"], args["archive_path"], json.dumps(args.get("options", {})), _Listener(),
            json.dumps(args.get("known", [])),
        )
    except Exception as exc:
        # The person gets the reason, not the stack: the stack names paths into the app's own folders, which help
        # nobody reading the error on screen. It still goes to the log, where a bug report can be read.
        logging.getLogger(__name__).exception("The download failed")
        return json.dumps({"status": -1, "failed": 0, "errors": [str(exc) or type(exc).__name__]})


def poll(_arg):
    """Current progress plus the files saved since the last poll."""
    with _lock:
        out = dict(_progress)
        out["files"] = list(_files)
        _files.clear()
    return json.dumps(out)


def cancel(arg):
    run = json.loads(arg or "{}").get("run")
    with _lock:
        _cancelled.add(_current["run"] if run is None else run)
    return "{}"

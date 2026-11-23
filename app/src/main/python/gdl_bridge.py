"""Bridge between the Kotlin app and gallery-dl, run in-process through Chaquopy."""
import errno
import json
import logging
import os
import re
import threading
import time
import unicodedata
from urllib.parse import unquote, urlsplit

from gallery_dl import config, exception, extractor, job, output
from gallery_dl.extractor import common

def is_supported(url):
    """True if gallery-dl has an extractor that matches the URL."""
    try:
        return extractor.find(url) is not None
    except Exception:
        return False


def gallery_dl_version():
    from gallery_dl import version
    return version.__version__


def site_of(url):
    """The gallery-dl category (the website) behind a link, or "" if unknown."""
    try:
        extr = extractor.find(url)
        return (extr.category or "") if extr is not None else ""
    except Exception:
        return ""


_NOTHING_LISTED = (
    "gallery-dl listed no files for this link. The website returned nothing for it, which usually means a login is needed "
    "(import your cookies in Settings), or the link is empty or has been removed."
)

_NO_ADDRESS = re.compile(r"^'([^']*):' URLs are not supported/enabled$")


def _friendly(message):
    """gallery-dl's wording for a file that has no usable address, put plainly."""
    found = _NO_ADDRESS.match(message)
    if not found:
        return message
    if not found.group(1):
        return ("Some files have no download address. The website does not give one for them, which is usual for locked, "
                "removed or members-only files. If a login is needed, import your cookies in Settings.")
    return f"Files with '{found.group(1)}:' addresses cannot be downloaded by this app."


class _ErrorCollector(logging.Handler):
    """Collects the errors gallery-dl logs, each distinct message once.

    The first ones are kept, and the most recent ones too: what ended a link is logged last, and with many failed files
    it would otherwise be cut off and never shown.
    """

    FIRST = 12
    LAST = 8

    def __init__(self):
        super().__init__(logging.WARNING)
        self._seen = set()
        self._first = []
        self._last = []
        # Warnings are kept apart: they are not failures, but when a link lists nothing they say why.
        self.warnings = []

    def emit(self, record):
        try:
            message = _friendly(record.getMessage())
        except Exception:
            return
        if record.levelno < logging.ERROR:
            if message not in self.warnings and len(self.warnings) < 5:
                self.warnings.append(message)
            return
        if message in self._seen:
            return
        self._seen.add(message)
        if len(self._first) < self.FIRST:
            self._first.append(message)
        else:
            self._last.append(message)
            del self._last[:-self.LAST]

    @property
    def messages(self):
        return self._first + self._last


PROGRESS_INTERVAL = 0.25  # seconds between progress updates sent to the app


def _normal(name):
    """A file name in one fixed spelling, so the same accented or CJK name compares equal whichever way it was stored."""
    return unicodedata.normalize("NFC", name)

_original_request = common.Extractor.request


def _cancellable_request(self, *args, **kwargs):
    """Every web request an extractor makes first checks for a cancel.

    A link that expands into hundreds of pages or sub-links would otherwise keep requesting them, one after another, long
    after the user pressed stop: only the file transfers themselves looked at the cancel flag.
    """
    # The listener of the job this extractor belongs to (see _ReportingJob): a cancel stops that job's requests only, not
    # those of a preview or a check that happens to run at the same time, nor of the next download.
    listener = getattr(self, "_gdl_listener", None)
    if listener is not None and listener.isCancelled():
        raise exception.StopExtraction()
    return _original_request(self, *args, **kwargs)


common.Extractor.request = _cancellable_request


class _ProgressOutput(output.NullOutput):
    """Replaces gallery-dl's terminal output so download progress reaches the app."""

    def __init__(self, owner):
        self._owner = owner

    def start(self, path):
        self._owner.file_started(path)

    def success(self, path):
        self._owner.file_progress(100, 100)

    def progress(self, bytes_total, bytes_downloaded, bytes_per_second):
        # gallery-dl reports progress for every chunk it receives, so this is also the moment to honour a cancel:
        # stopping here ends a large file part-way instead of waiting for it to finish.
        if self._owner.is_cancelled():
            raise exception.StopExtraction()
        self._owner.file_progress(bytes_total, bytes_downloaded)


class _ReportingJob(job.DownloadJob):
    """DownloadJob that tells the Kotlin listener about every file as it lands on disk.

    gallery-dl creates child jobs with ``self.__class__(extractor, parent)`` when a link expands into
    other links (profiles, threads, ...), so the constructor must accept that signature and share
    the listener and the failure counter with the parent.
    """

    def __init__(self, url, parent=None, listener=None, known=None, known_urls=None):
        super().__init__(url, parent)
        self._listener = listener if listener is not None else parent._listener
        if self.extractor is not None:
            self.extractor._gdl_listener = self._listener
        # Names and direct addresses of files the library already has for this link. Removing a link and adding it again
        # leaves no archive, so without this every file would be fetched and saved a second time. The name decides: some
        # websites give a different address for the same file on every run (a fresh signature in it), the name does not.
        # The address is a second check, as it stays the same when a name that carries a position number changes.
        self._known = known if known is not None else (parent._known if parent is not None else frozenset())
        self._known_urls = known_urls if known_urls is not None else (parent._known_urls if parent is not None else frozenset())
        self._stats = parent._stats if parent is not None else {
            "failed": 0,        # files that could not be downloaded
            "handled": 0,       # files seen so far, including skipped ones
            "count": 0,         # size of the album, when the site reports one
            "count_ok": True,   # False once the reported count stops being trustworthy
            "new": 0,           # files saved by this run
            "seen": set(),      # addresses already met in this run, so one address is never saved twice
            "known": 0,         # files skipped because the library already has them
            "name": "",
            "percent": -1,
            "last_emit": 0.0,
        }
        if parent is not None:
            # A link that expands into other links has no single total.
            self._stats["count_ok"] = False
        self._skipped = False
        self._last_path = None
        self.out = _ProgressOutput(self)

    @property
    def failed(self):
        return self._stats["failed"]

    # --- progress reporting -------------------------------------------------

    def _album_count(self):
        """Total files in this link, or 0 when unknown or unreliable."""
        stats = self._stats
        if not stats["count_ok"] or stats["count"] <= 0 or stats["handled"] > stats["count"]:
            return 0
        return stats["count"]

    def _note_url(self, kwdict):
        stats = self._stats
        stats["handled"] += 1
        stats["percent"] = -1
        count = kwdict.get("count")
        if isinstance(count, int) and not isinstance(count, bool) and count > 0:
            if stats["count"] == 0:
                stats["count"] = count
            elif stats["count"] != count:
                stats["count_ok"] = False   # per-post counts, e.g. a profile: not an album total

    def _emit(self, force=False):
        stats = self._stats
        now = time.monotonic()
        if not force and now - stats["last_emit"] < PROGRESS_INTERVAL:
            return
        stats["last_emit"] = now
        self._listener.onProgress(stats["handled"], self._album_count(), stats["percent"], stats["name"])

    def is_cancelled(self):
        return bool(self._listener.isCancelled())

    def file_started(self, path):
        self._stats["name"] = os.path.basename(path)
        self._stats["percent"] = -1
        self._emit(force=True)

    def file_progress(self, bytes_total, bytes_downloaded):
        stats = self._stats
        if bytes_total and bytes_total > 0:
            stats["percent"] = max(0, min(100, int(bytes_downloaded * 100 / bytes_total)))
        else:
            stats["percent"] = -1
        self._emit(force=stats["percent"] == 100)

    def handle_skip(self):
        self._skipped = True
        super().handle_skip()

    def _is_known(self, url, kwdict):
        """True when the library already has this file, by the name it would be saved as or by its direct address."""
        try:
            self.pathfmt.set_filename(kwdict)
            # The file name is only worked out by build_path(); set_filename() leaves it empty.
            self.pathfmt.build_path()
        except Exception:
            return url in self._known_urls
        name = getattr(self.pathfmt, "filename", None)
        return url in self._known_urls or (name is not None and _normal(name) in self._known)

    def _folder_name(self):
        """The innermost folder gallery-dl would save the current file in, or "" when it would sit directly in the root."""
        try:
            directory = os.path.normpath(getattr(self.pathfmt, "realdirectory", "") or "")
            base = os.path.normpath(os.path.expanduser(config.get(("extractor",), "base-directory") or ""))
            if not directory or directory == "." or directory == base:
                return ""
            return os.path.basename(directory)
        except Exception:
            return ""

    def _report_skipped(self, url):
        self._stats["known"] += 1
        name = getattr(self.pathfmt, "filename", None)
        if name:
            # The folder comes along so a file saved under an older folder name can be moved to the one gallery-dl uses now.
            self._listener.onSkipped(name, url, self._folder_name())

    def _file_failed(self, url, exc):
        """One file could not be handled, whatever the reason: it is counted and reported, and the link carries on."""
        name = getattr(self.pathfmt, "filename", None) or url or "a file"
        logging.getLogger("gallery-dl").error("Failed to download %s (%s: %s)", name, type(exc).__name__, exc)
        self._stats["failed"] += 1

    def handle_queue(self, url, kwdict):
        # A sub-link that is cancelled ends only its own job; without this the parent carried on with the next one.
        if self._listener.isCancelled():
            raise exception.StopExtraction()
        super().handle_queue(url, kwdict)

    def handle_url(self, url, kwdict):
        if self._listener.isCancelled():
            raise exception.StopExtraction()
        self._skipped = False
        self._note_url(kwdict)
        # A file the library already has downloads nothing and so reports nothing of its own; this is what lets the app show
        # how many files a refresh has checked so far (throttled, so it costs next to nothing).
        self._emit()
        if not isinstance(url, str):
            # A file with no address at all (None, not even an empty one) would make gallery-dl stop the whole link at this
            # file. Treated as an empty address, it is reported and counted as failed like any other, and the rest carry on.
            url = ""
        if url:
            # The same address again in one run (a post that lists a file twice, say): it is one file.
            seen = self._stats["seen"]
            if url in seen:
                return
            seen.add(url)
        if (self._known or self._known_urls) and self._is_known(url, kwdict):
            return self._report_skipped(url)
        try:
            super().handle_url(url, kwdict)
        except exception.ControlException:
            # Stop, terminate and restart signals (a cancel, a pause) must still end the link.
            raise
        except OSError as exc:
            if exc.errno == errno.ENOSPC:
                raise
            return self._file_failed(url, exc)
        except Exception as exc:
            return self._file_failed(url, exc)
        path = self.pathfmt.realpath
        if path and path != self._last_path and os.path.isfile(path):
            self._last_path = path
            self._stats["new"] += 1
            self._listener.onFile(path, url)
        elif self._skipped:
            # Already downloaded earlier, so nothing lands on disk. The library still learns this file's current address,
            # which is how a refreshed link gives every file a fresh one.
            self._report_skipped(url)
        else:
            # Neither saved nor skipped: gallery-dl logged "Failed to download".
            self._stats["failed"] += 1


# The only gallery-dl options the app lets the user change; anything else in the JSON is ignored.
USER_OPTIONS = {"api", "sleep-request", "sleep", "retries", "timeout", "cookies"}

# Options that gallery-dl reads in the downloader section as well as the extractor section.
BOTH_SECTIONS = {"retries", "timeout"}


def _apply_options(options_json):
    for key, value in json.loads(options_json or "{}").items():
        if key in USER_OPTIONS:
            config.set(("extractor",), key, value)
            if key in BOTH_SECTIONS:
                config.set(("downloader",), key, value)


class _NoListener:
    """A listener that ignores everything, for runs that only look and save nothing."""

    def __getattr__(self, _name):
        return lambda *args, **kwargs: None

    def isCancelled(self):
        return False


class _FolderProbe(_ReportingJob):
    """Walks a link up to its first file and notes the folder gallery-dl would save it in; downloads nothing."""

    def _found(self):
        return self._stats.get("probe") is not None

    def handle_queue(self, url, kwdict):
        if self._found() or time.monotonic() > self._stats["deadline"]:
            raise exception.StopExtraction()
        super().handle_queue(url, kwdict)

    def handle_url(self, url, kwdict):
        if self._found() or time.monotonic() > self._stats["deadline"]:
            raise exception.StopExtraction()
        self.pathfmt.set_filename(kwdict)
        self.pathfmt.build_path()
        self._stats["probe"] = self._folder_name()
        raise exception.StopExtraction()


# How long a walk through a link may go without listing a single file, and the longest it may run in all.
RESOLVE_IDLE_SECONDS = 180
RESOLVE_MAX_SECONDS = 1800

# Bumped by cancel_resolve(), so an import the user stops does not wait for a long walk through a link to end. A walk
# remembers the number it started with: a cancel aimed at it stops it, and one aimed at an earlier walk cannot stop a
# later one, which would otherwise end the next import without being asked.
_resolve_cancel = {"generation": 0}
_resolve_lock = threading.Lock()


def cancel_resolve():
    with _resolve_lock:
        _resolve_cancel["generation"] += 1


def _resolve_cancelled(generation):
    with _resolve_lock:
        return _resolve_cancel["generation"] != generation


class _ResolveProbe(_ReportingJob):
    """Walks a link and notes the address the files asked for have now; downloads nothing."""

    def _finished(self):
        stats = self._stats
        now = time.monotonic()
        return _resolve_cancelled(stats["generation"]) or now > stats["deadline"] or now > stats["hard"] or len(stats["found"]) >= len(stats["wanted"])

    def _alive(self):
        # Every file the link lists is progress, so a long link (one request per file, with the user's waits between them)
        # is not cut off while it is still being read; only a link that goes quiet for a while is.
        self._stats["deadline"] = time.monotonic() + self._stats["idle"]

    def handle_queue(self, url, kwdict):
        if self._finished():
            raise exception.StopExtraction()
        self._alive()
        super().handle_queue(url, kwdict)

    def handle_url(self, url, kwdict):
        if self._finished():
            raise exception.StopExtraction()
        self._alive()
        try:
            self.pathfmt.set_filename(kwdict)
            self.pathfmt.build_path()
        except Exception:
            return
        if isinstance(url, str) and url:
            # By the name the file is saved under, or by the name its address ends in (what an older sheet, which has no
            # name column, can only go by).
            ends = _normal(unquote(urlsplit(url).path.rsplit("/", 1)[-1]))
            for name in (_normal(getattr(self.pathfmt, "filename", None) or ""), ends):
                if name in self._stats["wanted"]:
                    self._stats["found"][name] = url
        if self._finished():
            raise exception.StopExtraction()


def resolve_files(url, names_json, options_json="{}", idle_seconds=None):
    """The address each named file of a link has now, as JSON {"name": "address"}; names the link no longer lists are left out.

    A saved address can be dead: some sites sign them, and the signature runs out. The link itself always knows
    the current one. Safe to call while a download runs, like preview_folder().
    """
    # The walk stops when this number changes: a cancel aimed at it counts, one aimed at an earlier walk does not.
    with _resolve_lock:
        walk = _resolve_cancel["generation"]
    try:
        config.set(("extractor",), "path-restrict", "windows")
        _apply_options(options_json)
        probe = _ResolveProbe(url, listener=_NoListener())
        probe._stats["generation"] = walk
        probe._stats["wanted"] = frozenset(_normal(n) for n in json.loads(names_json))
        probe._stats["found"] = {}
        # Some sites cost a request per file, and the user's waits between requests
        # apply, so a long link takes long: it is only given up when it goes quiet (see _alive) or runs out of total time.
        probe._stats["idle"] = idle_seconds or RESOLVE_IDLE_SECONDS
        probe._stats["deadline"] = time.monotonic() + probe._stats["idle"]
        probe._stats["hard"] = time.monotonic() + RESOLVE_MAX_SECONDS
        probe.run()
        return json.dumps(probe._stats["found"])
    except Exception:
        return "{}"


def preview_folder(url, options_json="{}", seconds=25):
    """The folder gallery-dl would save the first file of a link in ("" when unknown or at the root).

    Safe to call while a download runs: gallery-dl's settings are not cleared, and only the options the user
    chose are set again to the values a download sets.
    """
    try:
        config.set(("extractor",), "path-restrict", "windows")
        _apply_options(options_json)
        probe = _FolderProbe(url, listener=_NoListener())
        probe._stats["deadline"] = time.monotonic() + seconds
        probe.run()
        return probe._stats.get("probe") or ""
    except Exception:
        return ""


def download(url, base_dir, archive_path, options_json, listener, known_json="[]"):
    """Run gallery-dl for one URL.

    What counts as already saved is what the library holds (``known_json``), never a record kept apart from it: a record
    of its own would still say "downloaded" after the files were deleted, and a refresh would then fetch nothing. A file
    is the same file when its name is the same (or its address, as a second check).
    ``archive_path`` is only used to remove an old record left by an earlier version.
    options_json holds the user's switched-on options, for example {"sleep": [1.0, 3.0]}.
    Returns a JSON string: {"status": int, "failed": int, "errors": [str]}.
    """
    # Options the user has switched off since the last run are removed. The ones still on stay as they are: a preview or a
    # favorites check may be reading them right now, and wiping the whole configuration under it would break that run.
    switched_on = json.loads(options_json or "{}")
    for key in USER_OPTIONS - set(switched_on):
        config.unset(("extractor",), key)
        if key in BOTH_SECTIONS:
            config.unset(("downloader",), key)
    config.set(("extractor",), "base-directory", base_dir)
    # gallery-dl's own folders are kept: the app names each library folder after the innermost one a file lands in.
    if archive_path:
        for suffix in ("", "-wal", "-shm", "-journal"):
            try:
                os.remove(archive_path + suffix)
            except OSError:
                pass
    # Report progress as often as chunks arrive; _emit() throttles what is sent to the app.
    config.set(("downloader",), "progress", 0.0)
    # Shared storage is FAT-like: replace characters such as : ? * " in file names.
    config.set(("extractor",), "path-restrict", "windows")
    _apply_options(options_json)

    collector = _ErrorCollector()
    root = logging.getLogger()
    root.addHandler(collector)
    known = json.loads(known_json or "[]")
    # A plain list is just names; the apps send {"names": [...], "urls": [...]}.
    names, urls = (known.get("names", []), known.get("urls", [])) if isinstance(known, dict) else (known, [])
    try:
        download_job = _ReportingJob(
            url, listener=listener, known=frozenset(_normal(n) for n in names), known_urls=frozenset(u for u in urls if u),
        )
        status = download_job.run()
    finally:
        root.removeHandler(collector)

    errors = collector.messages
    if download_job._stats["handled"] == 0 and not listener.isCancelled():
        # The website listed nothing for this link. That is not a clean "completed": say so, with what gallery-dl said.
        errors = [_NOTHING_LISTED] + collector.warnings + errors
        status = status or 1
    elif download_job.failed or status:
        # Says what the run found, so a link that ends early can be told apart from one the website only partly offers.
        stats = download_job._stats
        errors.insert(0, (
            f"gallery-dl listed {stats['handled']} files for this link: {stats['new']} saved now, "
            f"{stats['known']} already in your library, {download_job.failed} could not be downloaded."
        ))
    return json.dumps({
        "status": status,
        "failed": download_job.failed,
        "errors": errors,
    })

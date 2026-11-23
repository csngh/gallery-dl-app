# Extractor plugins

In **Settings → Extractor plugins**, import a `.gdlplugin` file. It is installed disabled so you can review its name, version and domains before enabling. Enable only files you trust: plugins run Python code with the same permissions as the app, with no separate plugin sandbox. The listed domains are informational, not a network allowlist.

After enabling, disabling, or removing an active plugin, restart the app:

- **Mac:** Quit with **⌘Q** and reopen. Closing the window is not enough.
- **Android and Android TV:** Wait for downloads to finish, then **Force stop** Gallery DL from its system app settings and reopen it.
- **iPhone:** Close Gallery DL in the app switcher and reopen it. External plugins are provided for this project's sideloaded iPhone build, not as an App Store distribution guarantee.

The app ships with no extra plugins. Installed packages stay in app-private storage and aren’t shared by Sync. Removing a plugin doesn’t delete its links or downloads. To update a plugin, remove the old package and import the new one. On Mac, if a plugin prevents the app from opening, move its `.enabled.gdlplugin` file out of `~/Library/Application Support/Gallery DL/Extractor Plugins/` before relaunching.

## Package format v1

A `.gdlplugin` is a ZIP containing `manifest.json`, `extractor.pyc`, and optionally files directly under `assets/`. It needs the gallery-dl version shown under **Settings → About** and a matching target:

| App | Manifest `platform` | Manifest `python` |
| --- | --- | --- |
| Mac (Apple silicon) | `macos-arm64` | `3.13` |
| Android phone | `android` | `3.12` |
| Android TV | `android` | `3.11` |
| iPhone | `ios` | `3.13` |

Example manifest:

```json
{
  "format": 1,
  "id": "example-gallery",
  "name": "Example Gallery",
  "version": "1.0.0",
  "domains": ["gallery.example"],
  "platform": "macos-arm64",
  "python": "3.13",
  "gallery_dl": "1.32.18"
}
```

The Python module exports an `EXTRACTORS` tuple of gallery-dl `Extractor` subclasses defined in that module:

```python
from gallery_dl import text
from gallery_dl.extractor.common import Extractor, Message


class ExampleExtractor(Extractor):
    category = "examplegallery"
    subcategory = "image"
    pattern = r"https://gallery\.example/picture/(\d+)/?$"
    directory_fmt = ("{category}",)
    filename_fmt = "{id}.{extension}"
    archive_fmt = "{id}"

    def items(self):
        image_id = self.groups[0]
        url = f"https://gallery.example/files/{image_id}.jpg"
        data = {"id": image_id}
        yield Message.Directory, "", data
        yield Message.Url, url, text.nameext_from_url(url, data)


EXTRACTORS = (ExampleExtractor,)
```

Compile with the target app's CPython version. This example builds the Mac variant:

```sh
python3.13 macos/scripts/pack_plugin.py extractor.py manifest.json example.gdlplugin
```

Run `--asset path/to/file` once for each optional asset. Code can locate its files at `Path(__file__).parent / "assets"`. Executable assets are Mac-only; Android and iPhone plugins must not require helper executables or native extensions. Packages are limited to 256 MiB compressed and unpacked. Upstream extractors keep precedence over plugins.

Keep private packages and their source outside the public checkout. Bytecode and ZIP packaging do not encrypt the site names or make the code impossible to inspect.

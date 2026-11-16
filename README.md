<div align="center">

<img src="docs/brand/logo-lockup.png" alt="Gallery DL App" width="560">

### A native Android and iPhone front end and download manager for [gallery-dl](https://github.com/mikf/gallery-dl)

gallery-dl runs *inside the app* (embedded Python), so there is no server, no backend and no account.<br>
Paste a link, press **Start**, and it becomes a folder you can browse and play, with live video previews.

https://github.com/user-attachments/assets/d28a3f1e-f986-4f31-a7db-4adbdf1fa871

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Android TV](https://img.shields.io/badge/Android%20TV-supported-4285F4?logo=googletv&logoColor=white)
![iOS 15+](https://img.shields.io/badge/iOS-15%2B-000000?logo=apple&logoColor=white)

</div>

## Highlights

- **Paste, press Start.** Links are checked against gallery-dl's supported sites. Nothing downloads until you say so, one link at a time, in the background.
- **A folder per link.** Named as gallery-dl names it, or by you. Files you already have are never saved twice.
- **Refresh without repeats.** Renew and *Check for new files* fetch only what the library does not have.
- **Live previews and a real player.** Every video loops silently in the library. The player scrubs, zooms, changes speed and locks the screen.
- **Favorites that stay.** Long-press a file and tap the star: a copy is kept inside the app, even after the link and folder are deleted.
- **Sync between devices.** Send links and favorites to another phone, TV or iPhone on the same Wi-Fi.
- **One design on every screen.** Android phone, Android TV and iPhone share one look. Dark theme, no account, nothing leaves the device but the downloads.

## Releases

Ready-made apps are on the [Releases page](https://github.com/csngh/gallery-dl-app/releases):

| File | For |
| --- | --- |
| `GalleryDL-App-Phone.apk` | Android phone |
| `GalleryDL-App-TV.apk` | Android TV and projectors |
| `GalleryDL-App-iPhone.ipa` | iPhone (unsigned; install with Sideloadly, AltStore or SideStore) |

**Install:** on Android, open the downloaded file and allow installs from your browser or files app when asked. On iPhone, open the `.ipa` in Sideloadly, AltStore or SideStore and sign in with your Apple ID. A free Apple ID works, but the app must be refreshed every 7 days.

## Known limitations

- Private or login-gated content needs a cookies.txt exported from your browser (Settings > Accounts); there is no in-app login.
- On iPhone, downloads run while the app is open and for a short while after; keep it open for long downloads.
- Large libraries take a while to prepare previews on a slow phone the first time; it keeps going in the background.

## Credits

- [gallery-dl](https://github.com/mikf/gallery-dl) by Mike Fährmann does the actual downloading.
- [Inter](https://rsms.me/inter/) font, licensed under the SIL OFL (see [docs/licenses](docs/licenses/Inter-OFL.txt)).

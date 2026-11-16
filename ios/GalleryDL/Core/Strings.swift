import Foundation

/// Every piece of text the app shows, copied from the Android app's strings.xml so both read the same.
enum S {
    static let appName = "Gallery DL App"
    static let navHome = "Home", navQueue = "Queue", navLibrary = "Library", navActivity = "Activity"

    static let exportFileName = "gallery-dl-links.txt"
    static let notifStorageTitle = "Downloads paused: storage almost full"
    static func notifStorageBody(_ percent: Int) -> String { "The storage is \(percent)% full. Free some space and downloads carry on by themselves." }
    static func storageWarning(_ percent: Int) -> String { "Storage is almost full (\(percent)%). Downloads are paused until some space is freed." }
    static let actionSkipBack = "Back 10 seconds", actionSkipForward = "Forward 10 seconds"
    static let actionLandscape = "Landscape", actionExitLandscape = "Portrait"
    static let actionDetails = "Details"
    static let detailsSourceLink = "Link you added", detailsFileAddress = "This file's own address"
    static let detailsFileAddressMissing = "Not saved: this file was downloaded before addresses were kept."
    static let detailsFileAddressNote = "Some sites give an address that stops working after a while."
    static let actionOpenWith = "Open in another app"
    static let actionShare = "Share"
    static let actionShareSave = "Share or save to Files"
    static let actionLoopOn = "Loop this video", actionLoopOff = "Stop looping"
    static let actionLock = "Lock touch", actionUnlock = "Unlock touch"
    /// Shown over a WebM video whose sound cannot be played. `format` is the file's own name for the sound format.
    static func soundUnsupported(_ format: String) -> String { "No sound: \(format) is not supported" }
    static let soundFailed = "No sound: the audio output failed"
    static let actionRenew = "Renew all links"
    static func actionRenewSite(_ site: String) -> String { "Renew the links of \(site)" }
    static let actionLinksMenu = "More options", actionExportLinks = "Export links", actionImportLinks = "Import links"
    static let exportEmpty = "There are no links to export yet."
    static let actionExportFavorites = "Export video links", exportFavoritesFileName = "favorite-videos.csv"
    static let exportFavoritesEmpty = "There are no favorite videos to export yet."
    static let actionImportFavorites = "Import favorite videos", importFavoritesTitle = "Importing favorite videos"
    static let importFavoritesNone = "No videos were found in this file. It should be a sheet saved with Export video links."
    static func importFavoritesAdded(_ n: Int) -> String { n == 1 ? "1 video added to Favorites" : "\(n) videos added to Favorites" }
    static func importFavoritesCopied(_ n: Int) -> String { "Copied from your library: \(n)" }
    static func importFavoritesDownloaded(_ n: Int) -> String { "Downloaded: \(n)" }
    static func importFavoritesDuplicates(_ n: Int) -> String { "Already in Favorites: \(n)" }
    static func importFavoritesFailed(_ n: Int) -> String { "Could not be fetched (the link may have expired): \(n)" }
    static let importFavoritesConfirmTitle = "Import favorite videos"
    static func importFavoritesConfirmBody(_ n: Int) -> String {
        "Add \(n) \(n == 1 ? "video" : "videos") from this file to Favorites? Ones already in Favorites are skipped, and ones that are not in your library are downloaded. It runs in the background, and you can stop it from Library or Home."
    }
    static let actionImportConfirm = "Import"
    static let importFavoritesStopping = "Stopping…", importFavoritesStopped = "Import stopped"
    static let importFavoritesNothingAdded = "Nothing was added."
    static let optionRetryPreviewsTitle = "Failed previews"
    static func optionRetryPreviewsBody(_ n: Int) -> String {
        n == 1 ? "1 video has no preview because it could not be read. Videos like this are not tried again on their own."
               : "\(n) videos have no preview because they could not be read. Videos like these are not tried again on their own."
    }
    static let optionRetryPreviewsNone = "Every video has a preview, or none has failed."
    static let actionRetryPreviews = "Retry failed previews"
    // Sync with nearby devices
    static let syncTitle = "Sync", actionSync = "Sync with nearby devices"
    static let syncIntro = "Gallery DL finds your other devices on the same Wi-Fi by itself. Press Sync on one and choose what to share, and the other asks whether to accept. Favorite videos are sent as files. Nothing is stored without that answer."
    static let syncThisDevice = "This device", syncDeviceName = "Device name"
    static func syncAddressLine(_ port: UInt16) -> String { "This device is listening on port \(port)." }
    static let syncNearby = "Nearby devices"
    static let syncNoDevices = "No other device found yet. Open Sync on the other device too, and check that both are on the same Wi-Fi. Press Search again, or add its address below."
    static let syncSend = "Sync", syncSearchAgain = "Search again"
    static let syncCouldNotReach = "Could not reach that device. Check that Sync is open on it and that both are on the same Wi-Fi."
    static let syncBadAddress = "Type the address as shown on the other device, for example 192.168.1.20:53318."
    static let syncTimeoutHint = "If that device runs a VPN or firewall app, exclude Gallery DL from it, or turn it off while syncing."
    static let syncCannotListen = "This device could not start listening on the network. Allow the local network in Settings, Privacy, Local Network, and open this screen again."
    static let syncAddByAddress = "Add by address", syncAddressField = "192.168.1.20:53318", syncAddAction = "Add"
    static func syncReceiving(_ name: String) -> String { "Receiving from \(name)" }
    static func syncSendingTo(_ name: String) -> String { "Sending to \(name)" }
    static let syncSaving = "Saving to Favorites"
    static func syncFileOf(_ n: Int, _ total: Int) -> String { "File \(n) of \(total)" }
    static func syncSending(_ name: String) -> String { "Asking \(name)" }
    static let syncStopped = "Stopped."
    static func syncAllHere(_ n: Int) -> String { "Nothing new: all \(n) favorite videos were already here, so none were sent." }
    static func syncAlreadyHere(_ n: Int) -> String { "\(n) were already here and were not sent." }
    static func syncOtherHasAll(_ name: String) -> String { "\(name) already has everything you offered, so nothing was sent." }
    static let syncNothingToSend = "There is nothing selected to share."
    static func syncSent(_ name: String) -> String { "Synced with \(name)." }
    static func syncDeclined(_ name: String) -> String { "\(name) declined." }
    static func syncBusy(_ name: String) -> String { "\(name) is busy with another transfer. Try again in a moment." }
    static func syncReceived(links: Int, favorites: Int) -> String {
        if links == 0 && favorites == 0 { return "Nothing new to receive." }
        var parts: [String] = []
        if links > 0 { parts.append(links == 1 ? "1 link added to Home" : "\(links) links added to Home") }
        if favorites > 0 { parts.append(favorites == 1 ? "1 favorite video saved" : "\(favorites) favorite videos saved") }
        return parts.joined(separator: ", ") + "."
    }
    static func syncLeftOut(_ n: Int, sent: Bool) -> String { sent ? (n == 1 ? "1 was not sent:" : "\(n) were not sent:") : (n == 1 ? "1 was left out:" : "\(n) were left out:") }
    static func syncOfferTitle(_ name: String) -> String { "Sync from \(name)?" }
    static func syncOfferBody(links: Int, videos: Int, bytes: Int64) -> String {
        let parts = [links > 0 ? "\(links) \(links == 1 ? "link" : "links")" : nil, videos > 0 ? "\(videos) \(videos == 1 ? "favorite" : "favorites")" : nil].compactMap { $0 }
        return parts.joined(separator: " and ") + ".\nTotal size: \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))."
    }
    static let importLookingUp = "Looking up the current address…"
    static func syncChooseTitle(_ name: String) -> String { "Sync with \(name)" }
    static let syncChooseWhat = "Choose what to share. Favorite videos are sent as files, so they can be large."
    static func syncChooseLinks(_ n: Int) -> String { "Links (\(n))" }
    static func syncChooseFavorites(_ n: Int, _ bytes: Int64) -> String { "Favorites (\(n), \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)))" }
    static let syncChooseNothing = "There is nothing to share yet."
    static let syncAccept = "Accept", syncDecline = "Not now"
    static let transferFailed = "Unable to use this file. Please try another one."
    static let importTitle = "Importing links", importDoneTitle = "Import finished"
    static let importNone = "No links were found in this file. It should have one link per line."
    static func importProgress(_ done: Int, _ total: Int) -> String { "\(done) of \(total) checked" }
    static func importAdded(_ n: Int) -> String { n == 1 ? "1 link was added. Press Start to download it." : "\(n) links were added. Press Start to download them." }
    static func importDuplicates(_ n: Int) -> String { "Already in your list: \(n)" }
    static func importUnsupported(_ n: Int) -> String { "On unsupported websites: \(n)" }
    static func importNotLinks(_ n: Int) -> String { "Lines that were not links: \(n)" }
    static let actionOk = "OK"

    static let urlLabel = "Paste a link"
    static let actionAdd = "Add"
    static let errorUrlMalformed = "Please enter a valid http or https link."
    static let errorUrlUnsupported = "This website is not supported."

    static let statusQueued = "Queued", statusDownloading = "Downloading", statusCompleted = "Completed"
    static let statusFailed = "Failed", statusCancelled = "Cancelled", statusPartial = "Completed with errors"
    static let statusReady = "Ready to start"
    static func filesSaved(_ n: Int) -> String { n == 1 ? "\(n) file saved" : "\(n) files saved" }
    static func filesFailed(_ n: Int) -> String { n == 1 ? "\(n) file failed" : "\(n) files failed" }
    static let partialErrors = "Some files could not be downloaded."
    static let errorNoFiles = "Unable to download any files from this link."

    static let actionCancel = "Cancel", actionRetry = "Retry", actionRemove = "Remove"
    static let actionCheckNew = "Check for new files"
    static let actionClearFinished = "Clear finished"

    static let queueEmptyTitle = "The queue is empty"
    static let queueEmptyBody = "Please paste a link above to start downloading."

    static let noAppToOpen = "Unable to find an app that can open this file."

    static let downloadLocation = "Saved inside the app"
    static let downloadLocationHint = "Each link is saved in its own folder, and appears in your library."
    static let actionClose = "Close"

    static let libraryEmptyTitle = "Your library is empty"
    static let libraryEmptyBody = "Finished downloads appear here, with one folder for each link."
    static let libraryNoResults = "No results found"
    static let libraryNoResultsBody = "Please try a different search term."
    static let searchLabel = "Search"
    static let actionBack = "Back", actionOpen = "Open", actionDelete = "Delete"
    static func folderItems(_ n: Int) -> String { n == 1 ? "\(n) item" : "\(n) items" }
    static func selectedCount(_ n: Int) -> String { "\(n) selected" }
    static let actionTagFolder = "Tag folder", actionDeleteFolder = "Delete folder", actionRemoveTag = "Remove tag"
    static let tagFolderTitle = "Tag this folder", tagLabel = "Tag"
    static let startFolderTitle = "Folder for this link"
    static let startFolderMessage = "The files are saved in this folder. Keep the name or type another one; the link then always saves there, also when you refresh it."
    static let startFolderLabel = "Folder name"
    static let startFolderLooking = "Looking up the folder name\u{2026}"
    static let startFolderUnknown = "The name could not be found in advance. Leave this empty to use the one gallery-dl gives, or type your own."
    static let startFolderExists = "A folder with this name already exists. It belongs to:"
    static func startFolderMore(_ n: Int) -> String { "and \(n) more" }
    static let actionFavorite = "Add to Favorites"
    static func favoritesAdded(_ n: Int) -> String { n == 1 ? "1 file added to Favorites" : "\(n) files added to Favorites" }
    static let favoritesNoneAdded = "Already in Favorites"
    static let actionColumns = "Columns"
    static let actionViewOptions = "Columns and order"
    static let sortNewest = "Newest first"
    static let sortOldest = "Oldest first"
    static func columnsOption(_ n: Int) -> String { "\(n) columns" }
    static let deleteFolderTitle = "Delete this folder?"
    static func deleteFolderMessage(_ n: Int) -> String {
        n == 1 ? "This will permanently delete \(n) file saved from this link from your phone."
            : "This will permanently delete all \(n) files saved from this link from your phone."
    }
    static let deleteSelectedTitle = "Delete the selected items?"
    static func deleteSelectedMessage(_ n: Int) -> String {
        n == 1 ? "This will permanently delete \(n) file from your phone." : "This will permanently delete \(n) files from your phone."
    }

    static let statLinks = "Links", statCompleted = "Completed", statPartial = "Partial", statFailed = "Failed"
    static let statFilesSaved = "Files saved", statFilesFailed = "Files failed"
    static let filterAll = "All"
    static func actionRetryFailed(_ n: Int) -> String { "Retry failed (\(n))" }
    static let activityEmptyTitle = "No activity yet"
    static let activityEmptyBody = "Results for every link that you download will appear here."

    static let actionExpand = "Expand", actionCollapse = "Collapse"
    static let detailFolder = "Folder", detailAdded = "Added", detailFinished = "Finished"

    static func progressPercent(_ n: Int) -> String { "\(n)%" }
    static func progressFileOf(_ i: Int, _ total: Int) -> String { "File \(i) of \(total)" }
    static func progressFile(_ i: Int) -> String { "File \(i)" }
    static func progressChecked(_ n: Int) -> String { n == 1 ? "Checked 1 file so far" : "Checked \(n) files so far" }
    static func siteChecked(_ n: Int) -> String { n == 1 ? "Checked 1 file…" : "Checked \(n) files…" }

    static let confirmQuestion = "Do you want to continue?"
    static let deleteItemTitle = "Delete this file?"
    static let deleteItemMessage = "This will permanently delete this file from your phone."
    static let clearQueueTitle = "Clear the finished links from the Queue?"
    static let clearQueueMessage = "This only tidies the Queue. The links stay on Home, so you can refresh them again, and the downloaded files stay in your library."
    static let clearFinishedTitle = "Clear the finished links?"
    static let clearFinishedMessage = "This will remove all finished links from the queue and from the activity history. The downloaded files will remain in your library."
    static let actionClear = "Clear"
    static let removeLinkTitle = "Remove this link?"
    static let removeLinkMessage = "This will remove this link from the queue and from the activity history. The downloaded files will remain in your library."
    static let cancelDownloadTitle = "Cancel this download?"
    static let cancelDownloadMessage = "This will stop the download of this link. Files that have already been saved will remain in your library."
    static let actionCancelDownload = "Cancel download"
    static let actionKeepDownloading = "Keep downloading"

    static let actionMore = "More options"

    static let homeEmptyTitle = "No websites yet"
    static let homeEmptyBody = "Please paste a link above. Links from the same website are grouped here."
    static func siteLinks(_ n: Int) -> String { n == 1 ? "\(n) link" : "\(n) links" }
    static func siteInProgress(_ n: Int) -> String { "\(n) in progress" }
    static func siteAttention(_ n: Int) -> String { n == 1 ? "\(n) needs attention" : "\(n) need attention" }
    static func siteReady(_ n: Int) -> String { "\(n) ready to start" }
    static func siteDownloadingPercent(_ n: Int) -> String { "Downloading, \(n)%" }

    static let actionEdit = "Edit link", editLinkTitle = "Edit link", editLinkLabel = "Link"
    static let actionSaveRetry = "Save and retry", actionSave = "Save"

    static let actionViewLink = "View full link", fullLinkTitle = "Full link", actionCopy = "Copy"

    static let actionStart = "Start"
    static func actionStartAll(_ n: Int) -> String { "Start all (\(n))" }
    static func actionStopAll(_ n: Int) -> String { "Stop all (\(n))" }

    static let settingsTitle = "Settings"
    static let settingsPacingTitle = "Pacing"
    static let settingsPacingBody = "Slow downloads down so that websites are less likely to block you."
    static let optionSleepRequestTitle = "Wait between requests"
    static let optionSleepRequestBody = "Pause between requests to a website. Enter seconds as one number or as a range, for example 2.0-4.0."
    static let optionSleepTitle = "Wait between downloads"
    static let optionSleepBody = "Pause before each file is downloaded. Enter seconds as one number or as a range, for example 2.0-4.0."
    static let optionSecondsLabel = "Seconds"
    static let errorDuration = "Please enter seconds from 0 to 300, as one number or a range such as 2.0-4.0."
    static let settingsSitesTitle = "Websites"
    static let settingsSitesBody = "Choices that only some websites offer."
    static let optionApiRestTitle = "Use the REST API"
    static let optionApiRestBody = "Asks websites that offer more than one API to use the REST API. Only some websites, such as Twitter, give this choice."
    static let settingsApplyNote = "Changes apply to the next download that starts."

    static let settingsLocationTitle = "Download location"
    static let settingsLocationBody = "Where finished files are saved on your phone."
    static let settingsConnectionTitle = "Connection"
    static let settingsConnectionBody = "How downloads behave on slow or limited connections."
    static let optionWifiTitle = "Wi-Fi only"
    static let optionWifiBody = "Wait for Wi-Fi and never use mobile data. Downloads in the queue start when Wi-Fi is available."
    static let optionRetriesTitle = "Retries"
    static let optionRetriesBody = "How many times a file is tried again after it fails. The default is 4."
    static let optionRetriesLabel = "Number of retries"
    static let errorRetries = "Please enter a whole number from 0 to 20."
    static let optionTimeoutTitle = "Timeout"
    static let optionTimeoutBody = "How many seconds to wait for a slow server before giving up. The default is 30."
    static let errorTimeout = "Please enter seconds from 1 to 300."
    static let settingsAccountsTitle = "Accounts"
    static let settingsAccountsBody = "Download content that needs you to be signed in."
    static let optionCookiesTitle = "Use cookies"
    static let optionCookiesBody = "Sends your cookies.txt to websites so that you appear signed in. Export the file from a desktop browser and import it here."
    static let cookiesNone = "No cookies.txt has been imported."
    static func cookiesImported(_ n: Int) -> String { n == 1 ? "cookies.txt imported with \(n) cookie." : "cookies.txt imported with \(n) cookies." }
    static let errorCookiesInvalid = "Unable to read this file. Please choose a cookies.txt file in the Netscape format."
    static let actionImportFile = "Import file", actionReplaceFile = "Replace file"
    static let removeCookiesTitle = "Remove the cookies?"
    static let removeCookiesMessage = "This will delete the imported cookies.txt from the app. You will need to import it again to download content that needs a sign-in."
    static let settingsStorageTitle = "Storage and reset"
    static let settingsStorageBody = "Manage what the app keeps on your phone."
    static func storageRecords(_ size: String) -> String { "The app's records use \(size)." }
    static func storageThumbnails(_ size: String) -> String { "The saved thumbnails and preview clips of your library use \(size)." }
    static let actionClearThumbnails = "Clear thumbnail cache"
    static let clearThumbnailsTitle = "Clear the thumbnail cache?"
    static let clearThumbnailsMessage = "This removes the saved thumbnails and preview clips of the pictures and videos in your library. They are made again when needed. Your files are not touched."
    static let actionClearHistory = "Clear finished history", actionResetSettings = "Reset settings"
    static let resetSettingsTitle = "Reset all settings?"
    static let resetSettingsMessage = "This will return every option to its default. Your imported cookies, downloaded files and history will not be changed."
    static let actionReset = "Reset"
    static let settingsAboutTitle = "About", settingsAboutBody = "Versions and licences."
    static let aboutAppVersion = "App version", aboutGalleryDlVersion = "gallery-dl version"
    static let aboutLicences = "Open-source software used: gallery-dl (GPL-2.0), Python (PSF), BeeWare Python-Apple-support (BSD-3-Clause), and the Inter typeface (SIL Open Font License 1.1)."

    static let optionStartupSoundTitle = "Startup sound"
    static let optionStartupSoundBody = "Plays a short sound while the logo appears when the app opens. It stays silent when your phone is on silent or vibrate."
    static let settingsAppearanceTitle = "Startup and previews", settingsAppearanceBody = "What happens when the app opens, and how the library looks."
    static let previewsPreparing = "Preparing previews"
    static func previewsProgress(_ done: Int, _ total: Int) -> String { "\(done) of \(total)" }
    static let optionLivePreviewsTitle = "Live previews"
    static let optionPreviewSpeedTitle = "Preview speed"
    static let optionPreviewSpeedBody = "How fast the live previews play in the library. Slower is easier to follow and lighter on the battery."
    static let optionLivePreviewsBody = "Plays a silent, looping preview of each video in the library, from a short clip that is saved once for every video. It pauses when Low Power Mode or Reduce Motion is on."

    static let notifComplete = "Download complete"
    static let notifPartial = "Download complete with errors"
    static let notifFailed = "Download failed"
    static let notifPausedTitle = "Downloads paused"
    static func notifPausedBody(_ n: Int) -> String {
        n == 1 ? "\(n) link is waiting. Open Gallery DL to continue." : "\(n) links are waiting. Open Gallery DL to continue."
    }
    static let errorDestinationUnavailable = "Unable to access the download folder."
}

# Середовище v0.1.12 — Android file bridge

- Runtime WebChromeClient: file input / Android picker.
- Runtime Blob/data anchor downloads: capture -> WebMessageListener scoped to PWA origin -> Android SAF -> SHA-256 readback verification.
- External links in shell: browser Intent.ACTION_VIEW.
- Icon: enlarged cube cluster.
- versionCode 22 / versionName 0.1.12-android.1.

No IndexedDB clearing, origin changes or app signing changes.

LIMITATIONS: File downloads must use a clicked anchor with download attribute and blob/data URL. Other export APIs may need additional integration. The browser's PWA file chooser supports standard input[type=file].

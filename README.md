# Open Camera — Product Folders

An Android camera app based on [Open Camera](https://sourceforge.net/projects/opencamera/), with a product-oriented capture workflow inspired by Folder Camera.

## Project direction

Keep Open Camera's camera engine and advanced controls while adding a persistent folder workflow:

- Choose one storage root using Android's Storage Access Framework (SAF).
- Create and select a folder for each product.
- Support optional subfolders such as `Front`, `Back`, `Details`, and `Packaging`.
- Keep the active product destination visible and switchable from the camera screen.
- Snapshot the selected destination when capture starts so asynchronous photo processing cannot route a file into a newly selected product folder.
- Route supported outputs (JPEG, RAW/DNG, HDR/bracketing, burst and video) through the same destination resolver.

## Status

The Open Camera source base has been imported. Initial product-folder routing is implemented on the `feature/product-folder-routing` branch. Select the existing SAF main folder, then use the camera overflow menu's **Product folder…** action to enter a relative path such as `SKU-123/Front`. Missing directories are created on selection. JPEG, RAW/DNG and video SAF outputs use that destination; photo save requests snapshot the folder path before background processing. This is development software and has not yet been device-tested.

## Build

Open the project in Android Studio, or run:

```bash
./gradlew assembleDebug
```

The project requires the Android SDK configured in your environment. Camera features depend on device hardware and Android support.

## Upstream and licensing

The camera code is Open Camera by Mark Harman and contributors. This source snapshot was imported from the GitHub SourceForge mirror at commit `51c77bc6243d10c98f9043462c6d81b2cfaefdd9`:

- Mirror: https://github.com/UDeedIt/Open-Camera
- Original project and official source: https://sourceforge.net/projects/opencamera/
- Website: https://opencamera.sourceforge.io/

Open Camera is licensed under GNU GPL version 3 or later. This project remains GPL-3.0-or-later; see [LICENSE](LICENSE) and the upstream license files for terms. AndroidX and Google Material Design icon assets retain their separate Apache 2.0 notices.

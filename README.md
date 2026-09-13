# Shelf

A quiet place for the things you kept.

Shelf is a native Android app for collecting meaningful objects as photo cutouts. Give each object a title, add a story if you like, and arrange your collection on shelves.

## Features

- Choose a photo with Android’s system photo picker.
- Press and hold an object to select it, then confirm with **Keep this**. Object buttons also support accessible selection.
- Watch a gentle three-second reveal, or keep the original as a framed photo. Background removal runs on-device.
- Add a title and optional story, then save it to a shelf.
- Revisit your objects and their stories whenever you like.

Photos and metadata stay in app-private storage. No account is required.

## Getting started

### Requirements

- Android 9 (API 28) or later
- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Google Play services for background removal

The segmentation model needs an internet connection for its initial download. Once available, background removal works offline. Framed photos work when segmentation is unavailable. Selection is limited to subjects the model detects separately; touching or overlapping objects can be grouped together.

### Build

```sh
git clone https://github.com/ShobhanKarthish/shelf.git
cd shelf
```

Open the project in Android Studio, or set `JAVA_HOME` to your JDK 17 installation and create a `local.properties` file with your SDK location:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Build and install on a connected device or emulator:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.shobhankarthish.shelf/.MainActivity
```

The generated APK is debug-signed for development.

## Releases

Download versioned APKs from [GitHub Releases](https://github.com/ShobhanKarthish/shelf/releases).
The standard APK is a non-debuggable release signed with Shelf's release key.
Version `0.2.0` uses Android version code `2`; increase the version code for every new release.

To build a signed release, keep your keystore and signing properties outside the repository:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=your-keystore-password
keyAlias=shelf-release
keyPassword=your-key-password
```

```sh
SHELF_SIGNING_PROPERTIES=/absolute/path/to/release-signing.properties ./gradlew assembleRelease
```

Signing credentials are required for release builds. Back up the keystore and credentials securely;
future updates must use the same signing key. Never commit them.

The release APK cannot update an earlier debug-signed installation. The separately labelled
`debug-upgrade` APK in v0.2.0 updates the earlier development build while retaining its local data.
Do not uninstall an existing installation containing keepsakes you want to retain: Shelf has no export yet.
Subsequent release-signed builds can update the release app normally.

## Development

Shelf uses Kotlin, native Android views, SQLite, and ML Kit subject segmentation. Cormorant Garamond and Inter are bundled with the app.

Run lint:

```sh
./gradlew lintDebug
```

Run the instrumentation tests on a connected device or emulator with Google Play services:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.shobhankarthish.shelf.test/android.test.InstrumentationTestRunner
```

The segmentation test uses the real model. On a fresh device, allow the model download to finish before retrying the test. Other checks cover transparent cropping, input validation, and local persistence.

## Current limitations

Shelf is in early development. Editing and deleting saved objects, camera capture, and export are not available yet. Collections are intended to be small.

Imported images are scaled to a maximum long edge of 1600 pixels. Uninstalling Shelf or clearing its storage removes saved objects; there is no backup or sync.

## Contributing

Bug reports and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.

## Fonts

Cormorant Garamond and Inter use the SIL Open Font License. Their license notices are included in [licenses/](licenses/).

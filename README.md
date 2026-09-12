# Shelf

A quiet place for the things you kept. Shelf is a native Kotlin Android app. No WebView, HTML runtime, demo collection, accounts, cloud sync, or social features.

## Run

Build the debug APK below and install it on Android 9 or later. Subject cutouts require Google Play services. Allow internet access for its first model download; subsequent segmentation works offline. If the model is unavailable or no useful subject is found, the app offers a framed original photo and retry.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.shobhankarthish.shelf/.MainActivity
```

On this workspace, `./build-local.sh assembleDebug lintDebug` uses the JDK installed under `../../work/tooling`. To build elsewhere, install JDK 17 and Android SDK platform 35/build-tools 35.0.0, set `JAVA_HOME`, and point `local.properties` at your SDK (`sdk.dir=/your/sdk`). Then run `./gradlew assembleDebug lintDebug`.

## Working functionality

- Three onboarding chapters with centered branding, bundled Cormorant Garamond/Inter, exported olive aura assets, 2.4-second reversing drift, and system animation-disable support.
- Empty shelf → Android system photo picker → actual ML Kit subject segmentation → transparent-cutout review or framed-photo fallback.
- Required object title, optional story, and a shelf name. Objects are grouped by shelf in the shelf.
- PNG images in app-private storage and metadata in SQLite; saving writes the image before committing its metadata. Save failures leave the draft available. Automatic cloud backup and device transfer are excluded.
- Object labels, dates, object details, cancel confirmation, scrollable forms, photo-picker cancellation, and draft restoration after activity recreation.

Images are normalized for orientation and limited to a 1600-pixel long edge before segmentation. This preserves a display-quality copy, not the full-resolution camera original. Uninstalling the app or clearing its storage removes your saved objects.

## Verification — 12 September 2026

Verified on an Android 15 / API 35 ARM64 Google Play emulator, at 412×915 dp and a smaller 360×640 dp layout with enlarged text. This is emulator evidence, not a physical-device test or a Play Store release.

- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` pass. Lint has no errors; remaining warnings are SDK modernization/performance suggestions.
- Real subject segmentation produced `evidence/real-model-cutout.png` from the Figma shell reference. It was not a precomputed cutout supplied by the app.
- The fresh-model download failure was observed in the app; retry subsequently produced a real cutout.
- Both device tests passed with emulator Wi-Fi and mobile data disabled after the model download. They check real inference, invalid masks, transparent cropping, title validation, SQLite reopen, image existence, and framed-photo metadata.
- Through the actual UI: completed onboarding, selected a photo with the system picker, reviewed a cutout, entered title/story, saved, force-stopped/relaunched, and reopened the same title/story/image.
- Also saved a framed photo with no story and reopened it after force-stop. Checked blank-title validation, form scrolling with enlarged text, hardware back, and cancel confirmation.
- Captured changing animation frames while enabled and visually inspected the static reduced-motion screen.

Run the device checks on a connected emulator/device with a ready segmentation model:

```sh
./build-local.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.shobhankarthish.shelf.test/android.test.InstrumentationTestRunner
```

On a fresh device the model test can fail while the model downloads. Retry after the download; a failed model test must not be reported as a pass. Test fixtures only exist in the test APK; the shipped app starts empty. No milestone functionality is mocked.

## Design and limits

Live Figma screens inspected before implementation: A00, A01, A02, A03, A06 and B01, plus screen inventory and onboarding motion data. Source: https://www.figma.com/design/zsXGR0YEvo1T06az52M8AO?node-id=2-29 . The accepted browser reference was read only; the original design workspace was not changed.

The native system photo picker replaces the Figma mock photo library. System status/navigation bars are real Android UI. Shelf names group objects directly; collection management, editing/deleting saved objects, camera capture, and export are outside this first milestone. The eager grid is intended for a small collection; hundreds of objects need a recycled thumbnail list.

Segmentation quality is model-dependent and was verified with one shell reference, not a broad real-world photo corpus. Google currently supplies this segmentation SDK in beta: https://developers.google.com/ml-kit/vision/subject-segmentation/android . First-use download needs connectivity; devices without Google Play services can use framed photos.

The APK is debug-signed for development. Fonts are bundled under the SIL Open Font License; see `licenses/`. App imagery and aura assets come from the supplied Figma/reference design.

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.shobhankarthish.shelf"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shobhankarthish.shelf"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
}
dependencies { implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1") }

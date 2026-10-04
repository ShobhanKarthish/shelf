import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseSigning = Properties().apply {
    val path = System.getenv("SHELF_SIGNING_PROPERTIES")
    if (path != null) file(path).inputStream().use { load(it) }
}
android {
    namespace = "com.shobhankarthish.shelf"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shobhankarthish.shelf"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        manifestPlaceholders["appLabel"] = "Shelf"
    }
    signingConfigs {
        create("release") {
            storeFile = releaseSigning.getProperty("storeFile")?.let { file(it) }
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
        // Release speed without the release key: installs next to Shelf as "Shelf Preview", for trying builds on a phone.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            manifestPlaceholders["appLabel"] = "Shelf Preview"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
}
dependencies { implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1") }

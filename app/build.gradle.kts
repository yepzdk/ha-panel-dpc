import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing credentials live in gitignored keystore.properties (see the example file).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dk.yepzdk.hapanel"
    compileSdk = 36

    defaultConfig {
        applicationId = "dk.yepzdk.hapanel"
        minSdk = 28
        targetSdk = 36
        // 5 and 8 went to the 0.4.1-rc.1 and 0.5.0-rc.1 OTA test builds.
        versionCode = 10
        versionName = "0.6.0-rc.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

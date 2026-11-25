plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksPassFile = rootProject.file("keystore/keystore-password.txt")
val ksPassword: String = if (ksPassFile.exists()) ksPassFile.readText().trim() else ""

android {
    namespace = "app.inboxeditor.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.inboxeditor.shell"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/oe-release.jks")
            storePassword = ksPassword
            keyAlias = "oe"
            keyPassword = ksPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}

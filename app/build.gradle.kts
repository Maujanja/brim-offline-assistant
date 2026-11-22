plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.msaidizi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.msaidizi.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Usaini thabiti (keystore iko kwenye repo) ili masasisho yasakinishwe juu ya toleo la zamani.
    // Hii ni kwa matumizi ya kibinafsi/majaribio tu.
    signingConfigs {
        getByName("debug") {
            storeFile = file("msaidizi-debug.jks")
            storePassword = "msaidizi123"
            keyAlias = "msaidizi"
            keyPassword = "msaidizi123"
        }
    }

    buildTypes {
        release {
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Vosk: utambuzi wa sauti kwenye simu yenyewe (bila AccessKey, bila intaneti)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}

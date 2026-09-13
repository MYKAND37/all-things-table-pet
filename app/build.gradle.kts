plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI supplies a fixed release key. Without it a build falls back to the debug key,
// which Android regenerates on every machine -- that is exactly why builds made on
// different CI runners could not install over one another.
val keystorePath: String? = System.getenv("ATP_KEYSTORE")
val keystorePassword: String? = System.getenv("ATP_KEYSTORE_PASSWORD")
val keyAliasName: String = System.getenv("ATP_KEY_ALIAS") ?: "atp-release"
val haveReleaseKey = keystorePath != null && keystorePassword != null

// Android also refuses an in-place update unless versionCode goes up, and the CI run
// number is already monotonic, so it makes a free version code.
val ciRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.atp.pet"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.atp.pet"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRun
        versionName = "0.6." + ciRun
    }

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                // A PKCS12 store normally protects the key with the store password.
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (haveReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

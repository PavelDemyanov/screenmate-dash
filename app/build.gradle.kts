plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.smdash"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.smdash"
        minSdk = 29
        targetSdk = 34
        versionCode = 27
        versionName = "0.27"
    }

    // Official releases are signed with a pinned key (keystore/smdash.keystore) so a new build
    // installs straight over the previous one — data + patch preserved. That keystore is deliberately
    // NOT in this repo: publishing it would let anyone sign an install-over "update" to existing users.
    // When it's absent (a fresh clone / fork), the build falls back to Android's auto-generated debug
    // key — perfectly fine for building and running your own copy. See README → "Building from source".
    val pinnedKeystore = rootProject.file("keystore/smdash.keystore")
    val hasPinnedKeystore = pinnedKeystore.exists()
    if (hasPinnedKeystore) {
        signingConfigs {
            create("smdash") {
                storeFile = pinnedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            if (hasPinnedKeystore) signingConfig = signingConfigs.getByName("smdash")
        }
        release {
            isMinifyEnabled = false
            if (hasPinnedKeystore) signingConfig = signingConfigs.getByName("smdash")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // talk to the device's own root adbd over localhost (adb wire protocol) to apply the system patch.
    // dadb 1.2.7's module metadata leaks graalvm/junit test deps into runtime → strip them.
    implementation("dev.mobile:dadb:1.2.7") {
        exclude(group = "org.graalvm.buildtools")
        exclude(group = "org.junit.platform")
        exclude(group = "org.junit.jupiter")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

val googleWebClientId: String = (project.findProperty("GOOGLE_WEB_CLIENT_ID") as? String)
    ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
    ?: "789457169216-dgnbo4u4vp835s2thkj6aa440tvhfcpt.apps.googleusercontent.com"

android {
    namespace = "com.tpeditz.privatevault.v2"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.tpeditz.privatevault.v2"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "OFFICIAL_REPO", "\"tpeditz/Private-vault-android-v2\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json\"")
        buildConfigField("String", "UPDATE_APK_URL", "\"https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/private-vault.apk\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70\"")
        buildConfigField("String", "DEVELOPER_TELEGRAM", "\"https://t.me/Tp_Editz\"")
        buildConfigField("String", "DEVELOPER_INSTAGRAM", "\"https://www.instagram.com/tp_ediitz/\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                val storeFileObj = file(keystorePath)
                if (storeFileObj.exists()) {
                    storeFile = storeFileObj
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: System.getenv("STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: System.getenv("KEY_ALIAS") ?: "privatevault"
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isCrunchPngs = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.getByName("release")
            val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: System.getenv("STORE_PASSWORD")
            val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
            if (releaseConfig.storeFile?.exists() == true && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
                signingConfig = releaseConfig
            } else {
                signingConfig = null
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2"
                )
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.bouncycastle.prov)
    implementation(libs.android.apksig)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.bouncycastle.prov)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    "ksp"(libs.androidx.room.compiler)
}

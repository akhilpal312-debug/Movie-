import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Upload-key material is never committed. Provide it through Gradle properties
// (-Pjarvis.storeFile=... etc.) or environment variables before building a release bundle.
val releaseKeystore: String? = (findProperty("jarvis.storeFile") as String?)
    ?: System.getenv("JARVIS_UPLOAD_KEYSTORE")
val releaseStorePassword: String? = (findProperty("jarvis.storePassword") as String?)
    ?: System.getenv("JARVIS_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias: String? = (findProperty("jarvis.keyAlias") as String?)
    ?: System.getenv("JARVIS_UPLOAD_KEY_ALIAS")
val releaseKeyPassword: String? = (findProperty("jarvis.keyPassword") as String?)
    ?: System.getenv("JARVIS_UPLOAD_KEY_PASSWORD")

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Secrets are never compiled in. This is only the default location of the
        // local backend that mints short-lived Gemini Live tokens.
        buildConfigField("String", "DEFAULT_BACKEND_BASE_URL", "\"http://10.0.2.2:8787\"")
        buildConfigField("String", "DEFAULT_GEMINI_MODEL", "\"gemini-3.8-live\"")
    }

    signingConfigs {
        if (releaseKeystore != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            create("upload") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "META-INF/DEPENDENCIES")
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // Real org.json implementation for JVM unit tests (android.jar only stubs it).
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nasawa.configlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.nasawa.configlauncher"
        minSdk = 29 // ARCHITECTURE.md §18 ruling (Archimedes, 2026-10-18)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // #73: one stable, committed DEBUG-ONLY key, so every dev build (any machine or CI runner) installs over the last.
    // Publicly known passwords; it proves nothing about who built the APK. Never use it for release: the release
    // build type has no signing config here, and a real release key must never be committed (see README).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    // §20.1 templates/config-folder/AGENTS.md → asset "config-folder/AGENTS.md"; §20.2 schema/launcher-config.v1.schema.json → asset (with schema/README.md and examples/, tiny).
    sourceSets { getByName("main") { assets.srcDir("../templates"); assets.srcDir("../schema") } }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            // R8 for release builds (shrink + optimize); matters most on the older wall-display devices.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Full lint runs in CI; any error fails the build. A missed SDK gate must fail too.
        error += "NewApi"
        abortOnError = true
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.networknt.json.schema)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}


// Schema-agreement tests read the repo's schema/ folder.
tasks.withType<Test>().configureEach {
    systemProperty("repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("schema"))
}

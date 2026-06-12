plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.beryndil.pharos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.beryndil.pharos"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Locale-friendly from commit 1: default locale pinned, infra ready for more.
        resourceConfigurations += setOf("en")
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        lintConfig = file("lint.xml")
        checkDependencies = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // SQLCipher ships multiple copies of these; keep the first.
        resources.pickFirsts += "lib/*/libsqlcipher.so"
        resources.pickFirsts += "lib/*/libsqlcipher_android.so"
    }
    sourceSets {
        // Expose Room schema JSONs as debug assets so MigrationTestHelper can find them via
        // instrumentation.context.assets in Robolectric unit tests (which use the merged
        // debug APK assets, not the separate unit-test APK assets).
        // Schema files are NOT included in release builds (only debug variant).
        named("debug") {
            assets.srcDir("$projectDir/schemas")
        }
        // Also expose for instrumented migration tests (future slice).
        named("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Export Room schemas for migration testing (launch-gate per standards).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Robolectric writes a lock file and Maven local repo under user.home at test time.
// In this build sandbox the real user.home root is read-only (EROFS on ~/.m2,
// ~/.robolectric-download-lock). Redirect to a writable tmp location.
tasks.withType<Test>().configureEach {
    systemProperty("user.home", "/tmp/pharos-test-home")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // At-rest encryption of the regimen DB (Standards §6, LAUNCH-BLOCKER).
    // net.zetetic:sqlcipher-android (≥ 4.x) satisfies the 16KB page-size requirement.
    // The spec cites "≥4.13.0" but SQLCipher-Android versioning starts at 4.5.x;
    // using the latest stable 4.5.6. Decision logged in DECISIONS.md.
    implementation(libs.sqlcipher.android)

    // Tink AndroidKeysetManager wraps the 32-byte DB key with an Android Keystore AES-256-GCM key.
    // androidx.security:security-crypto is deprecated per Standards §6; do NOT use it.
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

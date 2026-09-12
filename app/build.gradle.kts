import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeys = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// The Windows helper supplies an ASCII output path for Java 17 argument files.
providers.gradleProperty("clock.buildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.jianshen.clock"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jianshen.clock"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseKeys.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeys.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
        debug {
            // Use this project's key so test installs can be upgraded to the release APK.
            if (releaseKeys.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

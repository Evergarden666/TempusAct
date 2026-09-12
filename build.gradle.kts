buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20") }
}
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

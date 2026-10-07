plugins {
    id("com.android.application") version "9.3.0" apply false
    // Kotlin support ships inside AGP 9, so kotlin.android is deliberately not applied.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

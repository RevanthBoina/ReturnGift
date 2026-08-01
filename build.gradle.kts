// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// Detekt configuration
detekt {
    toolVersion = "1.23.7"
    source.setFrom(files("app/src/main/java"))
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    baseline = file("detekt-baseline.xml")
}
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
}
package com.codebyramiz.shelf.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal object ShelfSdk {
    const val COMPILE = 37
    const val TARGET = 36
    const val MIN = 26
}

private val javaVersion = JavaVersion.VERSION_17

/** Shared Android + Kotlin setup for every Android module (app and libraries). */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = ShelfSdk.COMPILE
        defaultConfig.minSdk = ShelfSdk.MIN
        compileOptions.sourceCompatibility = javaVersion
        compileOptions.targetCompatibility = javaVersion
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = true
        lint.apply {
            abortOnError = true
            warningsAsErrors = true
            checkReleaseBuilds = false
            // Version-freshness checks belong to a dependency bot, not to a red build.
            disable += setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                "ObsoleteLintCustomCheck",
            )
            xmlReport = false
            sarifReport = true
        }
    }
    configure<KotlinAndroidProjectExtension> { compilerOptions.applyShelfDefaults() }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    configure<KotlinJvmProjectExtension> {
        compilerOptions.applyShelfDefaults()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

private fun KotlinJvmCompilerOptions.applyShelfDefaults() {
    allWarningsAsErrors.set(true)
    freeCompilerArgs.addAll(
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlinx.coroutines.FlowPreview",
    )
}

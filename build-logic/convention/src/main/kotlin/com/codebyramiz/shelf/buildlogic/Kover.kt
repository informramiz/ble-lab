package com.codebyramiz.shelf.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Name of the Kover variant that the root project aggregates and verifies. */
internal const val KOVER_VARIANT = "app"

/**
 * Applies Kover and registers one variant per module, built from the debug unit tests
 * (Android) or the JVM tests, so `koverVerifyApp` at the root never runs release tests.
 */
internal fun Project.configureKover(sourceVariant: String) {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    configure<KoverProjectExtension> {
        currentProject {
            createVariant(KOVER_VARIANT) { add(sourceVariant) }
        }
    }
}

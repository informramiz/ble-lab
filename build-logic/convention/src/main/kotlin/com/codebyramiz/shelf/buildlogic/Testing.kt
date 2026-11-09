package com.codebyramiz.shelf.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

internal fun Project.configureUnitTests() {
    dependencies {
        "testImplementation"(libs.lib("junit4"))
        "testImplementation"(libs.lib("kotlin-test"))
        "testImplementation"(libs.lib("truth"))
        "testImplementation"(libs.lib("turbine"))
        "testImplementation"(libs.lib("kotlinx-coroutines-test"))
    }
    tasks.withType<Test>().configureEach {
        // Robolectric and Compose tests are memory hungry; keep forks bounded on shared machines.
        maxHeapSize = "2g"
        maxParallelForks = 2
        systemProperty("robolectric.logging.enabled", "false")
    }
}

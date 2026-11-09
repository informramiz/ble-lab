package com.codebyramiz.shelf.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.buildFeatures.compose = true
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        "implementation"(platform(bom))
        "testImplementation"(platform(bom))
        "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
        "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
        "lintChecks"(libs.lib("compose-lint-checks"))
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
}

dependencies {
    kover(project(":app"))
    kover(project(":ble"))
}

kover {
    currentProject {
        // Each module contributes its debug unit tests to a variant named "app".
        createVariant("app") {}
    }
    reports {
        filters {
            excludes {
                // Generated code: Hilt/Dagger, Compose singletons, BuildConfig.
                classes(
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*_HiltModules*",
                    "*Hilt_*",
                    "hilt_aggregated_deps.*",
                    "dagger.hilt.*",
                    "*ComposableSingletons*",
                    "*.BuildConfig",
                    "*_Impl*",
                )
                // Thin adapters over android.bluetooth: they need a real radio, so they are
                // exercised by the on-device checklist in the README, not by JVM tests.
                classes(
                    "com.codebyramiz.blelab.ble.android.*",
                    "com.codebyramiz.blelab.di.*",
                    "com.codebyramiz.blelab.MainActivity*",
                    "com.codebyramiz.blelab.BleLabApplication*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
        verify {
            rule("Line coverage floor") {
                bound {
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                "ktlint_standard_function-expression-body" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

import com.codebyramiz.shelf.buildlogic.lib
import com.codebyramiz.shelf.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module: Compose UI + ViewModels + type-safe navigation entry points.
 * Features depend on core modules only, never on each other.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("shelf.android.library.compose")
            pluginManager.apply("shelf.hilt")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            pluginManager.apply("io.github.takahirom.roborazzi")

            dependencies {
                "implementation"(project(":core:designsystem"))
                "implementation"(project(":core:ui"))
                "implementation"(project(":core:domain"))
                "implementation"(project(":core:model"))

                "implementation"(libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
                "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
                "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
                "implementation"(libs.lib("androidx-navigation-compose"))
                "implementation"(libs.lib("kotlinx-serialization-json"))

                "testImplementation"(project(":core:testing"))
                "testImplementation"(project(":core:screenshot-testing"))
                "testImplementation"(libs.lib("androidx-compose-ui-test-junit4"))
                "testImplementation"(libs.lib("robolectric"))
                "debugImplementation"(libs.lib("androidx-compose-ui-test-manifest"))
            }
        }
    }
}

import com.codebyramiz.shelf.buildlogic.lib
import com.codebyramiz.shelf.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            dependencies { "ksp"(libs.lib("hilt-compiler")) }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                dependencies { "implementation"(libs.lib("hilt-core")) }
            }
            pluginManager.withPlugin("com.android.base") {
                pluginManager.apply("com.google.dagger.hilt.android")
                dependencies { "implementation"(libs.lib("hilt-android")) }
            }
        }
    }
}

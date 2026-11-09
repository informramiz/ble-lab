import androidx.room.gradle.RoomExtension
import com.codebyramiz.shelf.buildlogic.lib
import com.codebyramiz.shelf.buildlogic.libs
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("androidx.room")
            pluginManager.apply("com.google.devtools.ksp")
            extensions.configure<KspExtension> { arg("room.generateKotlin", "true") }
            // One exported JSON schema per version: input for migration tests and auto-migrations.
            extensions.configure<RoomExtension> { schemaDirectory("$projectDir/schemas") }
            dependencies {
                "implementation"(libs.lib("androidx-room-runtime"))
                "implementation"(libs.lib("androidx-room-ktx"))
                "ksp"(libs.lib("androidx-room-compiler"))
                "testImplementation"(libs.lib("androidx-room-testing"))
            }
        }
    }
}

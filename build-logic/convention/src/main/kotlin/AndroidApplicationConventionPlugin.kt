import com.android.build.api.dsl.ApplicationExtension
import com.codebyramiz.shelf.buildlogic.ShelfSdk
import com.codebyramiz.shelf.buildlogic.configureKotlinAndroid
import com.codebyramiz.shelf.buildlogic.configureKover
import com.codebyramiz.shelf.buildlogic.configureUnitTests
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = ShelfSdk.TARGET
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            configureUnitTests()
            configureKover(sourceVariant = "debug")
        }
    }
}

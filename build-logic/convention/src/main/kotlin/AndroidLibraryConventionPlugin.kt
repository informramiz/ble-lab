import com.android.build.api.dsl.LibraryExtension
import com.codebyramiz.shelf.buildlogic.ShelfSdk
import com.codebyramiz.shelf.buildlogic.configureKotlinAndroid
import com.codebyramiz.shelf.buildlogic.configureKover
import com.codebyramiz.shelf.buildlogic.configureUnitTests
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = ShelfSdk.TARGET
                lint.targetSdk = ShelfSdk.TARGET
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
            configureUnitTests()
            configureKover(sourceVariant = "debug")
        }
    }
}

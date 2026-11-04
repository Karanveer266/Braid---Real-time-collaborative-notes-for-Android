import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions.configureBraidKotlin()
        }
    }
}

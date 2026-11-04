import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM modules. The CRDT and sync layers deliberately live here: they have
 * zero Android dependencies, so their (large) test suites run on the plain JVM in seconds
 * and the code could be reused on a desktop or server peer unchanged.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = BraidSdk.JAVA
            targetCompatibility = BraidSdk.JAVA
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.configureBraidKotlin()
        }
        tasks.withType<Test>().configureEach {
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

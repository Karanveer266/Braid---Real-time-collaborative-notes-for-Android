import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/** Single place for SDK levels so every module is guaranteed to agree. */
internal object BraidSdk {
    const val COMPILE = 36
    const val MIN = 26
    const val TARGET = 36
    val JAVA = JavaVersion.VERSION_17
    val JVM_TARGET = JvmTarget.JVM_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.configureAndroidCommon(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = BraidSdk.COMPILE
        defaultConfig {
            minSdk = BraidSdk.MIN
        }
        compileOptions {
            sourceCompatibility = BraidSdk.JAVA
            targetCompatibility = BraidSdk.JAVA
        }
        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
        }
    }
}

internal fun KotlinJvmCompilerOptions.configureBraidKotlin() {
    jvmTarget.set(BraidSdk.JVM_TARGET)
    freeCompilerArgs.addAll(
        "-Xjvm-default=all",
        "-opt-in=kotlin.RequiresOptIn",
        // @ProtoNumber / @ProtoPacked are the wire contract of the binary codec; opting in once
        // here keeps the build log free of ~40 identical warnings that would bury real ones.
        "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        // Kotlin 2.2+: put constructor-property annotations such as Hilt's
        // @ApplicationContext on both the parameter and the field, the future default.
        "-Xannotation-default-target=param-property",
    )
}

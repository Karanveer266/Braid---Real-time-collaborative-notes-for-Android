plugins {
    alias(libs.plugins.braid.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.protobuf)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

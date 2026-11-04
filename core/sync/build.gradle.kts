plugins {
    alias(libs.plugins.braid.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.crdt)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

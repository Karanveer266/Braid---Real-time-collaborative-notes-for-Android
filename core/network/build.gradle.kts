plugins {
    alias(libs.plugins.braid.android.library)
}

android {
    namespace = "dev.braid.network"
}

dependencies {
    api(projects.core.sync)
    implementation(libs.kotlinx.coroutines.android)
}

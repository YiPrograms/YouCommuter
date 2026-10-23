plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.yikuo.youcommuter.core.platform"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
}

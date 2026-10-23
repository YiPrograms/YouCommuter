import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsApiKey = providers.gradleProperty("youcommuter.mapsApiKey")
    .orElse(providers.environmentVariable("YOUCOMMUTER_MAPS_API_KEY"))
    .getOrElse(localProperties.getProperty("MAPS_API_KEY", "")).trim()

android {
    namespace = "dev.yikuo.youcommuter"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.yikuo.youcommuter"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(project(":feature:commute"))
    implementation(project(":feature:stations"))
    implementation(project(":feature:routes"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.serialization.json)
    testImplementation(libs.junit)
}

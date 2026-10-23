plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:model"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.serialization.json)
}
tasks.test {
    systemProperty("youcommuter.fixtures", rootProject.file("../fixtures").absolutePath)
}

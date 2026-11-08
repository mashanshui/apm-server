import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// 共同样例由平台维护，位置从 Android 工程解析，不依赖测试进程的工作目录。
tasks.withType<Test>().configureEach {
    systemProperty(
        "performance.sharedMemoryFixture",
        rootProject.layout.projectDirectory.file("../docs/fixtures/memory-runtime-peaks.json").asFile.absolutePath,
    )
}

android {
    namespace = "com.shanshui.performance.metrics"

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":performance-core"))
    api(project(":performance-transport"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

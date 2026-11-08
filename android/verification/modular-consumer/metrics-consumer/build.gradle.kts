plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.shanshui.modularconsumer.metrics" }

/** 本轮独立验收的 Maven 版本，日常接入默认保持 1.0.0。 */
val performanceVersion = providers.gradleProperty("performance.version").getOrElse("1.0.0")

dependencies {
    implementation("com.shanshui.nativelib:performance-core:$performanceVersion")
    implementation("com.shanshui.nativelib:performance-transport:$performanceVersion")
    implementation("com.shanshui.nativelib:performance-metrics:$performanceVersion")
}

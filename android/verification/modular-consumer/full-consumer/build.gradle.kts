plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.shanshui.modularconsumer.full" }

/** 本轮独立验收的 Maven 版本，日常接入默认保持 1.0.0。 */
val performanceVersion = providers.gradleProperty("performance.version").getOrElse("1.0.0")

dependencies {
    implementation("com.shanshui.nativelib:performance-sdk:$performanceVersion")
    implementation("com.shanshui.nativelib:performance-native-tools:$performanceVersion")
}

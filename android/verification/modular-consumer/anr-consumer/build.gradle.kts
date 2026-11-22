plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.shanshui.modularconsumer.anr" }

/** 只解析本轮发布的 ANR 坐标，不依赖源码工程。 */
val performanceVersion = providers.gradleProperty("performance.version").getOrElse("1.0.0")

dependencies {
    implementation("com.shanshui.nativelib:performance-anr:$performanceVersion")
}

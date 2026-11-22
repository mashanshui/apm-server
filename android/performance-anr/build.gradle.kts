plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.shanshui.performance.anr"

    buildFeatures {
        prefab = true
    }

    defaultConfig {
        // 首期仅构建 arm64 采集器；其他 ABI 由运行时明确降级。
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":performance-core"))
    // 保留 Matrix 源码的 API 与 JNI 回调注解，不作为运行时依赖。
    compileOnly("androidx.annotation:annotation:1.6.0")
    // 与现有 Crash 模块使用同一 JSON 实现，报告格式不依赖网络模块。
    implementation("com.google.code.gson:gson:2.11.0")
    // ByteHook 1.1.1 的传递版本为 1.1.1，统一到项目原有的 ShadowHook 2.0.0。
    implementation(libs.bytehook)
    implementation(libs.shadowhook)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

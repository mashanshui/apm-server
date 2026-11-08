import java.io.File
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}

/** 与示例 App 共用的未跟踪签名配置。 */
val localSigningProperties = providers.fileContents(
    layout.projectDirectory.file("../../.signing/signing.properties"),
).asText.orElse("").map { content -> Properties().apply { load(content.reader()) } }

/**
 * 优先读取显式 Gradle 属性，再读取本机测试签名配置。
 * @param name 完整属性名。
 * @return 非空属性，缺少时返回 null。
 */
fun signingValue(name: String): String? = providers.gradleProperty(name)
    .orElse(localSigningProperties.map { it.getProperty(name, "") })
    .get().takeIf { it.isNotBlank() }

/** 三个消费者共用的签名参数，不进入发布制品。 */
val signingValues = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { signingValue("performance.signing.$it") }
/** 拒绝部分配置与相对证书路径，避免构建位置改变证书选择。 */
val signingReady = signingValues.values.all { it != null } && File(signingValues["storeFile"].orEmpty()).isAbsolute

/** 统一独立消费者的 Android SDK、字节码和 Release 混淆边界。 */
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = 36
            signingConfigs {
                create("consumer") {
                    // 仅用于本地验证，保持与示例 App 相同的测试证书。
                    storeFile = signingValues["storeFile"]?.let(::File)
                    storePassword = signingValues["storePassword"]
                    keyAlias = signingValues["keyAlias"]
                    keyPassword = signingValues["keyPassword"]
                }
            }
            defaultConfig {
                minSdk = 21
                targetSdk = 36
                versionCode = 1
                versionName = "1.0"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            packaging {
                jniLibs {
                    // 多个原生组件携带相同的 C++ 运行库时选择同一份文件。
                    pickFirsts += "lib/*/libc++_shared.so"
                }
            }
            buildTypes {
                debug {
                    signingConfig = signingConfigs.getByName("consumer")
                }
                release {
                    signingConfig = signingConfigs.getByName("consumer")
                    isMinifyEnabled = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }
        }
        /** 缺少 AGP 签名任务时仍阻止消费者生成另一种签名身份的产物。 */
        val validateLocalConsumerSigning = tasks.register("validateLocalConsumerSigning") {
            group = "verification"
            doLast {
                check(signingReady) { "消费者签名需要完整 performance.signing.* 本地配置和绝对 storeFile 路径" }
                logger.lifecycle("已核验独立消费者的本地测试签名配置")
            }
        }
        tasks.matching {
            it.name in setOf("validateSigningDebug", "validateSigningRelease", "packageDebug", "packageRelease", "bundleRelease")
        }.configureEach { dependsOn(validateLocalConsumerSigning) }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            // 使用 Kotlin 1.9 的配置入口，验证发布制品能够被同版本消费者编译。
            kotlinOptions.jvmTarget = "11"
        }
    }
}

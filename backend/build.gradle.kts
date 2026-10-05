plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.shanshui"
version = "0.0.1-SNAPSHOT"
description = "apm-server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // 优先从 Maven Local 解析本地构建的内部制品，例如 rhea-trace-processor。
    mavenLocal()
    // 先从阿里云 Google Maven 镜像解析 R8，避免访问 Google Maven 超时。
    maven {
        url = uri("https://maven.aliyun.com/repository/google")
        content {
            includeGroup("com.android.tools")
        }
    }
    // 阿里云镜像未命中时回退到 Google Maven 官方源。
    maven {
        url = uri("https://maven.google.com")
        content {
            includeGroup("com.android.tools")
        }
    }
    // 使用阿里云公共 Maven 镜像优先解析远程依赖，减少对 Maven Central 直连的依赖。
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    // 阿里云镜像未命中或暂时不可用时，使用 Maven Central 兜底。
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Boot 4 的会话自动配置位于独立模块，不能只声明 Spring Session 库。
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // 官方 R8 Retrace API，用于校验和按请求还原 Android mapping。
    // 固定官方 R8 Retrace 版本，服务端通过 Java API 校验和还原 mapping。
    implementation("com.android.tools:r8:8.9.35")
    implementation("io.github.mashanshui:rhea-trace-processor:1.0.2") {
        isTransitive = false
    }
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("junit-tmp").get().asFile.absolutePath)
    // 将命令行传入的真实 mapping 路径转发给测试进程，确保外部样本测试不是只在 Gradle 进程中可见。
    project.providers.systemProperty("apm.real.mapping").orNull?.let { realMappingPath ->
        systemProperty("apm.real.mapping", realMappingPath)
    }
    // 将显式启动的符号表性能验收开关转发给测试进程。
    project.providers.systemProperty("apm.symbol.benchmark").orNull?.let { enabled ->
        systemProperty("apm.symbol.benchmark", enabled)
    }
    doFirst {
        layout.buildDirectory.dir("junit-tmp").get().asFile.mkdirs()
    }
}

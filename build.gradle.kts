import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "local.mcserver"
version = "1.0.2-paper26.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.1.2 API（Java 25 字节码）
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // 服务端实现 jar：仅编译期访问 NMS（CustomPayload / Identifier），不打包
    compileOnly(files("libs/paper-server-26.1.2.jar"))

    // Netty 由服务端提供（版本需与 26.1.2 自带的 4.2.7.Final 一致）
    compileOnly(fileTree("libs/netty") { include("*.jar") })

    // 从 YSM 模组剥离出的纯数据逻辑（模型解析 / 加密 / zstd），不含任何 Minecraft 类
    implementation(files("libs/ysm-core-pure.jar"))
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalStdlibApi")
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("YSMSync")
    archiveClassifier.set("")
    // paper-api 与 paper-server 为 compileOnly，不会进入产物；
    // 运行时依赖（YSM 纯逻辑 + Kotlin 标准库）由 shadow 自动合并。
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

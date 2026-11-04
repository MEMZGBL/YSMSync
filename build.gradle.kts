import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "local.mcserver"
version = "1.2.0-modernysm-26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API 26.3（自带 adventure / slf4j / fastutil 等传递依赖）
    compileOnly("io.papermc.paper:paper-api:26.3.build.9-alpha")

    // 服务端 NMS 实现（CustomPacketPayload / Identifier 等），仅编译期使用，不会进入产物
    compileOnly(files("libs/leaves-server-26.3.jar"))

    // Netty 由服务端提供（与服务端自带的 4.2.16.Final 保持一致）
    compileOnly(fileTree("libs/netty") { include("*.jar") })

    // 从 YSM 模组剥离出的纯数据逻辑（模型解析 / 加密 / zstd），不含任何 Minecraft 类
    implementation(files("libs/ysm-core-pure.jar"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(fileTree("libs/netty") { include("*.jar") })
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
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
    // leaves-server jar 与 netty 为 compileOnly，不会进入产物；
    // 运行时依赖（YSM 纯逻辑 + Kotlin 标准库）由 shadow 自动合并。
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

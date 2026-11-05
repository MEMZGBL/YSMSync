import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "local.mcserver"
version = "1.2.0-modernysm"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API：编译基线固定在 26.1.2，产物在 26.1.2 ~ 26.3 上都能运行。
    // 已实测：26.1.2 编译的 jar 放到 26.3 服务端，加载 / 握手 / 模型同步 / 动画全部正常。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // 服务端 NMS 实现（CustomPacketPayload / Identifier 等），仅编译期使用，不会进入产物。
    // 26.1.2 与 26.3 的 DiscardedPayload 签名与写入行为完全一致（writeBytes / readBytes），
    // 所以 libs 下放任意一版的服务端 jar 都能编译，产物也可跨版本运行。
    compileOnly(fileTree("libs") { include("paper-server-*.jar", "leaves-server-*.jar") })

    // Netty 由服务端提供（4.2.7 / 4.2.16 均可，只用基础 API）
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

plugins {
    // Kotlin 插件版本由 build-logic / 根 classpath 提供，此处不能再带 version，
    // 否则报 "plugin is already on the classpath with an unknown version"
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Mika 的 agent 内核（v0.1.35）。
 *
 * **纯 JVM 模块**：零 Compose、零 Android 依赖 —— 这是它能在同一 APK 里
 * 与 RinCore 共存的前提（Compose 运行时只能有一个版本，而它根本不碰 Compose）。
 *
 * 依赖按 Mika 原版清单，仅把 okhttp 对齐到我们的 5.5.0（原版是 4.12.0）。
 * 版本差异若引发编译错误，逐个适配 —— 但不改它的业务逻辑。
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // ★otel-proto 的 class 继承 GeneratedMessage（full 版），
    // 而 Mika 原版用 javalite —— 两者不兼容，这里必须用 protobuf-java。
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")

    testImplementation(libs.junit)
}

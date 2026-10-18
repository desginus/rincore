/* 【域 F·主题渲染】markdown — vendored 渲染库模块
 * 源: JetBrains/markdown 0.7.14 (含流式 parser 修复/性能优化) +
 *     rikkahub/markdown CJK 强调修复 (501ce95c64) + RinCore 下划线保护对齐 +
 *     v4.8.119 数学模型移植 (上游 master 重写版: CJK 邻接触发放宽 / 货币对拒绝 /
 *     行内代码内含 / 嵌套 $ 中止 — 修复"中文紧贴 $...$ 不渲染"的解析侧根因)。
 * 说明: 原为 jitpack 依赖 (com.github.rikkahub:markdown d79a97cc8e, 基于上游
 * 0.7.4, 落后 10 个版本); 本轮 vendored 为本地模块以获取上游全部修复。
 * 测试: src/test 数学触发矩阵 (CI: :markdown:testDebugUnitTest)。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.intellij.markdown"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // v4.8.119: 解析器行为回归门禁 (:markdown:testDebugUnitTest)
    testImplementation("junit:junit:4.13.2")
}

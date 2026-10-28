import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.common"
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    // RinCore 兼容层（B1 并树）：FloatingWindow（app 经 :common 传递）
    api(libs.floatingx.app)
    api(libs.floatingx.compose)
    api(libs.floatingx.system)
    // okhttp
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.okhttp.logging)

    // kotlinx
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    // apache commons
    api(libs.commons.text)

    // floating
    // https://github.com/Petterpx/FloatingX

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // quickjs
    api(libs.quickjs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

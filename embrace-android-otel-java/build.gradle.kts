plugins {
    id("embrace-prod-android-conventions")
}

description = "Embrace Android SDK: OpenTelemetry Java API"

android {
    namespace = "io.embrace.android.embracesdk.otel.java"
}

dependencies {
    compileOnly(project(":embrace-android-api"))
    compileOnly(project(":embrace-android-otel"))
    compileOnly(platform(libs.opentelemetry.bom))
    compileOnly(libs.opentelemetry.sdk)

    implementation(libs.opentelemetry.java.aliases)
    implementation(libs.opentelemetry.kotlin.compat)

    testImplementation(project(":embrace-android-otel"))
    testImplementation(platform(libs.opentelemetry.bom))
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.kotlinx.coroutines.core)
}

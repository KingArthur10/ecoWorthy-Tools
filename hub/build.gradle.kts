plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("app.batterycommander.hub.SpikeMainKt")
}

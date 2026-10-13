import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}

tasks.test {
    systemProperty("fixturesDir", rootProject.file("../protocol/fixtures").absolutePath)
    providers.environmentVariable("MACDROID_DEV_URI").orNull?.let { systemProperty("macdroidDevUri", it) }
    providers.environmentVariable("MACDROID_PHONE_FILE").orNull?.let { systemProperty("macdroidPhoneFile", it) }
    providers.environmentVariable("MACDROID_PHONE_RECEIVE_DIR").orNull?.let { systemProperty("macdroidPhoneReceiveDir", it) }
}

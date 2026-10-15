import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(libs.zxcvbn)

    // Bouncy Castle Argon2 is used only as a JVM-side reference KDF in tests
    // (Argon2kt is a native library and cannot run on the JVM).
    testFixturesImplementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.bouncycastle)
}

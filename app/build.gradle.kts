plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ares.vault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ares.vault"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumented tests run against "staging" (com.ares.vault.staging) so they can create and
    // destroy vaults without touching the real AresVault install.
    testBuildType = "staging"

    buildTypes {
        create("staging") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Debug-only autofill test harness, shared by debug and staging.
    sourceSets {
        getByName("debug") { kotlin.directories.add("src/testHarness/kotlin") }
        getByName("staging") { kotlin.directories.add("src/testHarness/kotlin") }
    }
}

// Security guard (spec §4.3): fail the build if any dependency merges in network access.
abstract class VerifyNoNetworkPermission : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = manifest.get().asFile.readText()
        if ("android.permission.INTERNET" in text) {
            throw GradleException("INTERNET permission found in merged manifest ${manifest.get().asFile}")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoNetworkPermission>("verify${name}NoNetworkPermission") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "install$name" }.configureEach {
            dependsOn(verify)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "stagingImplementation"(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.truth)
}

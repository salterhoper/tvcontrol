import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing. In CI the keystore comes from env vars (see
 * .github/workflows/release.yml); locally drop a `keystore.properties` at
 * android/. With neither present, the release build is signed with the debug
 * key so the APK still installs on a TV for sideloading.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingProp(name: String): String? = keystoreProps.getProperty(name) ?: System.getenv(name)

val overrideVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
val overrideVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()

android {
    namespace = "com.codesky.tvcontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codesky.tvcontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = overrideVersionCode ?: 1
        versionName = overrideVersionName ?: "0.1.0"
    }

    val releaseStore = signingProp("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore != null && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingProp("RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("RELEASE_KEY_ALIAS")
                keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.zxing.core)

    // Ktor logs through slf4j; keep the logcat output clean.
    implementation(libs.slf4j.nop)
}

/**
 * The web dashboard lives in ../dashboard and is compiled straight into the APK's
 * assets so that the TV app can serve it to any device on the LAN.
 */
val dashboardDir = layout.projectDirectory.dir("../../dashboard")
val dashboardAssets = layout.projectDirectory.dir("src/main/assets/dashboard")

val buildDashboard = tasks.register<Exec>("buildDashboard") {
    description = "Builds the web dashboard into app assets."
    group = "build"
    workingDir = dashboardDir.asFile
    commandLine("bash", "../scripts/build-dashboard.sh")
    inputs.dir(dashboardDir.dir("src"))
    inputs.file(dashboardDir.file("package.json"))
    inputs.file(dashboardDir.file("vite.config.ts"))
    outputs.dir(dashboardAssets)
}

tasks.named("preBuild") {
    dependsOn(buildDashboard)
}

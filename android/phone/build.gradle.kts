import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing. In CI the keystore comes from env vars (see
 * .github/workflows/release.yml). Locally you can drop a `keystore.properties`
 * at android/. If neither is present, the release build is signed with the
 * debug key so the APK still installs - handy for sideloading a remote.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingProp(name: String): String? = keystoreProps.getProperty(name) ?: System.getenv(name)

// versionName / versionCode can be overridden from the command line
//   ./gradlew :phone:assembleRelease -PversionName=1.2.0 -PversionCode=5
val overrideVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
val overrideVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()

android {
    namespace = "com.codesky.tvcontrol.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codesky.tvcontrol.remote"
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
            // Match the TV app: no minification, so Ktor/serialization reflection
            // never gets stripped and the sideloaded APK always just runs.
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "/META-INF/{AL2.0,LGPL2.1}",
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.slf4j.nop)
}

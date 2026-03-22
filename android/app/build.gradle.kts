import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun projectOrEnv(name: String): String? {
    val fromProject = findProperty(name)?.toString()?.trim()
    if (!fromProject.isNullOrEmpty()) return fromProject
    val fromEnv = System.getenv(name)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    return null
}

fun projectOrEnvInt(name: String, fallback: Int): Int {
    return projectOrEnv(name)?.toIntOrNull() ?: fallback
}

android {
    namespace = "com.acb.androidcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.acb.androidcam"
        minSdk = 26
        targetSdk = 35
        versionCode = projectOrEnvInt("ACB_VERSION_CODE", 1)
        versionName = projectOrEnv("ACB_VERSION_NAME") ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = projectOrEnv("ACB_UPLOAD_STORE_FILE")
    val releaseStorePassword = projectOrEnv("ACB_UPLOAD_STORE_PASSWORD")
    val releaseKeyAlias = projectOrEnv("ACB_UPLOAD_KEY_ALIAS")
    val releaseKeyPassword = projectOrEnv("ACB_UPLOAD_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
}

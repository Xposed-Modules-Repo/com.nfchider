plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nfchider"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nfchider"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: rootProject.projectDir.resolve("release.keystore").toString())
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "nfchider123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "nfchider"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "nfchider123"
        }

        create("ciDebug") {
            storeFile = file(rootProject.projectDir.resolve("debug.keystore"))
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("ciDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

configurations.implementation {
    exclude(group = "androidx.startup", module = "startup-runtime")
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

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
        versionCode = 14
        versionName = "1.1.14"
    }

    androidResources {
        localeFilters += listOf("zh", "en")
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
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*"
            )
        }
    }

    base {
        archivesName.set("NfcHider-${defaultConfig.versionCode}-${defaultConfig.versionName}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val apkDir = File(buildDir, "outputs/apk/release")
        val vCode = android.defaultConfig.versionCode
        val vName = android.defaultConfig.versionName
        val targetApk = File(apkDir, "NfcHider-$vCode-$vName.apk")
        apkDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk") && file != targetApk) {
                file.copyTo(targetApk, overwrite = true)
                file.delete()
            }
        }
    }
}

configurations.implementation {
    exclude(group = "androidx.startup", module = "startup-runtime")
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // 语义化图标（缩放、图层、定位、录制等）；release 构建有资源混淆，
    // 未引用的图标会被剥离，不会显著增大包体。
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
}

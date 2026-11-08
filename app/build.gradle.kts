import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.zerolinux.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.zerolinux.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 81
        versionName = "0.3.69"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            val keyPropertiesFile = rootProject.file("key.properties")
            if (keyPropertiesFile.exists()) {
                val properties = Properties()
                properties.load(keyPropertiesFile.inputStream())
                val keystoreFile = rootProject.file(properties.getProperty("storeFile", "release.keystore"))
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storePassword = properties.getProperty("storePassword")
                    keyAlias = properties.getProperty("keyAlias")
                    keyPassword = properties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { releaseConfig ->
                if (releaseConfig.storeFile != null && releaseConfig.storeFile!!.exists()) {
                    signingConfig = releaseConfig
                }
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "META-INF/ASL2.0",
                "META-INF/version-control-info.textproto",
                "META-INF/com/android/build/gradle/app-metadata.properties",
                "**/app-metadata.properties"
            )
        }
        jniLibs {
            // proot 必须用 nativeLibraryDir 下的真实文件，不能从 APK 内直接 mmap
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // rootfs 解压：Android 自带 toybox tar 不支持 xz，必须自己解
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // OpenCode 客户端模块（WebView 壳 + 原生桥）
    implementation(files("libs/OpenCodeMobile-1.3.166.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.18.0")
    implementation("org.tukaani:xz:1.10")

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 原生桥线程池的回归测试（走 JVM，不走 Android API）
    testImplementation("junit:junit:4.13.2")
}

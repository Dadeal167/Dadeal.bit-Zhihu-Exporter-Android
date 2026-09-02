plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dadealbit.zhihuextractor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dadealbit.zhihuextractor"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.0"
    }

    signingConfigs {
        // 固定调试签名: 直接改写 AGP 默认 debug 配置, 保证 CI 每次构建签名一致
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.briqt.screenlimit.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.briqt.screenlimit.admin"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.1"
    }

    signingConfigs {
        create("release") {
            val envKs = System.getenv("SCREENLIMIT_KEYSTORE")
            if (!envKs.isNullOrBlank() && file(envKs).exists()) {
                storeFile = file(envKs)
                storePassword = System.getenv("SCREENLIMIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCREENLIMIT_KEY_ALIAS")
                keyPassword = System.getenv("SCREENLIMIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 零第三方依赖：UI 全部使用平台原生 View 构建。
dependencies {}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.briqt.screenlimit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.briqt.screenlimit"
        minSdk = 29
        targetSdk = 35
        versionCode = 24
        versionName = "0.15.0"
    }

    signingConfigs {
        // 固定签名：各版本签名一致才能覆盖升级安装（卸载重装会清空本地规则）。
        // 通过 SCREENLIMIT_KEYSTORE* 环境变量注入，未设置时回落 debug 签名便于本地体验编译。
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
            // 不加 applicationIdSuffix：包名参与硬安全/自身放行判定，换包名会导致拦截自身。
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

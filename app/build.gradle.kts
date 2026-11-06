@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37
    namespace = "com.xposed.wetypehook"

    // 本地 release 签名：凭据放在仓库根 keystore.properties（已被 .gitignore 忽略）。
    // 文件不存在时 release 保持未签名，不影响其他人的构建。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.xposed.wetypehook"
        minSdk = 31
        targetSdk = 37
        versionCode = 38
        versionName = "1.28.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += arrayOf("kotlin/**", "google/**", "**.bin")
        }
    }
    androidResources {
        // Let Android memory-map SF Pro instead of decompressing it for each axis variant.
        noCompress += "sf_pro.ttf"
    }
    applicationVariants.all {
        val outputFileName = "WeType_UI_Enhanced-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 上游 HostPreferencesFileTest 在 JVM 单测需要 XmlPullParser 实现
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation-android:1.12.1")
    implementation("androidx.compose.ui:ui-android:1.12.1")
    implementation("androidx.compose.ui:ui-graphics-android:1.12.1")
    implementation("androidx.compose.ui:ui-text-android:1.12.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0") {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-android")
    }
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("org.luckypray:dexkit:2.3.0")
    // 剪贴板搜索：TinyPinyin 轻量拼音（原坐标 com.github.promeg:tinypinyin:2.0.3 已不可用，改用同源 MavenCentral 坐标）
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 自定义配方（data/CustomLooks.kt）用 kotlinx-serialization 落盘。
    // 版本在根 build.gradle.kts 的 plugins 块里统一声明（2.0.21），这里只 apply。
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 发布签名信息读自项目根的 `keystore.properties`（已 gitignore），
 * 里面只放路径与口令，**密钥本体放在项目之外**（见该文件里的 storeFile）。
 * 文件不在时 release 照样能构建，只是不出签名包——这样换台机器/CI 不会一上来就红。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.suipai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.suipai.app"
        minSdk = 26
        targetSdk = 36
        // 0.7.0：设计系统 v1 / 路由重构 / 设置中心 / 回收站收藏夹 / 搜索索引
        // / 一瞬专业版 / 相机专业模式 / 拼图边框漏光 / 回忆幻灯片 / 配方卡投屏
        // / 重复照片与存储分析。versionCode 必须比上一版大，Play 才允许覆盖上传。
        versionCode = 7
        versionName = "0.7.0"
        // 配方写盘/读盘要的是真机上的 androidx ExifInterface 行为，JVM 单测跑不到。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    testOptions {
        // 单元测试里 android.jar 全是桩：`Log.w` 一调就抛 "not mocked"。
        // 落盘失败那条路径**本来就要** Log.w，所以不开这个，守卫反而测不到失败分支。
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            // R8 一起开着 shrinkResources：上架看的是包体与冷启动，而这两条只有在
            // release 变体上才测得到。代价是反射依赖（kotlinx-serialization 的配方编解码）
            // 必须在**真 release 包**上回归过，见 proguard-rules.pro 与交接文档 §4。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // AGP 8.9 起新开了 UseKtx（建议改用 androidx.core 的 KTX 扩展），一次命中 215 处
        // prefs.edit().putX(...).apply() 这类样板。那是纯风格建议：为了它机械改写 8 个大文件、
        // 且这些路径没有任何单测覆盖，回归风险远大于收益。
        // 所以只关这一项风格检查；所有正确性检查和 abortOnError 都保持开着，lint 仍是 0 error。
        disable += "UseKtx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val cameraX = "1.4.1"
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")

    implementation(project(":shared"))

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 与 shared 同一个版本：两份版本不一致时 KMP 的 commonMain 会编不过。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

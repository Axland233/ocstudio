plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.yehenowo.gitmind"
    compileSdk = 37  // MaterialKolor 5.x 要求 37;AGP9.1+37 组合已在 EdifierPods prototype 验证

    defaultConfig {
        applicationId = "com.yehenowo.gitmind"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // JGit 会带来一大堆 META-INF 签名/服务声明,不去掉会打包冲突
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "**/*.kotlin_metadata"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

kotlin {
    jvmToolchain(22)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.materialkolor:material-kolor:4.0.5")  // 5.0.1 依赖 JB m3 1.12 构造器,与 BOM 2025.05(material3 1.3.2) 运行时不兼容→闪退
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // LLM 网络层(OpenAI 兼容 SSE)
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.1.0")

    // git 引擎(纯 Java,Android 可用)
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
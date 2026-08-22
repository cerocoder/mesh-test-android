plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cerocoder.meshtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cerocoder.meshtest"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Классы transport/, emulator/, connection/ пишут в android.util.Log.
    // Без этой строки любой вызов Log в JVM-тесте падает с "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Блок верхнего уровня: расширение kotlin принадлежит плагину Kotlin, а не android {}.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(libs.meshtastic.protobufs)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

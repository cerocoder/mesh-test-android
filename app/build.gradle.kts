plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cerocoder.meshtest"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cerocoder.meshtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем release отладочным ключом. Это личное приложение для
            // работы со своей нодой, оно не публикуется, а без подписи
            // assembleRelease выдаёт app-release-unsigned.apk, который Android
            // просто откажется устанавливать — то есть проверить release-вариант
            // на телефоне было бы нечем. Если приложение когда-нибудь пойдёт
            // дальше своего телефона, здесь нужен настоящий ключ из секретов CI.
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)

    implementation(libs.meshtastic.protobufs)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

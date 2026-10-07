plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rainbowcomputer.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rainbowcomputer.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 호출어(wake-word) 인식용 오프라인 음성인식 엔진. 버전 표기 "0.3.32+"와
    // jna 좌표는 Vosk 공식 Android 설치 안내(alphacephei.com/vosk/install)에
    // 나온 그대로다 — "+"는 그 버전 이상의 최신을 Gradle이 알아서 받아오게
    // 하는 의도적인 표기(오타 아님). 만약 빌드 시 이 좌표의 해석이 실패하면
    // Maven Central에서 "com.alphacephei:vosk-android"의 실제 최신 버전
    // 번호를 확인해 정확한 숫자로 바꿔주면 된다(실기기 빌드를 못 해본
    // 상태라 여기서 100% 보장은 못 한다).
    implementation("com.alphacephei:vosk-android:0.3.32+")
    implementation("net.java.dev.jna:jna:5.8.0@aar")
}

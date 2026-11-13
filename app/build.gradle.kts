plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.montecarlo.meai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.montecarlo.meai"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // مفتاح توقيع ثابت للنسخ التجريبية، عشان التحديثات تنزل فوق بعض بدون حذف
    signingConfigs {
        getByName("debug") {
            storeFile = file("meai-debug.jks")
            storePassword = "meai1234"
            keyAlias = "meai"
            keyPassword = "meai1234"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

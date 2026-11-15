plugins {
    id("com.android.application")
// Removed: Built into AGP 9.0+ (android gradle plugin) 
//    id("org.jetbrains.kotlin.android")
//    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Двойные кавычки и знак равенства строго обязательны!
    namespace = "com.kavabanga.alarmapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kavabanga.alarmapp"
        minSdk = 26    // Минимальная поддерживаемая версия SDK для Compose — 21
        targetSdk = 31
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
//        compose = true // включаем поддержку Jetpack Compose
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
  // deprecated in AGP 9 (android gradle plugin)
  // kotlinOptions block removed; AGP 9+ automatically inherits Java 17 from compileOptions
  //  kotlinOptions {
  //      jvmTarget = "17"
  //  }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

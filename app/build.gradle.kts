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
        targetSdk = 33
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

// Добавлено вручную для использования Jetpack Compose
//    // BOM автоматически согласует версии всех библиотек Compose
//    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
//    implementation(composeBom)
//    androidTestImplementation(composeBom)

 //   // Базовый UI и дизайн Material 3
 //   implementation("androidx.compose.ui:ui")
 //   implementation("androidx.compose.material3:material3")

 //   // Инструменты для @Preview в Android Studio
 //   implementation("androidx.compose.ui:ui-tooling-preview")
 //   debugImplementation("androidx.compose.ui:ui-tooling")

 //   // Интеграция с Activity (для setContent)
 //   implementation("androidx.activity:activity-compose:1.10.1")

}

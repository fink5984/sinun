plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sinun.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sinun.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // כתובת השרת. debug → פרודקשן ב-Railway; אפשר לעקוף מקומית עם 10.0.2.2:8000.
        buildConfigField("String", "API_BASE_URL", "\"https://sinun-production.up.railway.app\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room — policy cache מקומי
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager — heartbeat ומשימות רקע
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // HTTP (JSON דרך org.json המובנה — מספיק לשלד)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // בדיקות יחידה על ליבת המנוע (JVM, ללא אמולטור)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dynamicisland"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dynamicisland"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
}

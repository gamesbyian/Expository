plugins {
    id("com.android.application") version "8.11.1"
}

android {
    namespace = "dev.expository.transfer.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.expository.transfer.fixture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-test-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

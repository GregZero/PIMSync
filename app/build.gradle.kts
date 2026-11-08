plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.pimsync.prototype"
    compileSdk = 35

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

    defaultConfig {
        applicationId = "fr.pimsync.prototype"
        minSdk = 23
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // GitHub Actions run numbers make each published build uniquely identifiable.
        // Local builds keep a usable development version when this variable is absent.
        val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val githubRunAttempt = System.getenv("GITHUB_RUN_ATTEMPT")?.toIntOrNull() ?: 1
        versionCode = githubRunNumber?.let { it * 100 + githubRunAttempt } ?: 1
        versionName = githubRunNumber?.let { "0.1.$it" } ?: "0.1.0"
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

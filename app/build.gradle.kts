import com.android.utils.TraceUtils.simpleId

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.PixelFlow.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.PixelFlow.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.glide)

    implementation(libs.okhttp)
    implementation(libs.gson)

    annotationProcessor(libs.glide.compiler)

    implementation(libs.room)

    annotationProcessor(libs.room.compiler)

    implementation(libs.flowlayout)

    implementation(libs.photoView)

    implementation(libs.exoVedioPlayer)

    implementation(libs.bundles.markwon.simple)
}
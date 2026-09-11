plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.tikketx.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tikketx.pos"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the Swipe data route lives. The till no longer talks to
        // Firestore directly — it posts to /api/data, the same endpoint the
        // dashboard uses, so there is one query engine and one storage shape.
        buildConfigField(
            "String",
            "SWIPE_API_ORIGIN",
            "\"${project.findProperty("swipeApiOrigin") ?: "https://app.swipe.gm"}\"",
        )
    }

    buildTypes {
        debug {
            // Point a debug install at a laptop with:
            //   ./gradlew assembleDebug -PswipeApiOriginDebug=http://10.0.2.2:8080
            // 10.0.2.2 is the host machine as seen from the Android emulator.
            buildConfigField(
                "String",
                "SWIPE_API_ORIGIN",
                "\"${project.findProperty("swipeApiOriginDebug") ?: project.findProperty("swipeApiOrigin") ?: "http://10.0.2.2:8080"}\"",
            )
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        // The heartbeat reports the running version to the dashboard, so it has
        // to come from the build rather than a hand-updated constant.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.print)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.okhttp)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.glide)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

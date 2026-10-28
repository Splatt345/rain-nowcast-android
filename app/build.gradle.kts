plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}
// versionCode * 10 + this, so each ABI's APK has its own versionCode (fdroid/nowcast.app.yml: VercodeOperation)
val ABI_CODES = mapOf("arm64-v8a" to 2, "x86_64" to 4)
android {
    namespace = "nowcast.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "nowcast.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // one APK per ABI, each with only its own onnxruntime and MapLibre libraries
    splits { abi { isEnable = true; reset(); include(*ABI_CODES.keys.toTypedArray()); isUniversalApk = false } }
    // lz4-java's desktop natives (via jHDF): Android loads none of them and falls back to Java
    packaging { resources { excludes += listOf("net/jpountz/util/darwin/**", "net/jpountz/util/windows/**") } }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            output.versionCode.set(output.versionCode.get() * 10 + (ABI_CODES[abi] ?: 0))
        }
    }
}
dependencies {
    implementation(project(":engine"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance:1.2.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

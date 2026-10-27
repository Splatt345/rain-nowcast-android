plugins { id("com.android.application") }
android {
    namespace = "nowcast.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "nowcast.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("onnx", "h5") }
}
dependencies {
    implementation(project(":engine"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

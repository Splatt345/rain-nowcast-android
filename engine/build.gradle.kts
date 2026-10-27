plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("io.jhdf:jhdf:0.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // The app supplies onnxruntime-android (same ai.onnxruntime API) at runtime.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { maxHeapSize = "3g" }

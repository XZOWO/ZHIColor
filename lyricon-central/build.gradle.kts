plugins { id("com.android.library") }

android {
    namespace = "io.github.proify.lyricon.central"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("io.github.proify.lyricon:provider:0.1.70")
    api("io.github.proify.lyricon:subscriber:0.1.70")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val updateSettings = Properties().apply {
    rootProject.file("update.properties").inputStream().use { load(it) }
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val signingEnvironment = listOf("ZHICOLOR_KEYSTORE", "ZHICOLOR_STORE_PASSWORD", "ZHICOLOR_KEY_ALIAS", "ZHICOLOR_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
require(signingEnvironment.values.all { it.isNullOrBlank() } || signingEnvironment.values.all { !it.isNullOrBlank() }) {
    "Release signing requires all four ZHICOLOR signing environment variables."
}

android {
    namespace = "com.naivety.zhicolor"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.naivety.zhicolor"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        buildConfigField("String", "GITHUB_UPDATE_MANIFEST_URL", quoted(updateSettings.getProperty("githubUpdateManifestUrl")))
        buildConfigField("String", "GITHUB_RELEASES_URL", quoted(updateSettings.getProperty("githubReleasesUrl")))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingEnvironment.values.all { !it.isNullOrBlank() }) {
            create("official") {
                storeFile = file(signingEnvironment.getValue("ZHICOLOR_KEYSTORE")!!)
                storePassword = signingEnvironment.getValue("ZHICOLOR_STORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("ZHICOLOR_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("ZHICOLOR_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("official")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.0")
    implementation("dev.chrisbanes.haze:haze-android:1.7.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.0")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.github.libxposed:service:101.0.0")
    compileOnly(project(":libxposed-api-stubs"))
    implementation(project(":bridge-core"))
    implementation(project(":lyricon-central"))
    implementation("io.github.proify.lyricon:subscriber:0.1.70")
    implementation("io.github.proify.lyricon.lyric:model:0.1.70")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}

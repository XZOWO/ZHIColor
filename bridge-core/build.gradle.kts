import java.util.Properties

plugins { id("com.android.library") }
val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
android {
    namespace = "com.naivety.zhicolor.bridge"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        buildConfigField("String", "APPLICATION_ID", "\"com.naivety.zhicolor\"")
        buildConfigField("String", "VERSION_NAME", "\"${releaseVersion.getProperty("versionName")}\"")
        buildConfigField("int", "VERSION_CODE", releaseVersion.getProperty("versionCode"))
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    compileOnly(project(":libxposed-api-stubs"))
    implementation("com.mocharealm.accompanist:lyrics-core-jvm:0.4.7")
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    testImplementation(project(":libxposed-api-stubs"))
}

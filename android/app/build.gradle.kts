plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ndwood.tvremote"
    compileSdk = 34
    defaultConfig { applicationId = "com.ndwood.tvremote"; minSdk = 21; targetSdk = 28; versionCode = 6; versionName = "1.5" }
    signingConfigs { getByName("debug") { storeFile = file("../debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("dev.mobile:dadb:1.2.10")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

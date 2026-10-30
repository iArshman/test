import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".common"
    compileSdk = 36

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "VERSION_NAME", "\"${rootProject.ext["appVersionName"]}\"")
        buildConfigField("int", "VERSION_CODE", "${rootProject.ext["appVersionCode"]}")
        buildConfigField("String", "APPLICATION_ID", "\"${rootProject.ext["applicationId"]}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
        buildConfigField("String", "BUILD_HASH", "\"${rootProject.ext["buildHash"]}\".toString()")
        val gitHash = providers.environmentVariable("GITHUB_SHA")
            .orElse(providers.environmentVariable("GIT_COMMIT"))
            .orElse(providers.gradleProperty("gitHash"))
            .orElse(providers.systemProperty("git.hash"))
            .orElse("unknown")
            .get()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "SIF_ENDPOINT", "\"${properties["debug_sif_endpoint"]?.toString() ?: "https://github.com/SnapEnhance/resources/raw/refs/heads/main/sif"}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(libs.coroutines)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.rhino)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.rhino.android) {
        exclude(group = "org.mozilla", module = "rhino-runtime")
    }

    compileOnly(libs.androidx.activity.ktx)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.navigation.compose)
    compileOnly(libs.androidx.material.icons.core)
    compileOnly(libs.androidx.material.ripple)
    compileOnly(libs.androidx.material.icons.extended)
    compileOnly(libs.androidx.material3)

    implementation(project(":mapper"))
}

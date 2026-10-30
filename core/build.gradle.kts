plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".core"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "NATIVE_KEY", "\"${System.getenv("NATIVE_KEY") ?: ""}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(files("libs/LSPosed-api-1.0-SNAPSHOT.jar"))
    implementation(libs.coroutines)
    implementation(libs.kotlin.reflect)
    implementation(libs.recyclerview)
    implementation(libs.gson)
    implementation(libs.dexkit)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.rhino)
    implementation(libs.androidx.constraintlayout)


    implementation(project(":common"))
    implementation(project(":mapper"))
    implementation(project(":native"))
    implementation(project(":valdi"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.ripple)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.hiddenapibypass)
    implementation(libs.colorpicker.compose)
    implementation(libs.android.liquid.glass)
}

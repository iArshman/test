plugins {
    alias(libs.plugins.androidLibrary)
}

//test

android {
    namespace = rootProject.ext["applicationId"].toString() + ".mapper"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.coroutines)
    implementation(libs.dexlib2)
    testImplementation(libs.junit)
}

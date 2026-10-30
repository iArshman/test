// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rust.android) apply false
}

// Remove these lines:
// var versionName = "1.0.0"
// var versionCode = 210

import org.gradle.api.provider.Property
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class GetVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @TaskAction
    fun writeVersion() {
        val versionFile = project.layout.projectDirectory.file("app/build/version.txt").asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText(versionName.get())
    }
}

tasks.register<GetVersionTask>("getVersion") {
    // Value comes from gradle.properties; falls back to 1.0.0 if not set.
    versionName.set(providers.gradleProperty("APP_VERSION_NAME").orElse("1.0.0"))
}

// You can still set these for legacy use by submodules or scripts:
rootProject.ext.set("appVersionName", providers.gradleProperty("APP_VERSION_NAME").orElse("1.7.6").get())
rootProject.ext.set("appVersionCode", providers.gradleProperty("APP_VERSION_CODE").orElse("331").get().toInt())
rootProject.ext.set("applicationId", "me.eternal.purrfect")
// Retrieve the Git Commit SHA to use as a deterministic identifier for native linking
val gitCommit = try {
    val proc = java.lang.Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
    proc.waitFor()
    val text = proc.inputStream.bufferedReader().readText().trim()
    if (text.isEmpty()) "static_baseline" else text
} catch (e: Exception) {
    "static_baseline"
}

rootProject.ext.set(
    "buildHash",
    (properties["debug_build_hash"] as String?)
        ?: "${rootProject.ext["appVersionCode"]}_$gitCommit"
)

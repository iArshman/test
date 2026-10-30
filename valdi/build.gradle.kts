import org.apache.tools.ant.taskdefs.condition.Os
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".valdi"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            assets.srcDirs("build/assets")
        }
    }
}

tasks.register("installTypeScript", org.gradle.api.tasks.Exec::class) {
    val tscScriptPath = layout.projectDirectory.file("node_modules/.bin/tsc").asFile
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npm.cmd", "install", "typescript")
    } else {
        commandLine("npm", "install", "typescript")
    }
    doLast {
        if (!Os.isFamily(Os.FAMILY_WINDOWS) && tscScriptPath.exists()) {
            tscScriptPath.setExecutable(true)
        }
    }
}

tasks.register("compileTsc", org.gradle.api.tasks.Exec::class) {
    dependsOn("installTypeScript")
    environment("NODE_OPTIONS", "--max-old-space-size=4096")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npx.cmd", "--yes", "tsc", "--project", "tsconfig.json")
    } else {
        commandLine("npx", "--yes", "tsc", "--project", "tsconfig.json")
    }
}

tasks.register("compileRollup", org.gradle.api.tasks.Exec::class) {
    dependsOn("compileTsc")
    environment("NODE_OPTIONS", "--max-old-space-size=4096")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npx.cmd", "--yes", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
    } else {
        commandLine("npx", "--yes", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
    }
}

tasks.register<org.gradle.api.tasks.Sync>("syncComposerAssets") {
    dependsOn("compileRollup")
    from(layout.projectDirectory.file("build/loader.js"))
    into(layout.projectDirectory.dir("build/assets/valdi"))
}

tasks.named("preBuild").configure {
    dependsOn("syncComposerAssets")
}

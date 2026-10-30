package me.eternal.purrfect.mapper.tests

import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import me.eternal.purrfect.mapper.ClassMapper
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File


class TestMappings {
    @Test
    fun testMappings() {
        val classMapper = ClassMapper()

        val gson = GsonBuilder().setPrettyPrinting().create()
        val apkPath = System.getenv("SNAPCHAT_APK")
        if (apkPath == null) {
            println("Skipping test: SNAPCHAT_APK environment variable is not set.")
            return
        }
        val apkPaths = apkPath
            .split(File.pathSeparator)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        apkPaths.forEachIndexed { index, path ->
            val stats = classMapper.loadApk(File(path).absolutePath)
            println("Loaded APK[$index]: path=${stats.path}, dex=${stats.dexFiles}, classes=${stats.classes}, origin=${stats.fromLspatchOrigin}")
        }
        runBlocking {
            val result = classMapper.run()
            val authContextDelegate = result
                .getAsJsonObject("Callbacks")
                ?.getAsJsonObject("callbacks")
                ?.get("AuthContextDelegate")
                ?.asString
            println("Callback AuthContextDelegate: ${authContextDelegate ?: "missing"}")
            assertTrue(
                "Callback mapper should find AuthContextDelegate",
                !authContextDelegate.isNullOrBlank()
            )
            val themeSurface = result.getAsJsonObject("ThemeSurface")
            if (themeSurface != null) {
                val classCount = themeSurface.getAsJsonObject("themeCandidateClasses")?.size() ?: 0
                val methodCount = themeSurface.getAsJsonObject("themeCandidateMethods")?.size() ?: 0
                val attrCount = themeSurface.getAsJsonObject("surfaceAttributeStrings")?.size() ?: 0
                val tokenCount = themeSurface.getAsJsonObject("surfaceTokenStrings")?.size() ?: 0
                val ownerCount = themeSurface.getAsJsonObject("surfaceStringOwners")?.size() ?: 0
                println(
                    "ThemeSurface counts: " +
                        "classes=$classCount, " +
                        "methods=$methodCount, " +
                        "attrs=$attrCount, " +
                        "tokens=$tokenCount, " +
                        "owners=$ownerCount"
                )
                assertTrue("ThemeSurface mapper should find candidate classes", classCount > 0)
                assertTrue("ThemeSurface mapper should find candidate methods", methodCount > 0)
                assertTrue("ThemeSurface mapper should find surface attributes", attrCount > 0)
                assertTrue("ThemeSurface mapper should find surface tokens", tokenCount > 0)
                assertTrue("ThemeSurface mapper should record string owners", ownerCount > 0)
            }
            println("Class totals: classes=${classMapper.getLoadedClassCount()}, uniqueTypes=${classMapper.getLoadedTypeCount()}")
            assertTrue("ClassMapper should load Snapchat classes", classMapper.getLoadedClassCount() > 0)
            assertTrue("ClassMapper should load Snapchat unique types", classMapper.getLoadedTypeCount() > 0)
            println("Mappings: ${gson.toJson(result)}")
        }
    }
}

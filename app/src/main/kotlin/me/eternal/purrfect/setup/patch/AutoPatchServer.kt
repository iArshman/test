package me.eternal.purrfect.setup.patch

import com.google.gson.JsonParser
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import me.eternal.purrfect.common.TargetApp
import okhttp3.OkHttpClient
import okhttp3.Request

class AutoPatchServer(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(1, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("User-Agent", "Purrfect")
                    .build()
            )
        }
        .build()
) {
    private val snapchatAssetRandom = SecureRandom()

    data class LatestApk(
        val tagName: String,
        val apkName: String,
        val downloadUrl: String,
    )

    fun fetchLatestSnapchatApk(): LatestApk? = fetchLatestApk(TargetApp.SNAPCHAT)

    fun fetchLatestRedditApk(): LatestApk? = fetchLatestApk(TargetApp.REDDIT)

    fun fetchLatestInstagramApk(): LatestApk? = fetchLatestApk(TargetApp.INSTAGRAM)

    fun fetchLatestApk(targetApp: TargetApp): LatestApk? {
        targetApp.releaseRepositories().forEach { repository ->
            fetchLatestApkFromRepository(targetApp, repository)?.let { return it }
        }
        return null
    }

    private fun fetchLatestApkFromRepository(
        targetApp: TargetApp,
        repository: ReleaseRepository
    ): LatestApk? {
        val request = Request.Builder()
            .url(repository.latestReleaseApiUrl)
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val json = response.body?.string() ?: return@runCatching null
                val release = JsonParser.parseString(json).asJsonObject
                val tagName = release.getAsJsonPrimitive("tag_name")?.asString ?: "latest"

                val assets = release.getAsJsonArray("assets") ?: return@runCatching null
                val apkAssets = assets.mapNotNull { element ->
                    val asset = element.asJsonObject
                    val name = asset.getAsJsonPrimitive("name")?.asString ?: return@mapNotNull null
                    val downloadUrl = asset.getAsJsonPrimitive("browser_download_url")?.asString ?: return@mapNotNull null
                    if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                    name to downloadUrl
                }

                val selected = selectApkAsset(targetApp, apkAssets) ?: return@runCatching null

                LatestApk(
                    tagName = tagName,
                    apkName = selected.first,
                    downloadUrl = selected.second,
                )
            }
        }.getOrNull()
    }

    private fun selectApkAsset(
        targetApp: TargetApp,
        apkAssets: List<Pair<String, String>>
    ): Pair<String, String>? {
        if (apkAssets.isEmpty()) return null

        return when (targetApp) {
            TargetApp.SNAPCHAT -> apkAssets[snapchatAssetRandom.nextInt(apkAssets.size)]
            TargetApp.REDDIT -> apkAssets.first()
            TargetApp.INSTAGRAM -> apkAssets.first()
            TargetApp.WHATSAPP -> null
        }
    }

    private data class ReleaseRepository(
        val owner: String,
        val name: String,
        val host: Host = Host.GITHUB
    ) {
        enum class Host { GITHUB, PURRFECT_GIT }

        val latestReleaseApiUrl: String
            get() = when (host) {
                Host.GITHUB -> "https://api.github.com/repos/$owner/$name/releases/latest"
                Host.PURRFECT_GIT -> "https://www.purrfectgit.com/api/repos/$owner/$name/releases/latest"
            }
    }

    private fun TargetApp.releaseRepositories(): List<ReleaseRepository> {
        return when (this) {
            TargetApp.SNAPCHAT -> listOf(
                ReleaseRepository("particle-box", "download-snap", ReleaseRepository.Host.PURRFECT_GIT)
            )

            TargetApp.REDDIT -> listOf(
                ReleaseRepository("particle-box", "download-reddit"),
                ReleaseRepository("curious-freak", "download-reddit")
            )

            TargetApp.INSTAGRAM -> listOf(
                ReleaseRepository("particle-box", "download-insta", ReleaseRepository.Host.PURRFECT_GIT)
            )

            TargetApp.WHATSAPP -> emptyList()
        }
    }
}

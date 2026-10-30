package me.eternal.purrfect.ui.manager.data

import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.setup.patch.AutoPatchServer
import okhttp3.OkHttpClient
import okhttp3.Request


object Updater {
    const val REDDIT_INSTALLED_RELEASE_TAG_PREF = "reddit_installed_release_tag"

    enum class Channel { STABLE, PRERELEASE }
    enum class UpdateTarget { PURRFECT, REDDIT }

    data class LatestRelease(
        val versionName: String,
        val releaseUrl: String,
        val workflowId: Long?,
        val assetDownloads: Map<String, String> = emptyMap(),
        val repositoryFullName: String = DEFAULT_PURRFECT_REPOSITORY,
        val target: UpdateTarget = UpdateTarget.PURRFECT,
    )

    private data class PurrfectGitRepository(
        val owner: String,
        val name: String,
    ) {
        val fullName: String = "$owner/$name"
        val releasesUrl: String = "https://www.purrfectgit.com/r/$owner/$name/releases"
    }

    private const val DEFAULT_PURRFECT_REPOSITORY = "particle-box/purrfect"
    private val purrfectRepositories = listOf(
        PurrfectGitRepository("particle-box", "purrfect"),
    )
    private val okHttpClient by lazy { OkHttpClient() }
    private val autoPatchServer by lazy { AutoPatchServer() }

    private fun normalizeVersionTag(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V")
    }

    internal fun isVersionGreater(version: String, current: String): Boolean {
        fun segments(raw: String) = raw
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }

        val v = segments(version)
        val c = segments(current)
        val size = maxOf(v.size, c.size)
        for (i in 0 until size) {
            val vi = v.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (vi > ci) return true
            if (vi < ci) return false
        }
        return false
    }

    private fun fetchLatestRelease(channel: Channel): LatestRelease? {
        purrfectRepositories.forEach { repository ->
            fetchLatestReleaseFromRepository(channel, repository)?.let { return it }
        }
        return null
    }

    private fun fetchLatestReleaseFromRepository(channel: Channel, repository: PurrfectGitRepository) = runCatching {
        val endpoint = Request.Builder()
            .url(repository.releasesUrl)
            .build()
        val response = okHttpClient.newCall(endpoint).execute()

        if (!response.isSuccessful) throw Throwable("Failed to fetch releases from ${repository.fullName}: ${response.code}")

        val releasesHtml = response.body?.string() ?: throw Throwable("Empty releases response")
        val releaseUrls = findPurrfectGitReleaseUrls(releasesHtml, repository)
        if (releaseUrls.isEmpty()) throw Throwable("No releases found")

        val currentVersion = BuildConfig.VERSION_NAME
        val latest = releaseUrls.firstNotNullOfOrNull { releaseUrl ->
            val releaseHtml = okHttpClient.newCall(Request.Builder().url(releaseUrl).build()).execute().use {
                if (!it.isSuccessful) return@firstNotNullOfOrNull null
                it.body?.string() ?: return@firstNotNullOfOrNull null
            }
            val tagName = findPurrfectGitReleaseTag(releaseHtml, releaseUrl) ?: return@firstNotNullOfOrNull null
            val normalizedTag = normalizeVersionTag(tagName)
            val isPrerelease = isPurrfectGitPrerelease(tagName, releaseHtml)
            val matchesChannel = when (channel) {
                Channel.STABLE -> !isPrerelease
                Channel.PRERELEASE -> isPrerelease
            }
            if (!matchesChannel || !isVersionGreater(normalizedTag, currentVersion)) return@firstNotNullOfOrNull null
            Triple(normalizedTag, releaseUrl, releaseHtml)
        } ?: throw Throwable("No matching releases found for $channel channel in ${repository.fullName}")

        val latestVersion = latest.first
        if (latestVersion == BuildConfig.VERSION_NAME) return@runCatching null
        val assets = findPurrfectGitReleaseAssets(latest.third, latest.second)

        val assetDownloads = buildMap<String, String> {
            assets.forEach { (name, url) ->
                when {
                    name.contains("arm64") || name.contains("armv8") -> put("arm64", url)
                    name.contains("armeabi") || name.contains("armv7") || name.contains("arm32") -> put("armv7", url)
                }
            }
        }

        LatestRelease(
            versionName = latestVersion,
            releaseUrl = latest.second,
            workflowId = null,
            assetDownloads = assetDownloads,
            repositoryFullName = repository.fullName,
        )
    }.onFailure {
        AbstractLogger.directError("Failed to fetch latest release ($channel) from ${repository.fullName}", it)
    }.getOrNull()

    private fun fetchLatestDebugCI(): LatestRelease? {
        purrfectRepositories.forEach { repository ->
            fetchLatestDebugCIFromRepository(repository)?.let { return it }
        }
        return null
    }

    private fun fetchLatestDebugCIFromRepository(repository: PurrfectGitRepository): LatestRelease? = null

    private fun findPurrfectGitReleaseUrls(html: String, repository: PurrfectGitRepository): List<String> {
        val path = "/r/${repository.owner}/${repository.name}/releases/"
        val releaseLinkRegex = Regex("""href=["']([^"']*$path(?!latest["'/])[^"'?#/]+)["']""")
        return releaseLinkRegex.findAll(html)
            .map { absolutePurrfectGitUrl(it.groupValues[1]) }
            .distinct()
            .toList()
    }

    private fun findPurrfectGitReleaseTag(html: String, releaseUrl: String): String? {
        val candidates = sequenceOf(
            Regex("""(?is)<h[1-3][^>]*>\s*(?:Release\s+)?([^<]+?)\s*</h[1-3]>""").find(html)?.groupValues?.getOrNull(1),
            Regex("""(?is)<title>\s*(?:Release\s+)?([^<]+?)(?:\s+-\s+PurrfectGit)?\s*</title>""").find(html)?.groupValues?.getOrNull(1),
            Regex("""(?i)\btag(?:\s*name)?["'\s:=>-]+v?([0-9][0-9A-Za-z._-]*)""").find(html)?.groupValues?.getOrNull(1),
            releaseUrl.substringAfterLast('/').takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
        )
        return candidates
            .mapNotNull { it?.trim()?.removePrefix("v")?.removePrefix("V") }
            .firstOrNull { it.isNotBlank() && it != "Verify access" }
    }

    private fun isPurrfectGitPrerelease(tagName: String, html: String): Boolean {
        val normalized = "$tagName $html".lowercase()
        return listOf("pre-release", "prerelease", "preview", "alpha", "beta", "rc").any(normalized::contains)
    }

    private fun findPurrfectGitReleaseAssets(html: String, releaseUrl: String): List<Pair<String, String>> {
        val assetRegex = Regex("""href=["']([^"']*/assets/([^"']+?)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        return assetRegex.findAll(html)
            .mapNotNull { match ->
                val url = absolutePurrfectGitUrl(match.groupValues[1], releaseUrl)
                val name = match.groupValues[2].substringAfterLast('/').lowercase()
                name.takeIf { it.isNotBlank() }?.let { it to url }
            }
            .distinctBy { it.second }
            .toList()
    }

    private fun absolutePurrfectGitUrl(path: String, baseUrl: String = "https://www.purrfectgit.com"): String {
        return when {
            path.startsWith("https://", ignoreCase = true) -> path
            path.startsWith("/") -> "https://www.purrfectgit.com$path"
            else -> baseUrl.substringBeforeLast('/') + "/$path"
        }
    }

    private val cache = mutableMapOf<Channel, LatestRelease?>()
    private val redditUpdateCache = mutableMapOf<String, LatestRelease?>()

    fun getLatestRelease(channel: Channel): LatestRelease? {
        return cache.getOrPut(channel) {
            if (BuildConfig.DEBUG && channel == Channel.STABLE) {
                fetchLatestDebugCI() ?: fetchLatestRelease(Channel.STABLE)
            } else {
                fetchLatestRelease(channel)
            }
        }
    }

    fun getLatestRedditUpdate(installedTag: String?): LatestRelease? {
        val currentTag = installedTag
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeVersionTag)
            ?: return null

        return redditUpdateCache.getOrPut(currentTag) {
            runCatching {
                val latest = autoPatchServer.fetchLatestApk(TargetApp.REDDIT) ?: return@runCatching null
                val latestVersion = normalizeVersionTag(latest.tagName)
                if (!isVersionGreater(latestVersion, currentTag)) return@runCatching null
                LatestRelease(
                    versionName = latestVersion,
                    releaseUrl = latest.downloadUrl,
                    workflowId = null,
                    assetDownloads = mapOf("apk" to latest.downloadUrl),
                    target = UpdateTarget.REDDIT,
                )
            }.onFailure {
                AbstractLogger.directError("Failed to fetch latest Reddit update", it)
            }.getOrNull()
        }
    }

    fun clearRedditUpdateCache() {
        redditUpdateCache.clear()
    }
}

package me.eternal.purrfect.common.config.impl

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SmartButton
import me.eternal.purrfect.common.config.ConfigFlag
import me.eternal.purrfect.common.config.ConfigContainer

class RedditConfig : ConfigContainer() {
    inner class AdBlocking : ConfigContainer() {
        val blockPromotedPosts = boolean("block_promoted_posts") { requireRestart() }
        val blockCommentAds = boolean("block_comment_ads") { requireRestart() }
    }

    inner class Premium : ConfigContainer() {
        val unlockRedditPremium = boolean("unlock_reddit_premium") { requireRestart() }
    }

    inner class Links : ConfigContainer() {
        val openLinksInExternalBrowser = boolean("open_links_in_external_browser") { requireRestart() }
        val sanitizeSharingLinks = boolean("sanitize_sharing_links") { requireRestart() }
    }

    inner class Navigation : ConfigContainer() {
        val hideAnswersButton = boolean("hide_answers_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
        val hideChatButton = boolean("hide_chat_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
        val hideCreateButton = boolean("hide_create_button") { requireRestart() }
        val hideGamesButton = boolean("hide_games_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    }

    inner class Shelves : ConfigContainer() {
        val hideRecentlyVisitedShelf = boolean("hide_recently_visited_shelf") { requireRestart() }
        val hideGamesOnRedditShelf = boolean("hide_games_on_reddit_shelf") { requireRestart() }
        val hideRedditProShelf = boolean("hide_reddit_pro_shelf") { requireRestart() }
        val hideAboutShelf = boolean("hide_about_shelf") { requireRestart() }
        val hideResourcesShelf = boolean("hide_resources_shelf") { requireRestart() }
        val hideTrendingTodayShelf = boolean("hide_trending_today_shelf") { requireRestart() }
    }

    inner class Dialogs : ConfigContainer() {
        val disableScreenshotPopup = boolean("disable_screenshot_popup") { requireRestart() }
        val removeNsfwWarningDialog = boolean("remove_nsfw_warning_dialog") { requireRestart() }
        val removeNotificationSuggestionDialog = boolean("remove_notification_suggestion_dialog") { requireRestart() }
    }

    inner class Comments : ConfigContainer() {
        val addScrollToTopButton = boolean("add_scroll_to_top_button") { requireRestart() }
    }

    val adBlocking = container("ad_blocking", AdBlocking()) { icon = Icons.Default.Block }
    val premium = container("premium", Premium()) { icon = Icons.Default.Diamond }
    val links = container("links", Links()) { icon = Icons.AutoMirrored.Filled.OpenInNew }
    val navigation = container("navigation", Navigation()) { icon = Icons.Default.SmartButton }
    val shelves = container("shelves", Shelves()) { icon = Icons.Default.DashboardCustomize }
    val dialogs = container("dialogs", Dialogs()) { icon = Icons.Default.RemoveCircleOutline }
    val comments = container("comments", Comments()) { icon = Icons.AutoMirrored.Filled.Comment }

    private val legacyBlockPromotedPosts = boolean("block_promoted_posts") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyBlockCommentAds = boolean("block_comment_ads") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyUnlockRedditPremium = boolean("unlock_reddit_premium") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyOpenLinksInExternalBrowser = boolean("open_links_in_external_browser") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyDisableScreenshotPopup = boolean("disable_screenshot_popup") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    val hideAnswersButton = boolean("hide_answers_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    val hideChatButton = boolean("hide_chat_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideCreateButton = boolean("hide_create_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    val hideGamesButton = boolean("hide_games_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideRecentlyVisitedShelf = boolean("hide_recently_visited_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideGamesOnRedditShelf = boolean("hide_games_on_reddit_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideRedditProShelf = boolean("hide_reddit_pro_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideAboutShelf = boolean("hide_about_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideResourcesShelf = boolean("hide_resources_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyHideTrendingTodayShelf = boolean("hide_trending_today_shelf") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyRemoveNsfwWarningDialog = boolean("remove_nsfw_warning_dialog") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyRemoveNotificationSuggestionDialog = boolean("remove_notification_suggestion_dialog") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacySanitizeSharingLinks = boolean("sanitize_sharing_links") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }
    private val legacyAddScrollToTopButton = boolean("add_scroll_to_top_button") { requireRestart(); addFlags(ConfigFlag.HIDDEN) }

    fun migrateLegacyFlags() {
        migrate(legacyBlockPromotedPosts, adBlocking.blockPromotedPosts)
        migrate(legacyBlockCommentAds, adBlocking.blockCommentAds)
        migrate(legacyUnlockRedditPremium, premium.unlockRedditPremium)
        migrate(legacyOpenLinksInExternalBrowser, links.openLinksInExternalBrowser)
        migrate(legacyDisableScreenshotPopup, dialogs.disableScreenshotPopup)
        migrate(legacyHideCreateButton, navigation.hideCreateButton)
        migrate(legacyHideRecentlyVisitedShelf, shelves.hideRecentlyVisitedShelf)
        migrate(legacyHideGamesOnRedditShelf, shelves.hideGamesOnRedditShelf)
        migrate(legacyHideRedditProShelf, shelves.hideRedditProShelf)
        migrate(legacyHideAboutShelf, shelves.hideAboutShelf)
        migrate(legacyHideResourcesShelf, shelves.hideResourcesShelf)
        migrate(legacyHideTrendingTodayShelf, shelves.hideTrendingTodayShelf)
        migrate(legacyRemoveNsfwWarningDialog, dialogs.removeNsfwWarningDialog)
        migrate(legacyRemoveNotificationSuggestionDialog, dialogs.removeNotificationSuggestionDialog)
        migrate(legacySanitizeSharingLinks, links.sanitizeSharingLinks)
        migrate(legacyAddScrollToTopButton, comments.addScrollToTopButton)
    }

    fun blockPromotedPostsEnabled() = adBlocking.blockPromotedPosts.get()
    fun blockCommentAdsEnabled() = adBlocking.blockCommentAds.get()
    fun unlockRedditPremiumEnabled() = premium.unlockRedditPremium.get()
    fun openLinksInExternalBrowserEnabled() = links.openLinksInExternalBrowser.get()
    fun disableScreenshotPopupEnabled() = dialogs.disableScreenshotPopup.get()
    fun hideCreateButtonEnabled() = navigation.hideCreateButton.get()
    fun hideRecentlyVisitedShelfEnabled() = shelves.hideRecentlyVisitedShelf.get()
    fun hideGamesOnRedditShelfEnabled() = shelves.hideGamesOnRedditShelf.get()
    fun hideRedditProShelfEnabled() = shelves.hideRedditProShelf.get()
    fun hideAboutShelfEnabled() = shelves.hideAboutShelf.get()
    fun hideResourcesShelfEnabled() = shelves.hideResourcesShelf.get()
    fun hideTrendingTodayShelfEnabled() = shelves.hideTrendingTodayShelf.get()
    fun removeNsfwWarningDialogEnabled() = dialogs.removeNsfwWarningDialog.get()
    fun removeNotificationSuggestionDialogEnabled() = dialogs.removeNotificationSuggestionDialog.get()
    fun sanitizeSharingLinksEnabled() = links.sanitizeSharingLinks.get()
    fun addScrollToTopButtonEnabled() = comments.addScrollToTopButton.get()

    private fun migrate(
        legacy: me.eternal.purrfect.common.config.PropertyValue<Boolean>,
        current: me.eternal.purrfect.common.config.PropertyValue<Boolean>
    ) {
        if (legacy.get() && !current.get()) current.set(true)
        if (legacy.get()) legacy.set(false)
    }
}

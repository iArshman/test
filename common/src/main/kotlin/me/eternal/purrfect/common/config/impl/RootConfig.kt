package me.eternal.purrfect.common.config.impl

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import me.eternal.purrfect.common.config.ConfigContainer
import me.eternal.purrfect.common.config.FeatureNotice

class RootConfig : ConfigContainer() {
    val downloader = container("downloader", DownloaderConfig()) { icon = Icons.Default.Download }
    val userInterface = container("user_interface", UserInterfaceTweaks()) { icon = Icons.Default.RemoveRedEye }
    val messaging = container("messaging", MessagingTweaks()) { icon = Icons.AutoMirrored.Default.Send }
    val global = container("global", Global()) { icon = Icons.Default.MiscellaneousServices }
    val rules = container("rules", Rules()) { icon = Icons.AutoMirrored.Default.Rule }
    val camera = container("camera", Camera()) { icon = Icons.Default.Camera; requireRestart() }
    val streaksReminder = container("streaks_reminder", StreaksReminderConfig()) { icon = Icons.Default.Alarm }
    val experimental = container("experimental", Experimental()) { icon = Icons.Default.Science; addNotices(
        FeatureNotice.UNSTABLE) }
    val scripting = container("scripting", Scripting()) { icon = Icons.Default.DataObject }
    val friendTracker = container("friend_tracker", FriendTrackerConfig()) { icon = Icons.Default.PersonSearch }
    val reddit = container("reddit", RedditConfig()) { icon = Icons.Default.Forum }
    val whatsapp = container("whatsapp", WhatsAppConfig()) { icon = Icons.Default.Forum }
    val instagram = container("instagram", InstagramConfig()) { icon = Icons.Default.PhotoCamera }
}

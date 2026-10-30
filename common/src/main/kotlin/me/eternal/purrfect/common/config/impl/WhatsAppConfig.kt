package me.eternal.purrfect.common.config.impl

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RemoveRedEye
import me.eternal.purrfect.common.config.ConfigFlag
import me.eternal.purrfect.common.config.ConfigContainer

class WhatsAppConfig : ConfigContainer() {
    inner class Channels : ConfigContainer() {
        val hideChannels = boolean("hide_channels") { requireRestart() }
        val hideChannelRecommendations = boolean("hide_channel_recommendations") { requireRestart() }
        val hideCommunitiesTab = boolean("hide_communities_tab") { requireRestart() }
    }

    inner class Privacy : ConfigContainer() {
        val hideTypingIndicators = boolean("hide_typing_indicators") { requireRestart() }
        val hideRecordingAudio = boolean("hide_recording_audio") { requireRestart() }
        val hideDelivered = boolean("hide_delivered") { requireRestart() }
        val hideAudioSeen = boolean("hide_audio_seen") { requireRestart() }
        val hideBlueTicks = boolean("hide_blue_ticks") { requireRestart() }
        val hideStatusView = boolean("hide_status_view") { requireRestart() }
    }

    inner class Messages : ConfigContainer() {
        val hideStartChatting = boolean("hide_start_chatting") { requireRestart() }
        val unlimitedViewOnce = boolean("unlimited_view_once") { requireRestart() }
        val showDeletedMessages = boolean("show_deleted_messages") { requireRestart() }
    }

    inner class UiElements : ConfigContainer() {
        val hideUiElements = boolean("hide_ui_elements")
        val captureUiElements = boolean("capture_ui_elements")
        val liquidClass = boolean("liquid_class")
        val hiddenUiElementIds = string("hidden_ui_element_ids") {
            inputCheck = { true }
            addFlags(ConfigFlag.HIDDEN)
        }
        val hiddenUiElementSelectors = string("hidden_ui_element_selectors") {
            inputCheck = { true }
            addFlags(ConfigFlag.HIDDEN)
        }
    }

    val channels = container("channels", Channels()) { icon = Icons.Default.Forum }
    val privacy = container("privacy", Privacy()) { icon = Icons.Default.PrivacyTip }
    val messages = container("messages", Messages()) { icon = Icons.Default.MarkChatUnread }
    val uiElements = container("ui_elements", UiElements()) { icon = Icons.Default.RemoveRedEye }

    fun hideChannelsEnabled() = channels.hideChannels.get()
    fun hideChannelRecommendationsEnabled() = channels.hideChannelRecommendations.get()
    fun hideCommunitiesTabEnabled() = channels.hideCommunitiesTab.get()
    fun hideTypingIndicatorsEnabled() = privacy.hideTypingIndicators.get()
    fun hideRecordingAudioEnabled() = privacy.hideRecordingAudio.get()
    fun hideDeliveredEnabled() = privacy.hideDelivered.get()
    fun hideAudioSeenEnabled() = privacy.hideAudioSeen.get()
    fun hideStatusViewEnabled() = privacy.hideStatusView.get()
    fun hideStartChattingEnabled() = messages.hideStartChatting.get()
    fun unlimitedViewOnceEnabled() = messages.unlimitedViewOnce.get()
    fun hideBlueTicksEnabled() = privacy.hideBlueTicks.get()
    fun showDeletedMessagesEnabled() = messages.showDeletedMessages.get()
    fun hideUiElementsEnabled() = uiElements.hideUiElements.get()
    fun captureUiElementsEnabled() = uiElements.captureUiElements.get()
    fun liquidClassEnabled() = uiElements.liquidClass.get()
    fun hiddenUiElementIds() = uiElements.hiddenUiElementIds.getNullable().orEmpty()
    fun hiddenUiElementSelectors() = uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
}

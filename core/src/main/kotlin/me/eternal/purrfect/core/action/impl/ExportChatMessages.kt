package me.eternal.purrfect.core.action.impl

import android.app.AlertDialog
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.*
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.database.impl.FriendInfo
import me.eternal.purrfect.common.database.impl.FriendFeedEntry
import me.eternal.purrfect.common.bridge.wrapper.LoggedMessage
import me.eternal.purrfect.common.bridge.wrapper.LoggerWrapper
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectColorSet
import me.eternal.purrfect.core.action.AbstractAction
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.logger.CoreLogger
import me.eternal.purrfect.core.messaging.ConversationExporter
import me.eternal.purrfect.core.messaging.ExportFormat
import me.eternal.purrfect.core.messaging.ExportParams
import me.eternal.purrfect.core.messaging.ExportSortOrder
import me.eternal.purrfect.core.wrapper.impl.Message
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import java.io.File
import java.io.PrintWriter
import java.text.DateFormat
import java.util.*
import kotlin.math.absoluteValue

private data class ExportColorParticipant(
    val userId: String,
    val displayName: String,
    val username: String
)

class ExportChatMessages : AbstractAction() {
    private val translation by lazy { context.translation.getCategory("chat_export") }
    private val dialogLogs = mutableListOf<String>()
    private var dialogTitle by mutableStateOf("")
    private var dialogText by mutableStateOf("")
    private var currentActionDialog: AlertDialog? = null
    private val activeExporters = java.util.concurrent.CopyOnWriteArrayList<ConversationExporter>()

    private fun logDialog(message: String) {
        context.runOnUiThread {
            if (dialogLogs.size > 10) dialogLogs.removeAt(0)
            dialogLogs.add(message)
            context.log.debug("dialog: $message", "ExportChatMessages")
            dialogText = dialogLogs.joinToString("\n")
        }
    }

    private fun setStatus(message: String) {
        context.runOnUiThread {
            dialogTitle = message
        }
    }

    private fun colorToHex(color: Color): String {
        return String.format("#%06X", 0xFFFFFF and color.toArgb())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ExporterDialog(
        getDialog: () -> AlertDialog? = { null }
    ) {
        val skin = LocalPurrfectSkin.current
        fun t(key: String) = translation["exporter_dialog.$key"]

        var feedEntries by remember { mutableStateOf(emptyList<FriendFeedEntry>()) }
        var exportType by remember { mutableStateOf(ExportFormat.HTML) }
        val selectedFeedEntries = remember { mutableStateListOf<FriendFeedEntry>() }
        val messageTypeFilter = remember { mutableStateListOf<ContentType>() }
        var amountOfMessages by remember { mutableIntStateOf(-1) }
        var downloadMedias by remember { mutableStateOf(false) }
        var showConversationPicker by remember { mutableStateOf(false) }
        var showFormatPicker by remember { mutableStateOf(false) }
        var showOrderPicker by remember { mutableStateOf(false) }
        var showMessageTypePicker by remember { mutableStateOf(false) }
        var exportSortOrder by remember { mutableStateOf(ExportSortOrder.NEWEST_TO_OLDEST) }
        val colorOverrides = remember { mutableStateMapOf<String, String>() }
        var colorPickerTarget by remember { mutableStateOf<ExportColorParticipant?>(null) }
        var colorPickerValue by remember { mutableStateOf<Color?>(null) }
        var participants by remember { mutableStateOf<List<ExportColorParticipant>>(emptyList()) }
        val allFriends by rememberAsyncMutableState(null) { context.database.getAllFriends().associateBy { it.userId!! } }
        val myUserId = context.database.myUserId
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(skin.textPrimary.copy(alpha = 0.08f))
                        .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        tint = skin.glowPrimary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                        contentDescription = null
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export conversations",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = skin.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    )
                    Text(
                        text = t("select_conversations_title"),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = skin.textSecondary,
                            fontSize = 13.sp
                        )
                    )
                }
                Text(
                    text = exportType.extension.uppercase(),
                    color = skin.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(skin.textPrimary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            SectionLabel(t("select_conversations_title"), skin)
            GlassField(
                value = selectedFeedEntries
                    .takeIf { it.isNotEmpty() }
                    ?.let { translation.format("exporter_dialog.text_field_selection", "amount" to it.size.toString()) }
                    ?: t("text_field_selection_all"),
                onClick = { showConversationPicker = true },
                skin = skin
            )

            SectionLabel(t("export_file_format_title"), skin)
            GlassField(
                value = exportType.extension.uppercase(),
                onClick = { showFormatPicker = true },
                skin = skin
            )

            SectionLabel(t("sort_order_title"), skin)
            GlassField(
                value = t(
                    if (exportSortOrder == ExportSortOrder.OLDEST_TO_NEWEST) {
                        "sort_order_oldest_to_newest"
                    } else {
                        "sort_order_newest_to_oldest"
                    }
                ),
                onClick = { showOrderPicker = true },
                skin = skin
            )

            SectionLabel(t("message_type_filter_title"), skin)
            GlassField(
                value = messageTypeFilter.takeIf { it.isNotEmpty() }?.let {
                    translation.format("exporter_dialog.text_field_selection", "amount" to it.size.toString())
                } ?: t("text_field_selection_all"),
                onClick = { showMessageTypePicker = true },
                skin = skin
            )

            SectionLabel(t("amount_of_messages_title"), skin)
            GlassTextField(
                value = amountOfMessages.takeIf { it != -1 }?.toString() ?: "",
                onValueChange = { amountOfMessages = it.toIntOrNull()?.absoluteValue ?: -1 },
                placeholder = "Unlimited",
                onImeDone = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
                skin = skin
            )

            SectionLabel(t("download_medias_title"), skin)
            NeonToggle(
                checked = downloadMedias,
                onCheckedChange = { downloadMedias = it },
                label = translation["exporter_dialog.download_medias_title"],
                skin = skin
            )

            SectionLabel("Participant colors", skin)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 240.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(skin.textPrimary.copy(alpha = 0.05f))
                    .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (participants.isEmpty()) {
                        BasicText(
                            text = "Select conversations to customize participant colors.",
                            style = TextStyle(color = skin.textSecondary, fontSize = 12.sp)
                        )
                    } else {
                        participants.forEach { participant ->
                            val colorHex = colorOverrides[participant.userId]
                            val color = colorHex?.let { Color(AndroidColor.parseColor(it)) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(skin.textPrimary.copy(alpha = 0.04f))
                                    .border(1.dp, skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        colorPickerTarget = participant
                                        colorPickerValue = color
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ColorSwatch(color = color, skin = skin)
                                Column(modifier = Modifier.weight(1f)) {
                                    BasicText(
                                        text = participant.displayName,
                                        style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    BasicText(
                                        text = participant.username,
                                        style = TextStyle(color = skin.textSecondary, fontSize = 12.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicText(
                                    text = colorHex ?: "Auto",
                                    style = TextStyle(color = skin.textSecondary, fontSize = 11.sp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecondaryButton(
                    text = translation["dialog_negative_button"],
                    modifier = Modifier.weight(1f),
                    onClick = { getDialog()?.dismiss() },
                    skin = skin
                )
                PrimaryButton(
                    text = translation["dialog_positive_button"],
                    modifier = Modifier.weight(1f),
                    enabled = selectedFeedEntries.isNotEmpty() || feedEntries.isNotEmpty(),
                    onClick = {
                        val selection = if (selectedFeedEntries.isEmpty()) feedEntries else selectedFeedEntries.toList()
                        exportChatForConversations(
                            selection,
                            ExportParams(
                                exportFormat = exportType,
                                sortOrder = exportSortOrder,
                                messageTypeFilter = messageTypeFilter.takeIf { it.isNotEmpty() }?.toList(),
                                amountOfMessages = amountOfMessages.takeIf { it != -1 },
                                downloadMedias = downloadMedias,
                                colorOverrides = colorOverrides.takeIf { it.isNotEmpty() }?.toMap()
                            )
                        )
                    },
                    skin = skin
                )
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                feedEntries = context.database.getFeedEntries(500)
            }
        }

        LaunchedEffect(selectedFeedEntries.toList(), allFriends) {
            withContext(Dispatchers.IO) {
                val selection = selectedFeedEntries.toList()
                if (selection.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        participants = emptyList()
                    }
                    return@withContext
                }
                val participantsMap = linkedMapOf<String, ExportColorParticipant>()
                selection.forEach { entry ->
                    val userIds = context.database.getConversationParticipants(entry.key!!, useCache = false) ?: emptyList()
                    userIds.forEach { userId ->
                        if (participantsMap.containsKey(userId)) return@forEach
                        val friend = allFriends?.get(userId) ?: context.database.getFriendInfo(userId)
                        val displayName = friend?.displayName ?: friend?.mutableUsername ?: userId
                        val username = friend?.mutableUsername ?: userId
                        participantsMap[userId] = ExportColorParticipant(userId, displayName, username)
                    }
                }
                withContext(Dispatchers.Main) {
                    participants = participantsMap.values.toList()
                }
            }
        }

        colorPickerTarget?.let { target ->
            Dialog(
                onDismissRequest = { colorPickerTarget = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                ExportColorPickerDialog(
                    participant = target,
                    initialColor = colorPickerValue,
                    onSave = { color ->
                        if (color == null) {
                            colorOverrides.remove(target.userId)
                        } else {
                            colorOverrides[target.userId] = colorToHex(color)
                        }
                        colorPickerTarget = null
                    },
                    onClear = {
                        colorOverrides.remove(target.userId)
                        colorPickerTarget = null
                    }
                )
            }
        }

        if (showConversationPicker) {
            SelectorPopup(
                title = t("select_conversations_title"),
                onDismiss = { showConversationPicker = false },
                skin = skin
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp * 0.55f))
                ) {
                    items(feedEntries, key = { it.key!! }) { feedEntry ->
                        val isSelected = selectedFeedEntries.contains(feedEntry)
                        SelectorRow(
                            title = remember(feedEntry) {
                                (if (feedEntry.conversationType == 1) feedEntry.feedDisplayName else feedEntry.participants?.filter { it != myUserId }?.firstOrNull()?.let { userId ->
                                    allFriends?.get(userId)?.let { friend -> friend.displayName?.let { "$it (${friend.mutableUsername})" } ?: friend.mutableUsername }
                                }) ?: "Unknown"
                            },
                            subtitle = feedEntry.takeIf { it.conversationType == 1 }?.let {
                                "${feedEntry.participantsSize} participants"
                            },
                            checked = isSelected,
                            onToggle = {
                                if (isSelected) selectedFeedEntries -= feedEntry else selectedFeedEntries += feedEntry
                            },
                            skin = skin
                        )
                    }

                    if (feedEntries.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SecondaryButton(
                                    text = t("text_field_selection_all"),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedFeedEntries.clear()
                                        selectedFeedEntries.addAll(feedEntries)
                                    },
                                    skin = skin
                                )
                                SecondaryButton(
                                    text = translation["dialog_negative_button"],
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedFeedEntries.clear() },
                                    skin = skin
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showFormatPicker) {
            SelectorPopup(
                title = t("export_file_format_title"),
                onDismiss = { showFormatPicker = false },
                skin = skin
            ) {
                ExportFormat.entries.forEach { exportFormat ->
                    SelectorRow(
                        title = exportFormat.name,
                        subtitle = exportFormat.extension.uppercase(),
                        checked = exportType == exportFormat,
                        onToggle = {
                            exportType = exportFormat
                            showFormatPicker = false
                        },
                        skin = skin
                    )
                }
            }
        }

        if (showMessageTypePicker) {
            SelectorPopup(
                title = t("message_type_filter_title"),
                onDismiss = { showMessageTypePicker = false },
                skin = skin
            ) {
                arrayOf(
                    ContentType.CHAT,
                    ContentType.SNAP,
                    ContentType.EXTERNAL_MEDIA,
                    ContentType.NOTE,
                    ContentType.STICKER
                ).forEach { contentType ->
                    val isSelected = messageTypeFilter.contains(contentType)
                    SelectorRow(
                        title = contentType.name.lowercase().replaceFirstChar { it.uppercase() },
                        subtitle = null,
                        checked = isSelected,
                        onToggle = {
                            if (isSelected) messageTypeFilter -= contentType else messageTypeFilter += contentType
                        },
                        skin = skin
                    )
                }
                SecondaryButton(
                    text = t("text_field_selection_all"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        messageTypeFilter.clear()
                        showMessageTypePicker = false
                    },
                    skin = skin
                )
            }
        }

        if (showOrderPicker) {
            SelectorPopup(
                title = t("sort_order_title"),
                onDismiss = { showOrderPicker = false },
                skin = skin
            ) {
                SelectorRow(
                    title = t("sort_order_newest_to_oldest"),
                    subtitle = null,
                    checked = exportSortOrder == ExportSortOrder.NEWEST_TO_OLDEST,
                    onToggle = {
                        exportSortOrder = ExportSortOrder.NEWEST_TO_OLDEST
                        showOrderPicker = false
                    },
                    skin = skin
                )
                SelectorRow(
                    title = t("sort_order_oldest_to_newest"),
                    subtitle = null,
                    checked = exportSortOrder == ExportSortOrder.OLDEST_TO_NEWEST,
                    onToggle = {
                        exportSortOrder = ExportSortOrder.OLDEST_TO_NEWEST
                        showOrderPicker = false
                    },
                    skin = skin
                )
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String, skin: PurrfectColorSet) {
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = TextStyle(
                fontSize = 12.sp,
                color = skin.textSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Start
            )
        )
    }

    @Composable
    private fun GlassField(
        value: String,
        onClick: () -> Unit,
        skin: PurrfectColorSet
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(skin.textPrimary.copy(alpha = 0.05f))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicText(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(fontSize = 14.sp, color = skin.textPrimary, textAlign = TextAlign.Start, fontWeight = FontWeight.Medium)
            )
        }
    }

    @Composable
    private fun GlassTextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        onImeDone: () -> Unit,
        skin: PurrfectColorSet
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = skin.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(skin.glowSecondary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(onDone = { onImeDone() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(skin.textPrimary.copy(alpha = 0.05f))
                        .border(1.dp, skin.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    if (value.isEmpty()) {
                        BasicText(
                            text = placeholder,
                            style = TextStyle(color = skin.textSecondary, fontSize = 14.sp)
                        )
                    }
                    inner()
                }
            }
        )
    }

    @Composable
    private fun NeonToggle(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        label: String,
        skin: PurrfectColorSet
    ) {
        val knobOffset by animateFloatAsState(targetValue = if (checked) 20f else 0f, animationSpec = tween(220), label = "toggle")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(skin.textPrimary.copy(alpha = 0.04f))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 26.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(skin.textPrimary.copy(alpha = 0.1f))
                    .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(40.dp))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .offset(x = knobOffset.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
                )
            }
            Column {
                BasicText(
                    text = label,
                    style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                BasicText(
                    text = if (checked) "Media files included" else "Only text content",
                    style = TextStyle(color = skin.textSecondary, fontSize = 11.sp)
                )
            }
        }
    }

    @Composable
    private fun PrimaryButton(
        text: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        onClick: () -> Unit,
        skin: PurrfectColorSet
    ) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .alpha(if (enabled) 1f else 0.6f)
                .background(Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(color = skin.primaryButtonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
        }
    }

    @Composable
    private fun SecondaryButton(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        skin: PurrfectColorSet
    ) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .background(skin.textPrimary.copy(alpha = 0.05f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(color = skin.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }

    @Composable
    private fun SelectorPopup(
        title: String,
        onDismiss: () -> Unit,
        skin: PurrfectColorSet,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Popup(
            alignment = Alignment.Center,
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
            onDismissRequest = onDismiss
        ) {
            val shape = RoundedCornerShape(22.dp)
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(0.85f)
                    .clip(shape)
                    .border(1.dp, skin.textPrimary.copy(alpha = 0.15f), shape),
                shape = shape,
                color = skin.cardOverlayColor,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(skin.cardOverlay)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BasicText(
                            text = title,
                            style = TextStyle(color = skin.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(skin.textPrimary.copy(alpha = 0.08f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(18.dp),
                                tint = skin.textPrimary
                            )
                        }
                    }
                    content()
                }
            }
        }
    }

    @Composable
    private fun SelectorRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onToggle: () -> Unit,
        skin: PurrfectColorSet
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) skin.glowPrimary.copy(alpha = 0.12f) else skin.textPrimary.copy(alpha = 0.04f))
                .border(1.dp, if (checked) skin.glowPrimary.copy(alpha = 0.4f) else skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCheckbox(checked = checked, skin = skin)
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                BasicText(
                    text = title,
                    style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    BasicText(
                        text = it,
                        style = TextStyle(color = skin.textSecondary, fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun GlassCheckbox(checked: Boolean, skin: PurrfectColorSet) {
        val animatedAlpha by animateFloatAsState(targetValue = if (checked) 1f else 0f, animationSpec = tween(200), label = "checkbox")
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                .background(skin.textPrimary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(visible = checked) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skin.glowPrimary.copy(alpha = animatedAlpha)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Checked",
                        tint = skin.cardOverlayColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ExportProgressDialog(
        onCancel: () -> Unit,
        skin: PurrfectColorSet
    ) {
        val scrollState = rememberScrollState()
        val shape = RoundedCornerShape(26.dp)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, skin.textPrimary.copy(alpha = 0.15f), shape),
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = skin.cardOverlayColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(skin.cardOverlay)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = dialogTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = skin.textPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 280.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(skin.textPrimary.copy(alpha = 0.05f))
                        .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    BasicText(
                        text = dialogText,
                        style = TextStyle(color = skin.textSecondary, fontSize = 12.sp)
                    )
                }
                SecondaryButton(
                    text = translation["dialog_negative_button"],
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancel,
                    skin = skin
                )
            }
        }
    }

    @Composable
    private fun ColorSwatch(color: Color?, skin: PurrfectColorSet) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color ?: skin.textPrimary.copy(alpha = 0.1f))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (color == null) {
                BasicText(
                    text = "A",
                    style = TextStyle(color = skin.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }

    @Composable
    private fun ExportColorPickerDialog(
        participant: ExportColorParticipant,
        initialColor: Color?,
        onSave: (Color?) -> Unit,
        onClear: () -> Unit
    ) {
        val skin = LocalPurrfectSkin.current
        var currentColor by remember { mutableStateOf(initialColor ?: skin.textPrimary) }
        val controller = rememberColorPickerController()
        var colorHexValue by remember { mutableStateOf(colorToHex(currentColor).removePrefix("#")) }

        LaunchedEffect(Unit) {
            controller.selectByColor(currentColor, false)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = skin.cardOverlayColor,
            tonalElevation = 12.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(skin.cardOverlay)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Color for ${participant.displayName}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = skin.textPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                TextField(
                    value = colorHexValue,
                    onValueChange = { value ->
                        colorHexValue = value
                        runCatching {
                            val parsed = Color(AndroidColor.parseColor("#$value"))
                            currentColor = parsed
                            controller.selectByColor(parsed, true)
                        }
                    },
                    label = { Text(text = "Hex Color") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = skin.textPrimary.copy(alpha = 0.08f),
                        unfocusedContainerColor = skin.textPrimary.copy(alpha = 0.05f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = skin.glowSecondary,
                        focusedTextColor = skin.textPrimary,
                        unfocusedTextColor = skin.textPrimary
                    )
                )
                HsvColorPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    controller = controller,
                    onColorChanged = {
                        if (!it.fromUser) return@HsvColorPicker
                        currentColor = it.color
                        colorHexValue = colorToHex(it.color).removePrefix("#")
                    }
                )
                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    controller = controller
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton(
                        text = "Auto",
                        modifier = Modifier.weight(1f),
                        onClick = onClear,
                        skin = skin
                    )
                    PrimaryButton(
                        text = "Save",
                        modifier = Modifier.weight(1f),
                        onClick = { onSave(currentColor) },
                        skin = skin
                    )
                }
            }
        }
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                PurrfectOverlayTheme(null) {
                    val skin = LocalPurrfectSkin.current
                    val isAether = skin.id == "AETHER"
                    val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(26.dp) else RoundedCornerShape(26.dp)

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                                .clip(shape)
                                .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), shape),
                            shape = shape,
                            color = skin.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp
                        ) {
                            ExporterDialog { alertDialog }
                        }
                    }
                }
            }.apply {
                window?.setGravity(android.view.Gravity.CENTER)
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }

    private fun exportChatForConversations(
        conversations: List<FriendFeedEntry>,
        exportParams: ExportParams,
    ) {
        dialogLogs.clear()
        val jobs = mutableListOf<Job>()
        dialogTitle = translation["exporting_chats"]
        dialogText = ""

        val conversationSize = translation.format("processing_chats", "amount" to conversations.size.toString())
        
        logDialog(conversationSize)

        val exportJob = context.coroutineScope.launch {
            conversations.forEach { conversation ->
                launch {
                    runCatching {
                        exportFullConversation(conversation, exportParams)
                    }.onFailure {
                        if (it is CancellationException) throw it
                        logDialog(translation.format("export_fail", "conversation" to conversation.key.toString()))
                        logDialog(it.stackTraceToString())
                        CoreLogger.xposedLog(it)
                    }
                }.also { jobs.add(it) }
            }
            jobs.joinAll()
            logDialog(translation["finished"])
        }

        currentActionDialog = createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            PurrfectOverlayTheme(null) {
                val skin = LocalPurrfectSkin.current
                ExportProgressDialog(
                    onCancel = {
                        exportJob.cancel()
                        jobs.forEach { it.cancel() }
                        activeExporters.forEach { it.cancel() }
                        activeExporters.clear()
                        alertDialog.dismiss()
                    },
                    skin = skin
                )
            }
        }.apply {
            setCanceledOnTouchOutside(false)
            show()
        }
    }

    private suspend fun fetchMessagesPaginated(conversationId: String, lastMessageId: Long, amount: Int): List<Message> = runBlocking {
        for (i in 0..5) {
            val messages: List<Message>? = suspendCancellableCoroutine { continuation ->
                context.feature(Messaging::class).conversationManager?.fetchConversationWithMessagesPaginated(conversationId,
                    lastMessageId,
                    amount, onSuccess = { messages ->
                        continuation.resumeWith(Result.success(messages))
                    }, onError = {
                        continuation.resumeWith(Result.success(null))
                    }) ?: continuation.resumeWith(Result.success(null))
            }
            if (messages != null) return@runBlocking messages
            logDialog("Retrying in 1 second...")
            delay(1000)
        }
        logDialog("Failed to fetch messages")
        emptyList()
    }

    private data class ExportTarget(
        val outputFile: File,
        val finalize: (File) -> String
    )

    private fun resolveExportTarget(fileName: String, mimeType: String): ExportTarget {
        val configuredFolder = context.config.downloader.saveFolder.get()?.trim().orEmpty()
        val defaultTarget = {
            val publicFolder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Purrfect"
            ).also { if (!it.exists()) it.mkdirs() }
            val outputFile = publicFolder.resolve(fileName).also { if (it.exists()) it.delete() }
            ExportTarget(outputFile) { file -> file.absolutePath }
        }

        if (configuredFolder.isBlank()) {
            return defaultTarget()
        }

        val outputFolder = runCatching {
            DocumentFile.fromTreeUri(context.androidContext, Uri.parse(configuredFolder))
        }.getOrNull()

        if (outputFolder == null || !outputFolder.canWrite()) {
            return defaultTarget()
        }

        val tempFile = File(context.androidContext.cacheDir, fileName).also {
            if (it.exists()) it.delete()
        }
        return ExportTarget(tempFile) { file ->
            val outputFile = outputFolder.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Failed to create export file")
            context.androidContext.contentResolver.openOutputStream(outputFile.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Failed to write export file")
            outputFile.uri.toString()
        }
    }

    private fun fetchLoggerMessages(conversationId: String): List<LoggedMessage> {
        return runCatching {
            val loggerWrapper = LoggerWrapper(context.androidContext)
            val messages = mutableListOf<LoggedMessage>()
            var fromTimestamp = Long.MAX_VALUE
            while (true) {
                val batch = loggerWrapper.fetchMessages(conversationId, fromTimestamp, 500, reverseOrder = true)
                if (batch.isEmpty()) break
                messages.addAll(batch)
                fromTimestamp = batch.last().sendTimestamp
            }
            messages
        }.getOrDefault(emptyList())
    }

    private fun messageSortKey(message: Message): Long {
        return message.orderKey
            ?: message.messageMetadata?.createdAt
            ?: message.messageDescriptor?.messageId
            ?: Long.MIN_VALUE
    }

    private suspend fun exportFullConversation(
        feedEntry: FriendFeedEntry,
        exportParams: ExportParams,
    ) = withContext(Dispatchers.IO) {
        val conversationId = feedEntry.key!!
        val conversationParticipants = context.database.getConversationParticipants(feedEntry.key!!, useCache = false)
            ?.mapNotNull {
                context.database.getFriendInfo(it)
            }?.associateBy { it.userId!! } ?: emptyMap()

        val loggerMessages = fetchLoggerMessages(conversationId)
        val participantMap = conversationParticipants.toMutableMap().apply {
            loggerMessages.forEach { message ->
                if (containsKey(message.userId)) return@forEach
                this[message.userId] = FriendInfo(
                    userId = message.userId,
                    displayName = message.username,
                    username = message.username,
                    usernameForSorting = message.username
                )
            }
        }

        val conversationName = feedEntry.feedDisplayName ?: conversationParticipants.values.take(3).joinToString("_") { it.mutableUsername ?: "" }

        val outputName = "conversation_${conversationName}_${System.currentTimeMillis()}.${exportParams.exportFormat.extension}"
        val mimeType = when (exportParams.exportFormat) {
            ExportFormat.JSON -> "application/json"
            ExportFormat.TEXT -> "text/plain"
            ExportFormat.HTML -> "text/html"
        }
        val outputTarget = resolveExportTarget(outputName, mimeType)
        val outputFile = outputTarget.outputFile

        logDialog(translation.format("exporting_message", "conversation" to conversationName))

        val conversationExporter = ConversationExporter(
            context = context,
            friendFeedEntry = feedEntry,
            conversationParticipants = participantMap,
            exportParams = exportParams,
            cacheFolder = context.androidContext.cacheDir.resolve("chat_export").also { if (!it.exists()) it.mkdirs() },
            outputFile = outputFile,
        ).apply { init(); printLog = {
            logDialog(it.toString())
        } }

        activeExporters.add(conversationExporter)
        try {
            ensureActive()
            var foundMessageCount = 0
            val exportedOrderKeys = mutableSetOf<Long>()
            val fetchedMessages = mutableListOf<Message>()
            val seenMessageKeys = mutableSetOf<String>()

            var lastMessageId: Long? = null
            fetchMessagesPaginated(conversationId, Long.MAX_VALUE, amount = 1).firstOrNull()?.also { message ->
                val messageKey = message.orderKey?.toString() ?: message.messageDescriptor?.messageId?.toString()
                if (messageKey != null && seenMessageKeys.add(messageKey)) {
                    fetchedMessages.add(message)
                }
                lastMessageId = message.messageDescriptor?.messageId
            }

            if (lastMessageId == null && fetchedMessages.isEmpty()) {
                logDialog(translation["no_messages_found"])
            }

            while (lastMessageId != null) {
                ensureActive()
                val pagedMessages = fetchMessagesPaginated(conversationId, lastMessageId, amount = 500)
                if (pagedMessages.isEmpty()) break

                pagedMessages.firstOrNull()?.let {
                    lastMessageId = it.messageDescriptor!!.messageId!!
                }

                pagedMessages.forEach { message ->
                    val messageKey = message.orderKey?.toString() ?: message.messageDescriptor?.messageId?.toString()
                    if (messageKey != null && seenMessageKeys.add(messageKey)) {
                        fetchedMessages.add(message)
                    }
                }
            }

            ensureActive()
            val filteredMessages = exportParams.messageTypeFilter?.let { filter ->
                fetchedMessages.filter { message ->
                    val contentType = message.messageContent?.contentType ?: return@filter false
                    filter.contains(contentType)
                }
            } ?: fetchedMessages

            val sortedMessages = when (exportParams.sortOrder) {
                ExportSortOrder.OLDEST_TO_NEWEST -> filteredMessages.sortedBy { messageSortKey(it) }
                ExportSortOrder.NEWEST_TO_OLDEST -> filteredMessages.sortedByDescending { messageSortKey(it) }
            }

            val messagesToWrite = exportParams.amountOfMessages?.let { limit ->
                sortedMessages.take(limit)
            } ?: sortedMessages

            messagesToWrite.forEach { message ->
                ensureActive()
                conversationExporter.readMessage(message)
                foundMessageCount++
                message.orderKey?.let { exportedOrderKeys.add(it) }
                setStatus("Exporting (found ${foundMessageCount})")
            }

            if (loggerMessages.isNotEmpty() && (exportParams.amountOfMessages == null || foundMessageCount < exportParams.amountOfMessages)) {
                ensureActive()
                val parsedLoggerMessages = loggerMessages.mapNotNull { conversationExporter.parseLoggedMessage(it) }
                val sortedLoggerMessages = when (exportParams.sortOrder) {
                    ExportSortOrder.OLDEST_TO_NEWEST -> parsedLoggerMessages.sortedBy { it.orderKey }
                    ExportSortOrder.NEWEST_TO_OLDEST -> parsedLoggerMessages.sortedByDescending { it.orderKey }
                }
                for (loggedMessage in sortedLoggerMessages) {
                    ensureActive()
                    if (exportedOrderKeys.contains(loggedMessage.orderKey)) continue
                    val filter = exportParams.messageTypeFilter
                    if (filter != null && !filter.contains(loggedMessage.contentType)) continue
                    if (exportParams.amountOfMessages != null && foundMessageCount >= exportParams.amountOfMessages) break
                    conversationExporter.readLoggedMessage(loggedMessage)
                    foundMessageCount++
                    setStatus("Exporting (found ${foundMessageCount})")
                }
            }

            ensureActive()
            if (exportParams.exportFormat == ExportFormat.HTML) conversationExporter.awaitDownload()
            conversationExporter.close()
            logDialog(translation["writing_output"])
            dialogLogs.clear()
            val exportedPath = runCatching { outputTarget.finalize(outputFile) }.getOrElse { error ->
                logDialog("Failed to write export output")
                logDialog(error.toString())
                context.log.error("Failed to finalize chat export", error)
                return@withContext
            }
            if (outputFile.parentFile == context.androidContext.cacheDir) {
                outputFile.delete()
            }
            logDialog("\n" + translation.format("exported_to",
                "path" to exportedPath
            ) + "\n")
        } finally {
            activeExporters.remove(conversationExporter)
        }
    }
}

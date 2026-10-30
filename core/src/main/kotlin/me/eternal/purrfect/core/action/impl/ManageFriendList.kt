package me.eternal.purrfect.core.action.impl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.People
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.eternal.purrfect.common.data.FriendLinkType
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.core.action.AbstractAction
import me.eternal.purrfect.core.event.events.impl.ActivityResultEvent
import me.eternal.purrfect.core.features.impl.experiments.AddFriendSourceSpoof
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.util.ktx.findStaticObjectFieldByType
import me.eternal.purrfect.core.util.EvictingMap
import me.eternal.purrfect.core.wrapper.impl.Snapchatter
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.common.util.snap.RemoteMediaResolver
import me.eternal.purrfect.mapper.impl.FriendRelationshipChangerMapper
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.*

class ManageFriendList : AbstractAction() {
    private val translation by lazy { context.translation.getCategory("friend_list") }
    
    private var pendingPickerAction: Pair<Int, (data: Uri) -> Unit>? = null
    private val uuidRegex = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE)
    
    private fun getUserIdBlacklist() = arrayOf(
        context.database.myUserId,
        "b42f1f70-5a8b-4c53-8c25-34e7ec9e6781",
        "84ee8839-3911-492d-8b94-72dd80f3713a",
    )

    private fun addFriend(userId: String) {
        val friendRelationshipChangerInstance = context.feature(AddFriendSourceSpoof::class).friendRelationshipChangerInstance
            ?: run {
                context.longToast(context.translation["toast_friend_add_unavailable"])
                return
            }

        context.mappings.useMapper(FriendRelationshipChangerMapper::class) {
            runCatching {
                val f9lClass = helperClass.getAsClass() ?: return@runCatching context.log.error("Could not find FriendRelationshipChanger helper class")
                val addFriendMethodName = addFriend14Method.get() ?: return@runCatching context.log.error("Could not find add friend method name")
                val sourceTypeClass = sourceType.getAsClass() ?: return@runCatching context.log.error("Could not find source type class")
                val pageTypeClass = pageType.getAsClass() ?: return@runCatching context.log.error("Could not find page type class")

                val method = f9lClass.methods.firstOrNull { it.name == addFriendMethodName }
                    ?: f9lClass.declaredMethods.firstOrNull { it.name == addFriendMethodName }
                    ?: return@runCatching context.log.error("Could not find $addFriendMethodName method")

                fun findStaticField(clazz: Class<*>): Any? {
                    return clazz.findStaticObjectFieldByType(clazz)
                }

                val enumClass = method.parameterTypes[2]
                val enumConstants = enumClass.enumConstants ?: enumClass.getMethod("values").invoke(null) as? Array<*>
                    ?: return@runCatching context.log.error("Could not get enum constants")
                val addedByUsername = enumConstants.firstOrNull { it.toString().contains("USERNAME", ignoreCase = true) }
                    ?: return@runCatching context.log.error("Could not find ADDED_BY_USERNAME enum")

                val sourceTypeDefault = findStaticField(sourceTypeClass)
                    ?: return@runCatching context.log.error("Could not find source type static field")

                val pageTypeDefault = findStaticField(pageTypeClass)
                    ?: return@runCatching context.log.error("Could not find page type static field")

                method.isAccessible = true
                val result = method.invoke(
                    null,
                    friendRelationshipChangerInstance,
                    userId,
                    addedByUsername,
                    sourceTypeDefault,
                    pageTypeDefault,
                    null, null, null, null, null,
                    null, null, null,
                    4064
                )

                result?.javaClass?.methods?.firstOrNull {
                    it.name == "subscribe" && it.parameterCount == 0
                }?.let { subscribeMethod ->
                    subscribeMethod.isAccessible = true
                    subscribeMethod.invoke(result)
                }
            }.onFailure {
                context.log.error("Failed to add friend $userId", it)
                context.longToast(
                    context.translation.format("toast_friend_add_failed", "message" to (it.message ?: ""))
                )
            }
        }
    }

    override fun onActivityCreate() {
        context.event.subscribe(ActivityResultEvent::class) { event ->
            pendingPickerAction?.takeIf { it.first == event.requestCode }?.let {
                pendingPickerAction = null
                event.canceled = true
                it.second(event.intent.data!!)
            }
        }
    }

    private fun exportFriends(userIds: List<String>) {
        pendingPickerAction = Random.nextInt(0, 65535) to { data ->
            context.androidContext.contentResolver.openOutputStream(data)?.bufferedWriter()?.use { writer ->
                userIds.forEach { writer.write(it); writer.newLine() }
            }
            context.longToast(
                context.translation.format("toast_friends_exported", "count" to userIds.size.toString())
            )
        }
        context.mainActivity?.startActivityForResult(
            Intent.createChooser(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "my_friends.txt")
            }, "Select a location to save the file"),
            pendingPickerAction!!.first
        )
    }

    private val userIdToSnapchatter = mutableMapOf<String, Snapchatter>()

    @Composable
    private fun ManagerDialog() {
        val skin = LocalPurrfectSkin.current
        val isAether = skin.id == "AETHER"
        val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(26.dp) else RoundedCornerShape(26.dp)
        
        val pendingFriendRequests = remember { mutableStateMapOf<String, Job>() }
        var fetchedFriends by remember { mutableStateOf<List<String>?>(null) }
        val coroutineScope = rememberCoroutineScope()
        val bitmojiCache = remember { EvictingMap<String, Bitmap>(50) }
        val noBitmojiBitmap = remember { BitmapFactory.decodeResource(context.resources, android.R.drawable.ic_menu_report_image).asImageBitmap() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (fetchedFriends == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
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
                            imageVector = Icons.Filled.People,
                            contentDescription = null,
                            tint = skin.glowPrimary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(28.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            translation.get("manage_title"),
                            color = skin.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = translation.get("export_description"),
                            color = skin.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PrimaryButton(
                        text = translation.get("export_friends"),
                        modifier = Modifier.weight(1f),
                        skin = skin
                    ) {
                        exportFriends(context.database.getAllFriends().filter { it.friendLinkType == FriendLinkType.MUTUAL.value && it.addedTimestamp > 0L }.mapNotNull { it.userId })
                    }
                    SecondaryButton(
                        text = translation.get("import_from_file"),
                        modifier = Modifier.weight(1f),
                        skin = skin
                    ) {
                        pendingPickerAction = Random.nextInt(0, 65535) to { data ->
                            runCatching {
                                fetchedFriends = context.androidContext.contentResolver.openInputStream(data)?.bufferedReader()?.readLines()
                                    ?.mapNotNull { line -> uuidRegex.find(line)?.value?.lowercase() }
                                    ?.distinct()
                                    ?.toMutableList() ?: mutableListOf()
                            }.onFailure {
                                context.log.error("Failed to import friends", it)
                                context.longToast(
                                    context.translation.format(
                                        "toast_friends_import_failed",
                                        "message" to (it.message ?: "")
                                    )
                                )
                            }
                        }
                        context.mainActivity?.startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, "Select a file"), pendingPickerAction!!.first)
                    }
                }
                PrimaryButton(
                    text = translation.get("load_suggested_friends"),
                    modifier = Modifier.fillMaxWidth(),
                    skin = skin
                ) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val blacklist = getUserIdBlacklist()
                        val suggestedFriends = context.database.getAllFriends().filter { it.userId !in blacklist && it.friendLinkType == FriendLinkType.SUGGESTED.value }.sortedByDescending { it.addedTimestamp }.mapNotNull { it.userId }
                        withContext(Dispatchers.Main) { fetchedFriends = suggestedFriends }
                    }
                }
            } else {
                var searchQuery by remember { mutableStateOf("") }

                val filteredFriends = remember(fetchedFriends, searchQuery) {
                    val friends = fetchedFriends ?: emptyList()
                    val sorted = { list: List<String> -> list.sortedByDescending { context.database.getFriendInfo(it)?.addedTimestamp ?: 0L } }
                    if (searchQuery.isBlank()) sorted(friends) else sorted(friends.filter { userId ->
                        val info = context.database.getFriendInfo(userId)
                        info?.mutableUsername?.contains(searchQuery, ignoreCase = true) == true || info?.displayName?.contains(searchQuery, ignoreCase = true) == true || userId.contains(searchQuery, ignoreCase = true)
                    })
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(skin.textPrimary.copy(alpha = 0.08f))
                                .border(1.dp, skin.textPrimary.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                                .clickable { fetchedFriends = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = context.translation["common.back"],
                                tint = skin.textPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = translation.get("manage_title"),
                                color = skin.textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.size(46.dp))
                    }

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(skin.textPrimary.copy(alpha = 0.05f))
                            .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        singleLine = true,
                        textStyle = TextStyle(color = skin.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(skin.glowSecondary),
                        decorationBox = { innerTextField ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = skin.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        BasicText(
                                            "Search...",
                                            style = TextStyle(color = skin.textSecondary, fontSize = 14.sp)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 340.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = skin.textPrimary.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        ) {
                            items(filteredFriends) { userId ->
                                var friendInfo by remember(userId) { mutableStateOf(context.database.getFriendInfo(userId)) }
                                var friendLinkType by remember(userId) { mutableStateOf(friendInfo?.let { FriendLinkType.fromValue(it.friendLinkType) }) }

                                val isActuallyAdded = friendInfo?.let { info ->
                                    friendLinkType != null && info.addedTimestamp > 0 &&
                                        (friendLinkType == FriendLinkType.MUTUAL || friendLinkType == FriendLinkType.OUTGOING)
                                } ?: false

                                var friendSnapchatter by remember(userId) { mutableStateOf<Snapchatter?>(null) }

                                LaunchedEffect(userId) {
                                    friendSnapchatter = userIdToSnapchatter[userId] ?: run {
                                        withContext(Dispatchers.IO) {
                                            context.feature(Messaging::class).fetchSnapchatterInfos(listOf(userId)).firstOrNull()?.also {
                                                userIdToSnapchatter[userId] = it
                                            }
                                        }
                                    }
                                }

                                var bitmojiBitmap by remember(userId, friendInfo?.bitmojiAvatarId) {
                                    mutableStateOf(friendInfo?.bitmojiAvatarId?.let { bitmojiCache[it] })
                                }

                                LaunchedEffect(userId, friendInfo?.bitmojiAvatarId, friendInfo?.bitmojiSelfieId) {
                                    val info = friendInfo ?: return@LaunchedEffect
                                    val avatarId = info.bitmojiAvatarId ?: return@LaunchedEffect
                                    val selfieId = info.bitmojiSelfieId ?: return@LaunchedEffect
                                    if (bitmojiBitmap != null) return@LaunchedEffect
                                    BitmojiSelfie.getBitmojiSelfie(selfieId, avatarId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)?.let { url ->
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                RemoteMediaResolver.downloadMedia(url) { inputStream, _ ->
                                                    bitmojiCache[avatarId] = BitmapFactory.decodeStream(inputStream).also { bitmojiBitmap = it }
                                                }
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(skin.textPrimary.copy(alpha = 0.05f))
                                        .border(1.dp, skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Image(
                                        bitmap = remember(bitmojiBitmap) { bitmojiBitmap?.asImageBitmap() ?: noBitmojiBitmap },
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                    )

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = friendSnapchatter?.let { snapchatter ->
                                                snapchatter.displayName?.let { "$it (${snapchatter.username})" }
                                                    ?: snapchatter.username
                                                    ?: context.translation["common.unknown"]
                                            } ?: context.translation["common.unknown"],
                                            color = skin.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            friendLinkType?.let { type ->
                                                StatusPill(
                                                    text = type.name.lowercase().replaceFirstChar { it.uppercase() },
                                                    color = if (type == FriendLinkType.MUTUAL) skin.glowSecondary else skin.textSecondary,
                                                    skin = skin
                                                )
                                            }
                                            friendSnapchatter?.let {
                                                val isFollowing = friendLinkType == FriendLinkType.FOLLOWING
                                                val isPending = pendingFriendRequests[userId]?.isActive == true
                                                val canAdd = !isActuallyAdded && !isPending && !isFollowing

                                                PrimaryButton(
                                                    text = when {
                                                        isFollowing -> "Following"
                                                        isActuallyAdded -> context.translation["common.added"]
                                                        isPending -> translation.get("adding")
                                                        else -> translation.get("add")
                                                    },
                                                    modifier = Modifier.height(28.dp).widthIn(min = 80.dp),
                                                    enabled = canAdd,
                                                    skin = skin
                                                ) {
                                                    if (!canAdd) return@PrimaryButton
                                                    val prevLinkType = friendLinkType
                                                    addFriend(userId)
                                                    pendingFriendRequests[userId] = coroutineScope.launch(Dispatchers.IO) {
                                                        runCatching {
                                                            withTimeout(10000) {
                                                                while (true) {
                                                                    context.database.getFriendInfo(userId)?.let { updated ->
                                                                        val newType = FriendLinkType.fromValue(updated.friendLinkType)
                                                                        if (newType != prevLinkType) {
                                                                            friendInfo = updated
                                                                            friendLinkType = newType
                                                                            return@withTimeout
                                                                        }
                                                                    }
                                                                    delay(500)
                                                                }
                                                            }
                                                        }
                                                    }.apply { invokeOnCompletion { pendingFriendRequests.remove(userId) } }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .alpha(if (enabled) 1f else 0.6f)
                .background(Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(text = text, style = TextStyle(color = skin.primaryButtonText, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }

    @Composable
    private fun SecondaryButton(text: String, modifier: Modifier = Modifier, skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .background(skin.textPrimary.copy(alpha = 0.05f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(text = text, style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        }
    }

    @Composable
    private fun StatusPill(text: String, color: Color, skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                me.eternal.purrfect.core.ui.PurrfectOverlayTheme(null) {
                    val skin = LocalPurrfectSkin.current
                    val isAether = skin.id == "AETHER"
                    val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(26.dp) else RoundedCornerShape(26.dp)

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                                .clip(shape)
                                .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), shape),
                            shape = shape,
                            color = skin.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp
                        ) {
                            ManagerDialog()
                        }
                    }
                }
            }.apply {
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }
}

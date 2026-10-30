package me.eternal.purrfect.ui.manager.pages.location

import android.os.Parcel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.ui.util.DialogProperties
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.bridge.location.FriendLocation
import me.eternal.purrfect.bridge.location.LocationCoordinates
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.common.ui.rememberAsyncUpdateDispatcher
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.storage.addOrUpdateLocationCoordinate
import me.eternal.purrfect.storage.getLocationCoordinates
import me.eternal.purrfect.storage.removeLocationCoordinate
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.util.AlertDialogs
import me.eternal.purrfect.ui.util.purrfectSwitchColors
import me.eternal.purrfect.ui.util.coil.BitmojiImage
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

import me.eternal.purrfect.common.ui.theme.PurrfectPalette

internal object LocationSkinPalette {
    @Composable
    internal fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else PurrfectPalette.glowSecondary
    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else PurrfectPalette.backgroundGradient
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else PurrfectPalette.cardOverlay
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else PurrfectPalette.textPrimary
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else PurrfectPalette.textSecondary
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else PurrfectPalette.cardOverlayColor
}

class BetterLocationRoot : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.better_location") }
    private val alertDialogs by lazy { AlertDialogs(translation) }

    @Composable
    private fun GlassPanel(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val shape = RoundedCornerShape(22.dp)
        Surface(
            modifier = modifier,
            shape = shape,
            color = LocationSkinPalette.cardOverlayColor,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, (if (LocationSkinPalette.isAphelion() && !androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else LocationSkinPalette.textPrimary).copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
    }

    @Composable
    private fun FriendLocationItem(
        friendLocation: FriendLocation,
        dismiss: () -> Unit
    ) {
        GlassPanel(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .fillMaxWidth()
                .clickable {
                    context.config.root.global.betterLocation.coordinates.setAny(friendLocation.latitude to friendLocation.longitude)
                    dismiss()
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BitmojiImage(
                    context = context,
                    url = BitmojiSelfie.getBitmojiSelfie(
                        friendLocation.bitmojiSelfieId,
                        friendLocation.bitmojiId,
                        BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D
                    ),
                    size = 50,
                    modifier = Modifier.padding(2.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        friendLocation.displayName?.let { "$it (${friendLocation.username})" }
                            ?: friendLocation.username,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = LocationSkinPalette.textPrimary
                    )
                    Text(
                        text = friendLocation.localityPieces.joinToString(", "),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocationSkinPalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = translation.format(
                            "spoofed_coordinates_title",
                            "latitude" to friendLocation.latitude.toFloat().toString(),
                            "longitude" to friendLocation.longitude.toFloat().toString()
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        color = LocationSkinPalette.textPrimary.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    tint = LocationSkinPalette.glowSecondary
                )
            }
        }
    }

    @Composable
    private fun FriendLocationsDialogs(
        friendsLocation: List<FriendLocation>,
        dismiss: () -> Unit
    ) {
        var search by remember { mutableStateOf("") }
        val filteredFriendsLocation = rememberAsyncMutableStateList(defaultValue = friendsLocation, keys = arrayOf(search)) {
            search.takeIf { it.isNotBlank() }?.let {
                friendsLocation.filter {
                    it.displayName?.contains(search, ignoreCase = true) == true || it.username.contains(search, ignoreCase = true)
                }
            }  ?: friendsLocation
        }

        GlassPanel(
            modifier = Modifier
                .padding(vertical = 24.dp, horizontal = 10.dp)
                .fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    translation["teleport_to_friend_title"],
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = LocationSkinPalette.textPrimary
                )
                val glowSecondary = LocationSkinPalette.glowSecondary
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(translation["search_bar"]) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.08f),
                        unfocusedContainerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.05f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = glowSecondary
                    ),
                    singleLine = true
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        if (friendsLocation.isEmpty()) {
                            Text(
                                translation["no_friends_map"],
                                fontSize = 16.sp,
                                modifier = Modifier.padding(16.dp),
                                fontWeight = FontWeight.Light,
                                color = LocationSkinPalette.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        } else if (filteredFriendsLocation.isEmpty()) {
                            Text(
                                translation["no_friends_found"],
                                fontSize = 16.sp,
                                modifier = Modifier.padding(16.dp),
                                fontWeight = FontWeight.Light,
                                color = LocationSkinPalette.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    items(filteredFriendsLocation) { friendLocation ->
                        FriendLocationItem(friendLocation, dismiss)
                    }
                }
            }
        }
    }

    @Composable
    private fun CoordinateActionButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        description: String,
        accent: Color,
        onClick: () -> Unit
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accent.copy(alpha = 0.22f),
                contentColor = LocationSkinPalette.textPrimary
            )
        ) {
            Icon(icon, contentDescription = description)
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val coordinatesProperty = remember {
            context.config.root.global.betterLocation.getPropertyPair("coordinates")
        }
        val providerProperty = remember { context.config.root.global.betterLocation.getPropertyPair("location_search_provider") }
        val apiKeyProperty = remember { context.config.root.global.betterLocation.getPropertyPair("google_maps_api_key") }

        val updateDispatcher = rememberAsyncUpdateDispatcher()
        val savedCoordinates = rememberAsyncMutableStateList(
            defaultValue = listOf(),
            updateDispatcher = updateDispatcher
        ) {
            context.database.getLocationCoordinates()
        }
        var showMap by remember { mutableStateOf(false) }
        var addSavedCoordinateDialog by remember { mutableStateOf(false) }
        var showTeleportDialog by remember { mutableStateOf(false) }
        var showProviderDialog by remember { mutableStateOf(false) }
        var showApiKeyDialog by remember { mutableStateOf(false) }

        val marker = remember { mutableStateOf<Marker?>(null) }
        val mapView = remember { mutableStateOf<MapView?>(null) }
        var spoofedCoordinates by remember(showTeleportDialog, showMap) {
            mutableStateOf(
                (coordinatesProperty.value.getNullable() as? Pair<*, *>)
                    ?: (0.0 to 0.0)
            )
        }

        fun addSavedCoordinate(id: Int?, locationCoordinates: LocationCoordinates, onSuccess: suspend (id: Int) -> Unit = {}) {
            context.coroutineScope.launch {
                onSuccess(context.database.addOrUpdateLocationCoordinate(id, locationCoordinates))
            }
        }

        if (showTeleportDialog) {
            me.eternal.purrfect.ui.util.Dialog(
                properties = DialogProperties(usePlatformDefaultWidth = false),
                onDismissRequest = { showTeleportDialog = false },
                content = {
                    FriendLocationsDialogs(remember { context.locationManager.friendsLocation }) {
                        showTeleportDialog = false
                        context.coroutineScope.launch {
                            context.config.writeConfig()
                        }
                    }
                }
            )
        }

         var currentProvider by remember {
             mutableStateOf(context.config.root.global.betterLocation.locationSearchProvider.getNullable() ?: "osm")
         }
         var currentApiKey by remember {
             mutableStateOf(context.config.root.global.betterLocation.googleMapsApiKey.getNullable() ?: "")
         }

         if (showProviderDialog) {
             me.eternal.purrfect.ui.util.Dialog(onDismissRequest = {
                 showProviderDialog = false
                 context.config.writeConfig()
                 currentProvider = context.config.root.global.betterLocation.locationSearchProvider.getNullable() ?: "osm"
             }) {
                 alertDialogs.UniqueSelectionDialog(providerProperty)
             }
         }
         if (showApiKeyDialog) {
             me.eternal.purrfect.ui.util.Dialog(onDismissRequest = {
                 showApiKeyDialog = false
                 context.config.writeConfig()
                 currentApiKey = context.config.root.global.betterLocation.googleMapsApiKey.getNullable() ?: ""
             }) {
                  alertDialogs.KeyboardInputDialog(apiKeyProperty) {
                      showApiKeyDialog = false
                      context.config.writeConfig()
                      currentApiKey = context.config.root.global.betterLocation.googleMapsApiKey.getNullable() ?: ""
                  }
             }
         }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocationSkinPalette.backgroundGradient)
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    translation.format(
                        "spoofed_coordinates_title",
                        "latitude" to ((spoofedCoordinates.first as? Double)?.toFloat() ?: "0.0").toString(),
                        "longitude" to ((spoofedCoordinates.second as? Double)?.toFloat() ?: "0.0").toString()
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    color = LocationSkinPalette.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (addSavedCoordinateDialog) {
                me.eternal.purrfect.ui.util.Dialog(
                    onDismissRequest = { addSavedCoordinateDialog = false },
                    content = {
                        AddCoordinatesDialog(
                            alertDialogs,
                            translation,
                            LocationCoordinates().apply {
                                this.latitude = marker.value?.position?.latitude ?: 0.0
                                this.longitude = marker.value?.position?.longitude ?: 0.0
                            },
                        ) { coordinates ->
                            addSavedCoordinateDialog = false
                            addSavedCoordinate(null, coordinates) {
                                withContext(Dispatchers.Main) {
                                    savedCoordinates.add(0, coordinates.apply { id = it })
                                }
                            }
                        }
                    }
                )
            }

            if (showMap) {
                me.eternal.purrfect.ui.util.Dialog(
                    onDismissRequest = { showMap = false },
                    content = {
                        val shape = RoundedCornerShape(24.dp)
                        val glowPrimary = LocationSkinPalette.glowPrimary
                        val glowSecondary = LocationSkinPalette.glowSecondary
                        val borderBrush = remember(glowPrimary, glowSecondary) {
                            Brush.linearGradient(
                                listOf(
                                    glowPrimary.copy(alpha = 0.45f),
                                    glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        }
                        Surface(
                            shape = shape,
                            color = LocationSkinPalette.textPrimary.copy(alpha = 0.06f),
                            tonalElevation = 0.dp,
                            shadowElevation = 16.dp,
                            border = BorderStroke(1.dp, borderBrush)
                        ) {
                            Box(modifier = Modifier.background(LocationSkinPalette.cardOverlay, shape)) {
                                alertDialogs.ChooseLocationDialog(
                                    property = coordinatesProperty,
                                    marker = marker,
                                    mapView = mapView,
                                    locationSearchProvider = context.config.root.global.betterLocation.locationSearchProvider.getNullable() ?: "osm",
                                    googleMapsApiKey = context.config.root.global.betterLocation.googleMapsApiKey.getNullable() ?: "",
                                    saveCoordinates = {
                                        addSavedCoordinateDialog = true
                                    }
                                ) {
                                    showMap = false
                                    context.config.writeConfig()
                                }
                            }
                        }
                        DisposableEffect(Unit) {
                            onDispose {
                                marker.value = null
                            }
                        }
                    }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentPadding = PaddingValues(bottom = routes.bottomPadding)
            ) {

                item {
                    @Composable
                    fun ConfigToggle(
                        text: String,
                        state: MutableState<Boolean>,
                        onCheckedChange: (Boolean) -> Unit
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = text, color = LocationSkinPalette.textPrimary)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.value,
                                onCheckedChange = {
                                    state.value = it
                                    onCheckedChange(it)
                                },
                                colors = purrfectSwitchColors()
                            )
                        }
                    }
                    ConfigToggle(
                        translation["spoof_location_toggle"],
                        remember { mutableStateOf(context.config.root.global.betterLocation.spoofLocation.get()) }
                    ) {
                        context.config.root.global.betterLocation.spoofLocation.set(it)
                    }
                    ConfigToggle(
                        translation["suspend_location_updates"],
                        remember { mutableStateOf(context.config.root.global.betterLocation.suspendLocationUpdates.get()) }
                    ) {
                        context.config.root.global.betterLocation.suspendLocationUpdates.set(it)
                    }

                    @Composable
                    fun ConfigSelector(text: String, value: String, onClick: () -> Unit) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onClick)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = text, modifier = Modifier.weight(1f), color = LocationSkinPalette.textPrimary)
                            Text(
                                text = value,
                                color = LocationSkinPalette.textSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    @Composable
                    fun ConfigInput(text: String, value: String, onClick: () -> Unit) {
                        ConfigSelector(text, if (value.isNotEmpty()) "********" else translation["options.empty"], onClick)
                    }

                    ConfigSelector(
                        text = translation["location_search_provider_title"],
                        value = translation["option_$currentProvider"]
                    ) { showProviderDialog = true }

                    if (currentProvider == "google_maps") {
                        ConfigInput(
                            text = translation["google_maps_api_key_title"],
                            value = currentApiKey
                        ) { showApiKeyDialog = true }
                    }
                }
                item {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showMap = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LocationSkinPalette.glowPrimary.copy(alpha = 0.28f),
                                    contentColor = LocationSkinPalette.textPrimary
                                )
                            ) {
                                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(translation["choose_location_button"])
                            }
                            Button(
                                onClick = { showTeleportDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LocationSkinPalette.glowSecondary.copy(alpha = 0.28f),
                                    contentColor = LocationSkinPalette.textPrimary
                                )
                            ) {
                                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(translation["teleport_to_friend_button"])
                            }
                        }
                    }
                }
                item {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    translation["saved_coordinates_title"],
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 22.sp,
                                    color = LocationSkinPalette.textPrimary
                                )
                                Text(
                                    translation["saved_coordinates_subtitle"],
                                    fontSize = 12.sp,
                                    color = LocationSkinPalette.textSecondary
                                )
                            }
                            FilledIconButton(
                                onClick = { addSavedCoordinateDialog = true },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.12f),
                                    contentColor = LocationSkinPalette.textPrimary
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = translation["add_icon_description"])
                            }
                        }
                    }
                }
                item {
                    if (savedCoordinates.isEmpty()) {
                        Text(
                            translation["no_saved_coordinates_hint"],
                            fontSize = 16.sp,
                            modifier = Modifier
                                .padding(start = 20.dp, top = 8.dp),
                            fontWeight = FontWeight.Light,
                            color = LocationSkinPalette.textSecondary
                        )
                    }
                }
                items(savedCoordinates, key = { it.id }) { coordinates ->
                    var mutableCoordinates by remember { mutableStateOf(coordinates) }
                    val isSelected = spoofedCoordinates == mutableCoordinates.latitude to mutableCoordinates.longitude
                    var showDeleteDialog by remember { mutableStateOf(false) }
                    var showEditDialog by remember { mutableStateOf(false) }

                    fun setSpoofedCoordinates() {
                        spoofedCoordinates = mutableCoordinates.latitude to mutableCoordinates.longitude
                        coordinatesProperty.value.setAny(spoofedCoordinates)
                        context.coroutineScope.launch {
                            context.config.writeConfig()
                        }
                    }

                    if (showDeleteDialog) {
                        me.eternal.purrfect.ui.util.Dialog(
                            onDismissRequest = { showDeleteDialog = false },
                            content = {
                                alertDialogs.ConfirmDialog(
                                    title = translation["delete_dialog_title"],
                                    message = translation["delete_dialog_message"],
                                    onConfirm = {
                                        showDeleteDialog = false
                                        context.coroutineScope.launch {
                                            context.database.removeLocationCoordinate(coordinates.id)
                                            savedCoordinates.remove(coordinates)
                                        }
                                    },
                                    onDismiss = { showDeleteDialog = false }
                                )
                            }
                        )
                    }

                    if (showEditDialog) {
                        me.eternal.purrfect.ui.util.Dialog(
                            onDismissRequest = { showEditDialog = false },
                            content = {
                                AddCoordinatesDialog(
                                    alertDialogs,
                                    translation,
                                    mutableCoordinates
                                ) {
                                    val itemId = coordinates.id
                                    context.coroutineScope.launch {
                                        addSavedCoordinate(itemId, it)
                                    }
                                    Parcel.obtain().apply {
                                        it.writeToParcel(this, 0)
                                        setDataPosition(0)
                                        coordinates.readFromParcel(this)
                                        coordinates.id = itemId
                                        recycle()
                                    }
                                    mutableCoordinates = it
                                    if (isSelected) setSpoofedCoordinates()
                                    showEditDialog = false
                                }
                            }
                        )
                    }

                    val glowPrimary = LocationSkinPalette.glowPrimary
                    val glowSecondary = LocationSkinPalette.glowSecondary

                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .clickable {
                                mutableCoordinates = coordinates
                                setSpoofedCoordinates()
                                GeoPoint(coordinates.latitude, coordinates.longitude).also {
                                    marker.value?.position = it
                                    mapView.value?.controller?.apply {
                                        animateTo(it)
                                        setZoom(16.0)
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = remember(mutableCoordinates) { mutableCoordinates.name },
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                    overflow = TextOverflow.Ellipsis,
                                    color = LocationSkinPalette.textPrimary
                                )
                                Text(
                                    text = remember(mutableCoordinates) { "(${mutableCoordinates.latitude.toFloat()}, ${mutableCoordinates.longitude.toFloat()})" },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    overflow = TextOverflow.Ellipsis,
                                    color = LocationSkinPalette.textSecondary
                                )
                            }
                            CoordinateActionButton(
                                icon = Icons.Default.Edit,
                                description = translation["edit_icon_description"],
                                accent = glowPrimary
                            ) {
                                showEditDialog = true
                            }
                            CoordinateActionButton(
                                icon = Icons.Default.DeleteOutline,
                                description = translation["delete_icon_description"],
                                accent = glowSecondary
                            ) {
                                showDeleteDialog = true
                            }
                        }
                    }
                }
            }
        }
    }
}

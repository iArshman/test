package me.eternal.purrfect.ui.manager.pages.location

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.eternal.purrfect.bridge.location.LocationCoordinates
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.ui.util.AlertDialogs

@Composable
fun AddCoordinatesDialog(
    alertDialogs: AlertDialogs,
    translation: LocaleWrapper,
    locationCoordinates: LocationCoordinates,
    confirm: (locationCoordinates: LocationCoordinates) -> Unit
) {
    var savedName by remember {
        mutableStateOf(
            (locationCoordinates.name ?: "").let {
                TextFieldValue(it, selection = TextRange(it.length))
            }
        )
    }
    var savedLatitude by remember { mutableStateOf(locationCoordinates.latitude.toString()) }
    var savedLongitude by remember { mutableStateOf(locationCoordinates.longitude.toString()) }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.08f),
        unfocusedContainerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.05f),
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedLabelColor = LocationSkinPalette.textSecondary,
        unfocusedLabelColor = LocationSkinPalette.textSecondary,
        cursorColor = LocationSkinPalette.glowSecondary,
        focusedTextColor = LocationSkinPalette.textPrimary,
        unfocusedTextColor = LocationSkinPalette.textPrimary
    )

    alertDialogs.DefaultDialogCard {
        val focusRequester = remember { FocusRequester() }
        
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(
                                LocationSkinPalette.cardOverlayColor.copy(alpha = 0.98f),
                                Color(0xFF1A143A).copy(alpha = 0.94f)
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = translation["save_coordinates_dialog_title"],
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = LocationSkinPalette.textPrimary
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = savedName,
                    onValueChange = { savedName = it },
                    label = { Text(translation["saved_name_dialog_hint"]) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )

                LaunchedEffect(Unit) {
                    delay(200)
                    focusRequester.requestFocus()
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = savedLatitude,
                    onValueChange = { savedLatitude = it },
                    label = { Text(translation["latitude_dialog_hint"]) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = savedLongitude,
                    onValueChange = { savedLongitude = it },
                    label = { Text(translation["longitude_dialog_hint"]) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            confirm(LocationCoordinates().apply {
                                this.name = savedName.text
                                this.latitude = savedLatitude.toDoubleOrNull() ?: 0.0
                                this.longitude = savedLongitude.toDoubleOrNull() ?: 0.0
                            })
                        },
                        enabled = savedName.text.isNotBlank() && savedLatitude.isNotBlank() && savedLongitude.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocationSkinPalette.glowPrimary.copy(alpha = 0.3f),
                            contentColor = LocationSkinPalette.textPrimary,
                            disabledContainerColor = LocationSkinPalette.textPrimary.copy(alpha = 0.12f),
                            disabledContentColor = LocationSkinPalette.textPrimary.copy(alpha = 0.6f)
                        )
                    ) {
                        Text(translation["save_dialog_button"])
                    }
                }
            }
        }
    }
}

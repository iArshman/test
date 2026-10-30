package me.eternal.purrfect.ui.manager

import me.eternal.purrfect.ui.manager.pages.themes.aphelion.AphelionTheme

/**
 * ManagerTheme is the registry of all available themes.
 *
 * Adding a new theme requires changes only in this file:
 * - Add a new object entry to the sealed class
 * - Add its ID string to fromId()
 *
 * No other file needs to change when a new theme is added.
 */
sealed class ManagerTheme(val theme: ThemeContract) {
    object Aphelion : ManagerTheme(AphelionTheme)

    companion object {
        fun fromId(id: String): ManagerTheme = Aphelion
    }
}

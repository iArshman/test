package me.eternal.purrfect.common.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Type-safe implementation of the Catppuccin color system.
 * Based on the official Catppuccin Palette (v1.8.0).
 */
object Catppuccin {

    data class Flavor(
        val base: Color,
        val mantle: Color,
        val crust: Color,
        val text: Color,
        val subtext1: Color,
        val subtext0: Color,
        val overlay2: Color,
        val overlay1: Color,
        val overlay0: Color,
        val surface2: Color,
        val surface1: Color,
        val surface0: Color,

        // Accents
        val white: Color,
        val black: Color,
        val gold: Color,
        val neon: Color,
        val rosewater: Color,        val flamingo: Color,
        val pink: Color,
        val mauve: Color,
        val red: Color,
        val maroon: Color,
        val peach: Color,
        val yellow: Color,
        val green: Color,
        val teal: Color,
        val sky: Color,
        val sapphire: Color,
        val blue: Color,
        val lavender: Color,
        val espresso: Color,
        val forest: Color
    ) {
        fun getAccent(name: String): Color = when (name.uppercase()) {
            "ROSEWATER" -> rosewater
            "FLAMINGO"  -> flamingo
            "PINK"      -> pink
            "MAUVE"     -> mauve
            "RED"       -> red
            "MAROON"    -> maroon
            "PEACH"     -> peach
            "YELLOW"    -> yellow
            "GREEN"     -> green
            "TEAL"      -> teal
            "SKY"       -> sky
            "SAPPHIRE"  -> sapphire
            "BLUE"      -> blue
            "LAVENDER"  -> lavender
            "ESPRESSO"  -> espresso
            "FOREST"    -> forest
            "WHITE"     -> white
            "BLACK"     -> black
            "GOLD"      -> gold
            "NEON"      -> neon
            else        -> mauve
        }

        val accents = listOf(
            "Rosewater" to rosewater, "Flamingo" to flamingo, "Pink" to pink, "Mauve" to mauve,
            "Red" to red, "Maroon" to maroon, "Peach" to peach, "Yellow" to yellow,
            "Green" to green, "Teal" to teal, "Sky" to sky, "Sapphire" to sapphire,
            "Blue" to blue, "Lavender" to lavender, "Espresso" to espresso, "Forest" to forest,
            "White" to white, "Black" to black, "Gold" to gold, "Neon" to neon
        )
    }

    val mocha = Flavor(
        base = Color(0xFF1E1E2E), mantle = Color(0xFF181825), crust = Color(0xFF11111B),
        text = Color(0xFFCDD6F4), subtext1 = Color(0xFFBAC2DE), subtext0 = Color(0xFFA6ADC8),
        overlay2 = Color(0xFF9399B2), overlay1 = Color(0xFF7F849C), overlay0 = Color(0xFF6C7086),
        surface2 = Color(0xFF585B70), surface1 = Color(0xFF45475A), surface0 = Color(0xFF313244), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF5E0DC), flamingo = Color(0xFFF2CDCD), pink = Color(0xFFF5C2E7),
        mauve = Color(0xFFCBA6F7), red = Color(0xFFF38BA8), maroon = Color(0xFFEBA0AC),
        peach = Color(0xFFFAB387), yellow = Color(0xFFF9E2AF), green = Color(0xFFA6E3A1),
        teal = Color(0xFF94E2D5), sky = Color(0xFF89DCEB), sapphire = Color(0xFF74C7EC),
        blue = Color(0xFF89B4FA), lavender = Color(0xFFB4BEFE),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    val macchiato = Flavor(
        base = Color(0xFF24273A), mantle = Color(0xFF1E2030), crust = Color(0xFF181926),
        text = Color(0xFFCAD3F5), subtext1 = Color(0xFFB8C0E0), subtext0 = Color(0xFFA5ADCB),
        overlay2 = Color(0xFF939AB7), overlay1 = Color(0xFF8087A2), overlay0 = Color(0xFF6E738D),
        surface2 = Color(0xFF5B6078), surface1 = Color(0xFF494D64), surface0 = Color(0xFF363A4F), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF4DBD6), flamingo = Color(0xFFF0C6C6), pink = Color(0xFFF5BDE6),
        mauve = Color(0xFFC6A0F6), red = Color(0xFFED8796), maroon = Color(0xFFEE99A0),
        peach = Color(0xFFF5A97F), yellow = Color(0xFFEED49F), green = Color(0xFFA6DA95),
        teal = Color(0xFF8BD5CA), sky = Color(0xFF91D7E3), sapphire = Color(0xFF7DC4E4),
        blue = Color(0xFF8AADF4), lavender = Color(0xFFB7BDF8),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    val frappe = Flavor(
        base = Color(0xFF303446), mantle = Color(0xFF292C3C), crust = Color(0xFF232634),
        text = Color(0xFFC6D0F5), subtext1 = Color(0xFFB5BFE1), subtext0 = Color(0xFFA5ADCE),
        overlay2 = Color(0xFF949CBB), overlay1 = Color(0xFF838BA7), overlay0 = Color(0xFF737994),
        surface2 = Color(0xFF626880), surface1 = Color(0xFF51576D), surface0 = Color(0xFF414559), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF2D5CF), flamingo = Color(0xFFEEBEBE), pink = Color(0xFFF4B8E4),
        mauve = Color(0xFFCA9EE6), red = Color(0xFFE78284), maroon = Color(0xFFEA999C),
        peach = Color(0xFFEF9F76), yellow = Color(0xFFE5C890), green = Color(0xFFA6D189),
        teal = Color(0xFF81C8BE), sky = Color(0xFF99D1DB), sapphire = Color(0xFF85C1DC),
        blue = Color(0xFF8CAAEE), lavender = Color(0xFFBABBF1),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    val latte = Flavor(
        base = Color(0xFFEFF1F5), mantle = Color(0xFFE6E9EF), crust = Color(0xFFDCE0E8),
        text = Color(0xFF4C4F69), subtext1 = Color(0xFF5C5F77), subtext0 = Color(0xFF6C6F85),
        overlay2 = Color(0xFF7C7F93), overlay1 = Color(0xFF8C8FA1), overlay0 = Color(0xFF9CA0B0),
        surface2 = Color(0xFFACB0BE), surface1 = Color(0xFFBCC0CC), surface0 = Color(0xFFCCD0DA), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF0083B0),
        rosewater = Color(0xFFDC8A78), flamingo = Color(0xFFDD7878), pink = Color(0xFFEA76CB),
        mauve = Color(0xFF8839EF), red = Color(0xFFD20F39), maroon = Color(0xFFE64553),
        peach = Color(0xFFFE640B), yellow = Color(0xFFDF8E1D), green = Color(0xFF40A02B),
        teal = Color(0xFF179299), sky = Color(0xFF04A5E5), sapphire = Color(0xFF209FB5),
        blue = Color(0xFF1E66F5), lavender = Color(0xFF7287FD),
        espresso = Color(0xFFAC765D), forest = Color(0xFF5D824E)
    )

    /**
     * Custom Warm Ivory flavor (Light 1) for Premium Lux and Lumina Light skins.
     */
    val warmLatte = Flavor(
        base = Color(0xFFF8F3EC), mantle = Color(0xFFF3EEE7), crust = Color(0xFFEEE7DF),
        text = Color(0xFF232225), subtext1 = Color(0xFF6D6870), subtext0 = Color(0xFF8B858F),
        overlay2 = Color(0xFFAAA5AE), overlay1 = Color(0xFFCAC4CD), overlay0 = Color(0xFFD6CBD2),
        surface2 = Color(0xFFDCD2DA), surface1 = Color(0xFFE2D9E2), surface0 = Color(0xFFD6CBD2), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF0083B0),
        rosewater = Color(0xFFEFD3DC), flamingo = Color(0xFFEEBEBE), pink = Color(0xFFF4B8E4),
        mauve = Color(0xFF8F63D8), red = Color(0xFFE78284), maroon = Color(0xFFEA999C),
        peach = Color(0xFFEF9F76), yellow = Color(0xFFE5C890), green = Color(0xFFA6D189),
        teal = Color(0xFFA9CFC8), sky = Color(0xFFD3E7ED), sapphire = Color(0xFF85C1DC),
        blue = Color(0xFF8CAAEE), lavender = Color(0xFFDCD0EA),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    /**
     * Custom Soft Dark flavor (Light 2) for NOX.
     */
    val softAmoled = Flavor(
        base = Color(0xFF08080A), mantle = Color(0xFF101014), crust = Color(0xFF18171D),
        text = Color(0xFFF2EEF3), subtext1 = Color(0xFFB8B1BD), subtext0 = Color(0xFF746E7A),
        overlay2 = Color(0xFF5E5963), overlay1 = Color(0xFF48444C), overlay0 = Color(0xFF34303A),
        surface2 = Color(0xFF2C2932), surface1 = Color(0xFF28252E), surface0 = Color(0xFF242229), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF1D7D2), flamingo = Color(0xFFF0C6C6), pink = Color(0xFFF5BDE6),
        mauve = Color(0xFFA78BFA), red = Color(0xFFED8796), maroon = Color(0xFFEE99A0),
        peach = Color(0xFFF5A97F), yellow = Color(0xFFEED49F), green = Color(0xFFA6DA95),
        teal = Color(0xFF9FCBC3), sky = Color(0xFFAFCFDB), sapphire = Color(0xFF7DC4E4),
        blue = Color(0xFF8AADF4), lavender = Color(0xFFC9B8F2),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    /**
     * Custom Nordic Deep flavor for Lumina Dark skin.
     */
    val nordicDeep = Flavor(
        base = Color(0xFF1A1B26), mantle = Color(0xFF16161E), crust = Color(0xFF13131A),
        text = Color(0xFFC0CAF5), subtext1 = Color(0xFFA9B1D6), subtext0 = Color(0xFF9AA5CE),
        overlay2 = Color(0xFF787C99), overlay1 = Color(0xFF565F89), overlay0 = Color(0xFF444B6A),
        surface2 = Color(0xFF3B4261), surface1 = Color(0xFF2F3549), surface0 = Color(0xFF24283B), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF1D7D2), flamingo = Color(0xFFF0C6C6), pink = Color(0xFFF5BDE6),
        mauve = Color(0xFFA78BFA), red = Color(0xFFF7768E), maroon = Color(0xFFFF9E64),
        peach = Color(0xFFFF9E64), yellow = Color(0xFFE0AF68), green = Color(0xFF9ECE6A),
        teal = Color(0xFF9FCBC3), sky = Color(0xFFAFCFDB), sapphire = Color(0xFF7DC4E4),
        blue = Color(0xFF7AA2F7), lavender = Color(0xFFC9B8F2),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )

    /**
     * Custom Royal Velvet flavor (Suggestion 1) for UMBRA.
     */
    val royalVelvet = Flavor(
        base = Color(0xFF14142B), mantle = Color(0xFF1F1F41), crust = Color(0xFF2B2B5E),
        text = Color(0xFFEAE3F2), subtext1 = Color(0xFFA199AB), subtext0 = Color(0xFF7D7787),
        overlay2 = Color(0xFF635E6D), overlay1 = Color(0xFF4D4956), overlay0 = Color(0xFF383540),
        surface2 = Color(0xFF2D2936), surface1 = Color(0xFF26222E), surface0 = Color(0xFF1D1726), white = Color(0xFFFFFFFF), black = Color(0xFF000000), gold = Color(0xFFD4AF37), neon = Color(0xFF00FFD1),
        rosewater = Color(0xFFF1D7D2), flamingo = Color(0xFFF0C6C6), pink = Color(0xFFF5BDE6),
        mauve = Color(0xFF7B61FF), red = Color(0xFFF7768E), maroon = Color(0xFFEE99A0),
        peach = Color(0xFFF5A97F), yellow = Color(0xFFEED49F), green = Color(0xFFA6DA95),
        teal = Color(0xFF9FCBC3), sky = Color(0xFFAFCFDB), sapphire = Color(0xFF7DC4E4),
        blue = Color(0xFF8AADF4), lavender = Color(0xFFD3C3FF),
        espresso = Color(0xFFDDB69E), forest = Color(0xFF719686)
    )
}

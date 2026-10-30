package me.eternal.purrfect.core.features.impl.ui

import android.widget.TextView
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

class FakeFollowersCount : Feature("Fake Followers Count") {

    override fun init() {
        if (context.config.userInterface.spoofFollowersCount.globalState != true) return
        val raw = context.config.userInterface.spoofFollowersCount.customFollowersCount.getNullable()?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        val digits = raw.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return
        val followerValue = try {
            BigInteger(digits)
        } catch (_: NumberFormatException) {
            return
        }

        onNextActivityCreate {
            TextView::class.java.hook("setText", HookStage.BEFORE) { param ->
                val textView = param.thisObject() as? TextView ?: return@hook
                if (textView.javaClass.name !in COMPOSER_SNAP_TEXT_VIEW) return@hook
                val arg0 = param.argNullable<Any>(0) ?: return@hook
                if (arg0 !is CharSequence) return@hook
                val text = arg0.toString()
                if (!FOLLOWERS_LINE.containsMatchIn(text)) return@hook
                val display = formatFollowersDisplay(followerValue)
                val replaced = FOLLOWERS_LINE.replace(text) { mr ->
                    "$display ${mr.groupValues[2]}"
                }
                param.setArg(0, replaced)
            }
        }
    }

    private fun formatFollowersDisplay(n: BigInteger): String {
        val v = n.max(BigInteger.ZERO)
        if (v < TEN_THOUSAND) {
            return NumberFormat.getIntegerInstance(Locale.US).format(v.toLong())
        }
        val (scaled, suffix) = scaleToTier(v)
        return formatMantissaMaxFourDigits(scaled) + suffix
    }

    private fun scaleToTier(v: BigInteger): Pair<BigDecimal, String> {
        var i = SCALE_TIERS.indexOfLast { v >= it.floor }.coerceAtLeast(0)
        var floor = SCALE_TIERS[i].floor
        var suffix = SCALE_TIERS[i].suffix
        var scaled = BigDecimal(v, MATH_CTX).divide(BigDecimal(floor, MATH_CTX), MATH_CTX)
        while (scaled >= THOUSAND_BD && i + 1 < SCALE_TIERS.size) {
            i++
            floor = SCALE_TIERS[i].floor
            suffix = SCALE_TIERS[i].suffix
            scaled = BigDecimal(v, MATH_CTX).divide(BigDecimal(floor, MATH_CTX), MATH_CTX)
        }
        return Pair(scaled, suffix)
    }

    private fun formatMantissaMaxFourDigits(scaled: BigDecimal): String {
        val x = scaled.abs().setScale(12, RoundingMode.HALF_UP)
        if (x.compareTo(BigDecimal.ZERO) == 0) return "0"
        if (x >= HUNDRED) {
            val i = x.setScale(0, RoundingMode.HALF_UP)
            var s = i.toPlainString()
            if (digitCount(s) > 4) {
                s = x.round(MathContext(4, RoundingMode.HALF_UP)).setScale(0, RoundingMode.HALF_UP).toPlainString()
            }
            return s
        }
        if (x >= TEN) {
            var s = stripFrac(x.setScale(1, RoundingMode.HALF_UP))
            if (digitCount(s) > 4) {
                s = x.setScale(0, RoundingMode.HALF_UP).toPlainString()
            }
            return s
        }
        var s = stripFrac(x.setScale(2, RoundingMode.HALF_UP))
        if (digitCount(s) > 4) {
            s = stripFrac(x.setScale(1, RoundingMode.HALF_UP))
        }
        if (digitCount(s) > 4) {
            s = x.setScale(0, RoundingMode.HALF_UP).toPlainString()
        }
        return s
    }

    private fun digitCount(s: String) = s.count { it.isDigit() }

    private fun stripFrac(d: BigDecimal): String {
        var s = d.stripTrailingZeros().toPlainString()
        if ('.' in s) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return s
    }

    private data class ScaleTier(val floor: BigInteger, val suffix: String)

    companion object {
        private val FOLLOWERS_LINE = Regex("^([\\d,]+)\\s+(Followers)\\b", RegexOption.IGNORE_CASE)
        private val COMPOSER_SNAP_TEXT_VIEW = setOf(
            "com.snap.valdi.views.ComposerSnapTextView",
            "com.snap.composer.views.ComposerSnapTextView",
        )
        private val MATH_CTX = MathContext(24, RoundingMode.HALF_UP)
        private val TEN_THOUSAND = BigInteger("10000")
        private val THOUSAND_BD = BigDecimal("1000")
        private val HUNDRED = BigDecimal("100")
        private val TEN = BigDecimal("10")
        private val SCALE_TIERS = listOf(
            ScaleTier(BigInteger("1000"), "K"),
            ScaleTier(BigInteger("1000000"), "M"),
            ScaleTier(BigInteger("1000000000"), "B"),
            ScaleTier(BigInteger("1000000000000"), "T"),
            ScaleTier(BigInteger("1000000000000000"), "Q"),
            ScaleTier(BigInteger("1000000000000000000"), "E"),
            ScaleTier(BigInteger("1000000000000000000000"), "Z"),
            ScaleTier(BigInteger("1000000000000000000000000"), "Y"),
        )
    }
}

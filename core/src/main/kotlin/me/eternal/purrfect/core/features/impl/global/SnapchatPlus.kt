package me.eternal.purrfect.core.features.impl.global

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.ktx.findFieldNamesByType
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.mapper.impl.PlusSubscriptionMapper
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SnapchatPlus: Feature("SnapchatPlus") {
    private val expirationTimeMillis = (System.currentTimeMillis() + 15552000000L)

    override fun init() {
        val snapchatPlusTier = context.config.global.snapchatPlus.getNullable()
        if (snapchatPlusTier == null || snapchatPlusTier == "not_subscribed") return

        // Pre-calculate custom purchase date to eliminate main thread lag
        val customPurchaseDateRaw = context.config.global.snapchatPlusPurchaseDate.get().trim()
        val customPurchaseDateMillis = if (customPurchaseDateRaw.isNotEmpty()) {
            runCatching {
                LocalDate.parse(customPurchaseDateRaw, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        } else (System.currentTimeMillis() - 7776000000L) // 3 months fallback

        context.mappings.useMapper(PlusSubscriptionMapper::class) {
            classReference.get()?.hookConstructor(HookStage.AFTER) { param ->
                param.thisObject<Any>().dataBuilder {
                    //subscription tier
                    if (get<Any>(tierField.getAsString()!!)?.javaClass?.isEnum == true) {
                        set(tierField.getAsString()!!, when (snapchatPlusTier) {
                            "not_subscribed" -> "NO_ACCESS"
                            "basic" -> "SNAPCHAT_PLUS"
                            "ad_free" -> "SNAPCHAT_PLUS_AD_FREE"
                            else -> "SNAPCHAT_PLUS"
                        })
                    } else {
                        set(tierField.getAsString()!!, when (snapchatPlusTier) {
                            "not_subscribed" -> 1
                            "basic" -> 2
                            "ad_free" -> 3
                            else -> 2
                        })
                    }

                    //subscription status
                    set(statusField.getAsString()!!, 2)

                    set(
                        originalSubscriptionTimeMillisField.getAsString()!!,
                        customPurchaseDateMillis
                    )
                    set(expirationTimeMillisField.getAsString()!!, expirationTimeMillis)
                }
            }
        }

        // Force enable all premium features in the catalog
        if (context.config.experimental.hiddenSnapchatPlusFeatures.get()) {
            runCatching {
                val featureCatalogClass = findClass("com.snap.plus.FeatureCatalog")
                featureCatalogClass.hook("isFeatureEnabled", HookStage.BEFORE) { param ->
                    param.setResult(true)
                }
                context.log.verbose("Successfully unlocked premium Snapchat features")
            }
        }
    }
}

package com.vdo.app.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.vdo.app.R
import kotlin.concurrent.thread

/**
 * Advertising, kept deliberately quiet: one small banner, never full-screen, never over
 * the player, and it takes up no space at all until an ad has actually loaded.
 */
object Ads {
    var ready by mutableStateOf(false)
        private set

    /** Lets automated tests run the app without loading ads. */
    @Volatile var disabled = false

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "ads-init") {
            runCatching { MobileAds.initialize(app) { ready = true } }
        }
    }
}

@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    if (!Ads.ready || Ads.disabled) return
    // The ad view is heavy to create, so let the screen settle first; ads never hold up the app.
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); show = true }
    if (!show) return
    var loaded by remember { mutableStateOf(false) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = if (loaded) modifier.fillMaxWidth() else Modifier.height(0.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                adUnitId = ctx.getString(R.string.admob_banner_id)
                adListener = object : AdListener() {
                    override fun onAdLoaded() { loaded = true }
                    override fun onAdFailedToLoad(error: LoadAdError) { loaded = false }
                }
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}

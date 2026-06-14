package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elvishew.xlog.XLog
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean

public class AdMob(private val context: Context) {

    private companion object {
        private const val ADMOB_APP_ID_META_DATA_NAME = "com.google.android.gms.ads.APPLICATION_ID"
        private const val TEST_DEVICE_HASHED_ID = "203640674D72D8AD3E73BDFC4AD236B2"

        private val adUnitIds: List<String> by lazy {
            runCatching {
                val jsonArray = JSONArray(BuildConfig.AD_UNIT_IDS)
                List(jsonArray.length()) { index -> jsonArray.getString(index).trim() }.filter { it.isNotBlank() }
            }.getOrElse { cause ->
                XLog.w(getLog("adUnitIds", "Invalid AD_UNIT_IDS: ${cause.message}"))
                emptyList()
            }
        }
    }

    private data class AdUnitState(val id: String, var inUseCount: Int = 0, var lastUsedAtMillis: Long = 0L)

    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    private val adUnitLock = Any()
    private val adUnitComparator = compareBy<AdUnitState> { it.inUseCount }.thenBy { it.lastUsedAtMillis }
    private val adUnitStates: List<AdUnitState> by lazy { adUnitIds.map(::AdUnitState) }

    public var initialized: Boolean by mutableStateOf(false)
        private set

    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    private val isDebuggable: Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val requestConfiguration: RequestConfiguration? =
        if (isDebuggable) {
            RequestConfiguration.Builder().setTestDeviceIds(listOf(TEST_DEVICE_HASHED_ID)).build()
        } else {
            null
        }

    private val consentRequestParameters = if (isDebuggable) {
        ConsentRequestParameters.Builder()
            .setConsentDebugSettings(
                ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
                    .addTestDeviceHashedId(TEST_DEVICE_HASHED_ID)
                    .build()
            )
            .build()
    } else {
        ConsentRequestParameters.Builder().build()
    }

    public val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    public fun showPrivacyOptionsForm(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                XLog.w(getLog("showPrivacyOptionsForm", "Error: ${formError.errorCode} ${formError.message}"))
            }
        }
    }

    public fun init(activity: Activity) {
        XLog.d(getLog("init", "${activity.hashCode()}"))

        if (initialized) return

        consentInformation.requestConsentInfoUpdate(
            activity,
            consentRequestParameters,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initializeMobileAds(it) } },
            { initializeMobileAds(it) }
        )

        initializeMobileAds()
    }

    private fun initializeMobileAds(error: FormError? = null) {
        if (initialized) {
            XLog.d(getLog("initializeMobileAds", "Already initialized. Ignoring"))
            return
        }

        if (error != null) {
            XLog.w(getLog("initializeMobileAds", "Error: ${error.errorCode} ${error.message}"))
        }

        if (consentInformation.canRequestAds().not()) return

        XLog.d(getLog("initializeMobileAds"))
        if (isMobileAdsInitializeCalled.compareAndSet(false, true).not()) {
            XLog.d(getLog("initializeMobileAds", "Pending initialization. Ignoring"))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val appId = runCatching {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString(ADMOB_APP_ID_META_DATA_NAME)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            }.getOrElse { cause ->
                XLog.w(getLog("initializeMobileAds", "Failed to read AdMob app id: ${cause.message}"))
                null
            } ?: run {
                XLog.w(getLog("initializeMobileAds", "Missing AdMob app id"))
                isMobileAdsInitializeCalled.set(false)
                return@launch
            }

            runCatching {
                val initializationConfig = InitializationConfig.Builder(appId)
                    .apply { requestConfiguration?.let(::setRequestConfiguration) }
                    .build()
                MobileAds.initialize(context, initializationConfig) {
                    CoroutineScope(Dispatchers.Main).launch {
                        XLog.d(this@AdMob.getLog("initializeMobileAds", "Done"))
                        initialized = true
                    }
                }
            }.onFailure { cause ->
                isMobileAdsInitializeCalled.set(false)
                XLog.e(this@AdMob.getLog("initializeMobileAds", "Failed: ${cause.message}"), cause)
            }
        }
    }

    internal fun acquireAdUnitId(): String? = synchronized(adUnitLock) {
        adUnitStates
            .minWithOrNull(adUnitComparator)
            ?.also { state ->
                state.inUseCount += 1
                XLog.d(getLog("acquireAdUnitId", state.toString()))
            }
            ?.id
            ?: run {
                XLog.w(getLog("acquireAdUnitId", "Missing ad unit ids"))
                null
            }
    }

    internal fun recordAdUnitUse(adUnitId: String, event: String) {
        updateAdUnit(adUnitId, "recordAdUnitUse.$event") { lastUsedAtMillis = System.currentTimeMillis() }
    }

    internal fun releaseAdUnit(adUnitId: String) {
        updateAdUnit(adUnitId, "releaseAdUnit") { inUseCount = (inUseCount - 1).coerceAtLeast(0) }
    }

    private fun updateAdUnit(adUnitId: String, operation: String, update: AdUnitState.() -> Unit) {
        synchronized(adUnitLock) {
            adUnitStates.firstOrNull { it.id == adUnitId }?.apply {
                update()
                XLog.d(getLog(operation, toString()))
            } ?: XLog.w(getLog(operation, "Unknown ad unit id: $adUnitId"))
        }
    }
}

@Composable
public fun AnchoredAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        modifier = modifier,
        logTag = "AnchoredAdaptiveBanner",
        adSizeProvider = AdSize::getLargeAnchoredAdaptiveBannerAdSize
    )
}

@Composable
public fun InlineAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        modifier = modifier,
        logTag = "InlineAdaptiveBanner",
        adSizeProvider = AdSize::getCurrentOrientationInlineAdaptiveBannerAdSize
    )
}

@Composable
private fun AdaptiveBannerContent(
    modifier: Modifier = Modifier,
    logTag: String,
    adSizeProvider: (Context, Int) -> AdSize
) {
    val adMob = koinInject<AdMob>()

    if (adMob.initialized) {
        BoxWithConstraints(modifier = modifier) {
            val activity = LocalActivity.current ?: return@BoxWithConstraints
            val adWidth = maxWidth.value.toInt()
            if (adWidth <= 0) return@BoxWithConstraints

            val adSize = remember(activity, adWidth, adSizeProvider) { adSizeProvider(activity, adWidth) }
            val adUnitId = rememberAdUnitId(adMob, activity, adWidth) ?: return@BoxWithConstraints

            AdBox(adMob, adUnitId, adWidth, adSize, activity, logTag)
        }
    }
}

@Composable
private fun rememberAdUnitId(adMob: AdMob, activity: Activity, adWidth: Int): String? {
    var adUnitId by remember(adMob, activity, adWidth) { mutableStateOf<String?>(null) }

    DisposableEffect(adMob, activity, adWidth) {
        val acquiredAdUnitId = adMob.acquireAdUnitId()
        adUnitId = acquiredAdUnitId

        onDispose {
            acquiredAdUnitId?.let(adMob::releaseAdUnit)
        }
    }

    return adUnitId
}

@Composable
private fun AdBox(adMob: AdMob, adUnitId: String, adWidth: Int, adSize: AdSize, activity: Activity, logTag: String) {
    var isSlotVisible by remember(adUnitId, activity, adWidth) { mutableStateOf(false) }
    var slotHeight by remember(adUnitId, activity, adWidth) { mutableStateOf(adSize.height) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (isSlotVisible) slotHeight.dp else 0.dp)
    ) {
        key(adUnitId, activity, adWidth) {
            val scope = rememberCoroutineScope()
            val released = remember { AtomicBoolean(false) }

            AndroidView(
                factory = {
                    XLog.d(getLog(logTag, "factory: $adUnitId"))
                    val adView = AdView(activity)
                    val adRequest = BannerAdRequest.Builder(adUnitId, adSize).build()
                    adMob.recordAdUnitUse(adUnitId, "request")
                    adView.loadAd(
                        adRequest,
                        object : AdLoadCallback<BannerAd> {
                            override fun onAdLoaded(ad: BannerAd) {
                                if (released.get()) return
                                XLog.d(getLog(logTag, "onAdLoaded: $adUnitId"))
                                adMob.recordAdUnitUse(adUnitId, "loaded")
                                ad.adEventCallback = object : BannerAdEventCallback {
                                    override fun onAdImpression() {
                                        if (released.get()) return
                                        XLog.d(getLog(logTag, "onAdImpression: $adUnitId"))
                                        adMob.recordAdUnitUse(adUnitId, "impression")
                                    }

                                    override fun onAdClicked() {
                                        if (released.get()) return
                                        XLog.d(getLog(logTag, "onAdClicked: $adUnitId"))
                                    }
                                }
                                ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                                    override fun onAdRefreshed() {
                                        if (released.get()) return
                                        XLog.d(getLog(logTag, "onAdRefreshed: $adUnitId"))
                                        adMob.recordAdUnitUse(adUnitId, "refreshed")
                                        scope.launch {
                                            if (released.get().not()) {
                                                slotHeight = adView.getBannerAd()?.getAdSize()?.height ?: adSize.height
                                                isSlotVisible = true
                                            }
                                        }
                                    }

                                    override fun onAdFailedToRefresh(adError: LoadAdError) {
                                        if (released.get()) return
                                        XLog.w(getLog(logTag, "onAdFailedToRefresh: $adUnitId $adError"))
                                        adMob.recordAdUnitUse(adUnitId, "refreshFailed")
                                    }
                                }
                                scope.launch {
                                    if (released.get().not()) {
                                        slotHeight = ad.getAdSize().height
                                        isSlotVisible = true
                                    }
                                }
                            }

                            override fun onAdFailedToLoad(adError: LoadAdError) {
                                if (released.get()) return
                                XLog.w(getLog(logTag, "onAdFailedToLoad: $adUnitId $adError"))
                                scope.launch {
                                    if (released.get().not() && adView.getBannerAd() == null) isSlotVisible = false
                                }
                            }
                        },
                    )
                    adView
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { adView ->
                    XLog.d(adView.getLog(logTag, "onRelease: $adUnitId"))
                    released.set(true)
                    adView.destroy()
                },
            )
        }
    }
}

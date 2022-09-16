package admob.plus.cordova

import admob.plus.cordova.ads.AdBase
import admob.plus.cordova.ads.AppOpen
import admob.plus.cordova.ads.Banner
import admob.plus.cordova.ads.Interstitial
import admob.plus.cordova.ads.Native
import admob.plus.cordova.ads.Rewarded
import admob.plus.cordova.ads.RewardedInterstitial
import admob.plus.cordova.ads.getParentView
import admob.plus.core.Adapter
import admob.plus.core.ads
import admob.plus.core.configForTestLabIfNeeded
import admob.plus.core.isRunningInTestLab
import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import com.google.android.gms.ads.MobileAds
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal


private const val TAG = "AdMobPlus"

class AdMob : CordovaPlugin(), Adapter {
    lateinit var context: CallbackContext
    private var readyCallbackContext: CallbackContext? = null
    private val eventQueue: ArrayList<PluginResult> = arrayListOf()

    private val actions = mapOf(
        Actions.READY to ::executeReady,
        Actions.START to ::executeStart,
        Actions.CONFIGURE to ::executeConfigure,
        Actions.CONFIG_REQUEST to ::executeConfigure,
        Actions.AD_CREATE to ::executeAdCreate,
        Actions.AD_IS_LOADED to ::executeAdIsLoaded,
        Actions.AD_LOAD to ::executeAdLoad,
        Actions.AD_SHOW to ::executeAdShow,
        Actions.AD_HIDE to ::executeAdHide,
        Actions.SET_APP_MUTED to ::executeSetAppMute,
        Actions.SET_APP_VOLUME to ::executeSetAppVolume,
    )

    override fun pluginInitialize() {
        super.pluginInitialize()
        Log.i(TAG, "Initialize plugin")
    }

    @Throws(JSONException::class)
    override fun execute(
        action: String,
        data: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        context = callbackContext
        val ctx = ExecuteContext(action, data, callbackContext, this)
        return actions[action]?.invoke(ctx) != null
    }

    private fun executeReady(ctx: ExecuteContext): Boolean {
        if (readyCallbackContext == null) {
            for (result in eventQueue) {
                ctx.sendResult(result)
            }
            eventQueue.clear()
        } else {
            Log.e(TAG, "Ready action should only be called once.")
        }
        readyCallbackContext = ctx.callbackContext
        emit(
            Events.READY,
            mapOf("isRunningInTestLab" to isRunningInTestLab(cordova.activity))
        )
        return true
    }

    private fun executeStart(ctx: ExecuteContext) {
        MobileAds.initialize(ctx.activity) {
            configForTestLabIfNeeded(ctx.activity)
            ctx.resolve(mapOf("version" to MobileAds.getVersionString()))
        }
    }

    private fun executeConfigure(ctx: ExecuteContext) {
        ctx.configure()
        ctx.resolve()
    }

    private fun executeAdCreate(ctx: ExecuteContext) {
        if (ctx.optId() == null) return ctx.reject("id is required")

        ctx.optString("cls")?.also {
            val ad = when (it) {
                "AppOpenAd" -> AppOpen(ctx)
                "BannerAd" -> Banner(ctx)
                "InterstitialAd" -> Interstitial(ctx)
                "NativeAd" -> Native(ctx)
                "RewardedAd" -> Rewarded(ctx)
                "RewardedInterstitialAd" -> RewardedInterstitial(ctx)
                else -> null
            }
            ad?.also {
                ctx.resolve()
            } ?: ctx.reject("ad cls is not supported")
        } ?: ctx.reject("ad cls is missing")
    }

    private fun executeAdIsLoaded(ctx: ExecuteContext) {
        cordova.activity.runOnUiThread {
            ctx.optAdOrReject()?.let { ad ->
                ctx.resolve(ad.isLoaded)
            }
        }
    }

    private fun executeAdLoad(ctx: ExecuteContext) {
        cordova.activity.runOnUiThread {
            ctx.optAdOrReject()?.let { ad ->
                ad.load(ctx)
            }
        }
    }

    private fun executeAdShow(ctx: ExecuteContext) {
        cordova.activity.runOnUiThread {
            ctx.optAdOrReject()?.let { ad ->
                if (ad.isLoaded) {
                    ad.show(ctx)
                } else {
                    ctx.resolve(false)
                }
            }
        }
    }

    private fun executeAdHide(ctx: ExecuteContext) {
        cordova.activity.runOnUiThread {
            ctx.optAdOrReject()?.let { ad ->
                ad.hide(ctx)
            }
        }
    }

    private fun executeSetAppMute(ctx: ExecuteContext) {
        val value = ctx.args.optBoolean(0)
        MobileAds.setAppMuted(value)
        ctx.resolve()
    }

    private fun executeSetAppVolume(ctx: ExecuteContext) {
        val value = BigDecimal.valueOf(ctx.args.optDouble(0)).toFloat()
        MobileAds.setAppVolume(value)
        ctx.resolve()
    }

    override val activity: Activity get() = cordova.activity

    override val contentView: ViewGroup? get() = super.contentView ?: getParentView(webView.view)

    override fun emit(eventName: String, data: Map<String, Any>) {

        val event = JSONObject(mapOf("type" to eventName, "data" to data))
        val result = PluginResult(PluginResult.Status.OK, event)
        result.keepCallback = true
        readyCallbackContext?.sendPluginResult(result) ?: eventQueue.add(result)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        for (i in 0 until ads.size()) {
            val ad = ads.valueAt(i) as AdBase
            ad.onConfigurationChanged(newConfig)
        }
    }


    override fun onPause(multitasking: Boolean) {
        for (i in 0 until ads.size()) {
            val ad = ads.valueAt(i) as AdBase
            ad.onPause(multitasking)
        }
        super.onPause(multitasking)
    }

    override fun onResume(multitasking: Boolean) {
        super.onResume(multitasking)
        for (i in 0 until ads.size()) {
            val ad = ads.valueAt(i) as AdBase
            ad.onResume(multitasking)
        }
    }

    override fun onDestroy() {
        readyCallbackContext = null
        for (i in 0 until ads.size()) {
            val ad = ads.valueAt(i) as AdBase
            ad.onDestroy()
        }
        Banner.destroyParentView()
        super.onDestroy()
    }

    companion object {
        const val NATIVE_VIEW_DEFAULT = Native.VIEW_DEFAULT_KEY

        @JvmStatic
        fun registerNativeAdViewProviders(providers: Map<String, Native.ViewProvider>) {
            Native.providers.putAll(providers)
        }
    }
}
package com.mparticle.kits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mparticle.MParticle
import com.mparticle.consent.CCPAConsent
import com.mparticle.consent.ConsentState
import com.mparticle.consent.GDPRConsent
import com.mparticle.identity.IdentityStateListener
import com.mparticle.identity.MParticleUser
import com.mparticle.internal.Logger
import com.onetrust.otpublishers.headless.Public.Keys.OTBroadcastServiceKeys
import com.onetrust.otpublishers.headless.Public.OTPublishersHeadlessSDK
import com.onetrust.otpublishers.headless.Public.OTVendorListMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject



class OneTrustKit : KitIntegration(), IdentityStateListener {

    internal enum class ConsentRegulation { GDPR, CCPA }
    internal class OneTrustConsent(val purpose: String, val regulation: ConsentRegulation)

    private var categoryReceiver: BroadcastReceiver? = null
    private var deferConsentApplication = false
    private val oneTrustSdk: OTPublishersHeadlessSDK by lazy { OTPublishersHeadlessSDK(getContext()) }

    private lateinit var purposeConsentMapping: Map<String, OneTrustConsent>;
    private lateinit var venderGeneralConsentMapping: Map<String, OneTrustConsent>
    private lateinit var venderIABConsentMapping: Map<String, OneTrustConsent>
    private lateinit var venderGoogleConsentMapping: Map<String, OneTrustConsent>

    companion object {
        private const val MobileConsentGroups = "mobileConsentGroups"
        private const val IabConsentGroups = "vendorIABConsentGroups"
        private const val GoogleConsentGroups = "vendorGoogleConsentGroups"
        private const val GeneralConsentGroups = "vendorGeneralConsentGroups"

        private const val MappingSharedPrefsKey = "OT_mP_Mapping"
        private const val IABSharedPrefsKey = "OT_Vendor_IAB_mP_Mapping"
        private const val GoogleSharedPrefsKey = "OT_Vendor_Google_mP_Mapping"
        private const val GeneralSharedPrefsKey = "OT_Vendor_General_mP_Mapping"

        internal const val CCPAPurposeValue = "data_sale_opt_out"
    }

    override fun getName(): String {
        return "OneTrust"
    }

    override fun setOptOut(optedOut: Boolean): List<ReportingMessage> {
        return listOf()
    }

    public override fun onKitCreate(
        settings: Map<String, String>,
        context: Context
    ): List<ReportingMessage> {
        // Retrieve mParticle --> OneTrust mapping values
        val mobileMappingValues = settings[MobileConsentGroups]
        val mpVendorIABConsentMapping = settings[IabConsentGroups]
        val mpVendorGoogleConsentMapping = settings[GoogleConsentGroups]
        val mpVendorGeneralConsentMapping = settings[GeneralConsentGroups]

        saveToDisk(MappingSharedPrefsKey, mobileMappingValues)
        saveToDisk(IABSharedPrefsKey, mpVendorIABConsentMapping)
        saveToDisk(GoogleSharedPrefsKey, mpVendorGoogleConsentMapping)
        saveToDisk(GeneralSharedPrefsKey, mpVendorGeneralConsentMapping)


        purposeConsentMapping = parseConsentMapping(mobileMappingValues)
        venderIABConsentMapping = parseConsentMapping(mpVendorIABConsentMapping)
        venderGoogleConsentMapping = parseConsentMapping(mpVendorGoogleConsentMapping)
        venderGeneralConsentMapping = parseConsentMapping(mpVendorGeneralConsentMapping)

        updateVendorConsents(venderIABConsentMapping, OTVendorListMode.IAB)
        updateVendorConsents(venderGoogleConsentMapping, OTVendorListMode.GOOGLE)
        updateVendorConsents(venderGeneralConsentMapping, OTVendorListMode.GENERAL)

        // Listen for One Trust Consent changes and create an mPartcle Consent
        // Changes based on these events
        // intent.getAction -> OT cookie name
        // intent.getIntExtras(OTBroadcastServiceKeys.EVENT_STATUS) -> (bool-int) consented?
        categoryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                MParticle.getInstance()?.Identity()?.currentUser
                    .let { user ->
                        if (user == null) {
                            Logger.info("MParticle user is not present, will defer consent application")
                            deferConsentApplication = true
                        } else {
                            val category = intent.action
                            purposeConsentMapping[category]
                                ?.run {
                                    val status = intent.getIntExtra(OTBroadcastServiceKeys.EVENT_STATUS, -1)
                                    Log.i("BroadcastService", "Onetrust Intent name: $category status = $status")
                                    createConsentEvent(user, purpose, status, regulation)
                                } ?: Logger.warning("Onetrusk Kit does not have a puposeConsent mapping for $category")
                        }
                    }
            }
        }
        MParticle.getInstance()?.Identity()?.currentUser
            ?.also { applyCurrentConsentState(it) }
            .let {
                if (it == null) {
                    Logger.info("MParticle user is not present, will defer consent application")
                    deferConsentApplication = true
                }
            }

        // Save mapping to disk. Will be retrieved by OneTrust Mobile SDK
        return listOf()
    }

    override fun getInstance(): Any {
        return oneTrustSdk!!
    }

    override fun onUserIdentified(user: MParticleUser, previousUser: MParticleUser?) {
        if (deferConsentApplication) {
            Logger.info("MParticle user is now present, will apply deferred consent application")
            applyCurrentConsentState(user)
            deferConsentApplication = false
        }
    }

    internal fun applyCurrentConsentState(user: MParticleUser) {
        for (consentElement in purposeConsentMapping.keys) {
            // Register receiver from above based on cookie value dispatched by OneTrust SDK
            context.registerReceiver(categoryReceiver, IntentFilter(consentElement))

            // Fetch Consent Status from OneTrust based on cookie value
            val status = oneTrustSdk.getConsentStatusForGroupId(consentElement)
            purposeConsentMapping[consentElement]?.apply {
                // Dispatch creation of initial consent state till after init is done
                Handler(Looper.myLooper() ?: Looper.getMainLooper()).post {
                    this@OneTrustKit.createConsentEvent(
                        user,
                        purpose,
                        status,
                        regulation
                    )
                }
            }
        }
    }

    // Create an mParticle consent state based on One Trust mapping
    // Status is an integer that probably should be a boolean
    //  1 = Consent Given
    //  0 = Consent Not Given
    // -1 = Consent has not been collected/ sdk is not yet initialized
    internal fun createConsentEvent(
        user: MParticleUser,
        purpose: String?,
        status: Int,
        regulation: ConsentRegulation
    ) {
        var state: ConsentState? = null
        when (regulation) {
            ConsentRegulation.GDPR -> {
                if (purpose == null) {
                    Logger.warning("Purpose is required for GDPR Consent Events")
                    return
                }
                val gdprConsent = GDPRConsent
                    .builder(status == 1)
                    .timestamp(System.currentTimeMillis())
                    .build()
                state = ConsentState
                    .builder()
                    .addGDPRConsentState(purpose, gdprConsent)
                    .build()
            }
            ConsentRegulation.CCPA -> {
                val ccpaConsent = CCPAConsent
                    .builder(status == 1)
                    .timestamp(System.currentTimeMillis())
                    .build()
                state = ConsentState
                    .builder()
                    .setCCPAConsentState(ccpaConsent)
                    .build()
            }
        }
        user.setConsentState(state)
    }

    fun saveToDisk(key: String, mappingData: String?) {
        val context = context
        val sharedPreferences =
            context.getSharedPreferences("com.onetrust.consent.sdk", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(key, mappingData)
        editor.apply()
    }

    internal fun updateVendorConsents(consentMapping: Map<String, OneTrustConsent>, mode: String) {
        consentMapping
            .entries
            .iterator()
            .forEach { (consentKey, mapping) ->
                // Fetch consent keys from one trust and pre-populate
                try {
                    oneTrustSdk.getVendorDetails(mode, consentKey)
                        ?.let { details ->
                            val status = details.optString("consent", null).toIntOrNull()
                            createConsentEvent(mapping, status)
                        }
                } catch (ex: NumberFormatException) {
                    Logger.warning(ex, "unable to fetch vendor details for $mode: $consentKey")
                }
            }
    }

    internal fun createConsentEvent(consentMapping: OneTrustConsent, status: Int?) {
        val user = MParticle.getInstance()?.Identity()?.currentUser ?: Logger.warning("current user is not present").let { return }
        val consented = status == 1

        var consentState = user.consentState.let { ConsentState.withConsentState(it) }
        when (consentMapping.regulation) {
            ConsentRegulation.GDPR -> {
                consentState.addGDPRConsentState(consentMapping.purpose, GDPRConsent.builder(consented).build())
            }
            ConsentRegulation.CCPA -> {
                consentState.setCCPAConsentState(CCPAConsent.builder(consented).build())
            }
        }
        user.setConsentState(consentState.build())
    }

    internal fun parseConsentMapping(json: String?): Map<String, OneTrustConsent> {
        if (json.isNullOrEmpty()) {
            return mapOf()
        }
        return try {
            JSONArray(json)
                .let { jsonArray ->
                    (0 until jsonArray.length())
                        .map { index ->
                            jsonArray.optJSONObject(index) ?: JSONObject()
                        }
                }
        } catch (jse: JSONException) {
            Logger.warning(jse, "OneTrust parsing error")
            listOf()
        }.map {
                    val cookieValue = it.optString("value", null)
                    val purpose = it.optString("map", null)
                    val regulation = when (purpose) {
                        CCPAPurposeValue -> ConsentRegulation.CCPA
                        else -> ConsentRegulation.GDPR
                    }
                    if (cookieValue.isNullOrEmpty() && purpose.isNullOrEmpty()) {
                        Logger.warning("Consent Object is missing value and map: $this")
                        null
                    } else {
                        cookieValue to OneTrustConsent(purpose, regulation)
                    }
                }
            .filterNotNull()
            .associate { it.first to it.second }
    }
}
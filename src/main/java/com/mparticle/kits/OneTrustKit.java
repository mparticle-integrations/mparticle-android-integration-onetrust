package com.mparticle.kits;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mparticle.MParticle;
import com.mparticle.consent.ConsentState;
import com.mparticle.consent.GDPRConsent;
import com.mparticle.identity.IdentityStateListener;
import com.mparticle.identity.MParticleUser;
import com.mparticle.internal.Logger;
import com.mparticle.internal.MPUtility;
import com.onetrust.otpublishers.headless.Public.Keys.OTBroadcastServiceKeys;
import com.onetrust.otpublishers.headless.Public.OTPublishersHeadlessSDK;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class OneTrustKit extends KitIntegration implements IdentityStateListener {

    private final static String MP_MOBILE_CONSENT_GROUPS = "mobileConsentGroups";
    private final static String ONETRUST_PREFS = "OT_mP_Mapping";
    private Context m_context;

    private BroadcastReceiver categoryReceiver;
    final Map<String, String> consentMapping = new HashMap<String, String>();
    boolean deferConsentApplication = false;

    @Override
    public String getName() {
        return "OneTrust";
    }

    @Override
    public List<ReportingMessage> setOptOut(boolean optedOut) {
        return null;
    }

    @Override
    protected List<ReportingMessage> onKitCreate(Map<String, String> settings, final Context context) {
        // Retrieve mParticle --> OneTrust mapping values
        String mobileMappingValues = settings.get(MP_MOBILE_CONSENT_GROUPS);

        if (mobileMappingValues != null) {
            try {
                // Convert mobileMappingValues and extract new consentMapping
                // value -> OneTrust Cookie Value
                // map -> mParticle Consent Purpose
                JSONArray consentJSONArray = new JSONArray(mobileMappingValues);
                for (int i = 0; i < consentJSONArray.length(); i++) {
                    JSONObject consentJSONObject = consentJSONArray.optJSONObject(i);

                    String value = consentJSONObject.optString("value");
                    String map = consentJSONObject.optString("map");

                    if (MPUtility.isEmpty(value) && MPUtility.isEmpty(map)) {
                        Logger.warning("Consent Object is missing value and map: " + consentJSONObject.toString());
                    } else {
                        consentMapping.put(value, map);
                    }

                }

            } catch (JSONException jsonException) {
                Logger.warning(jsonException, "MP OT JSON ERROR");
            }
        }

        // Listen for One Trust Consent changes and create ne mPartcle Consent
        // Changes based on these events
        // intent.getAction -> OT cookie name
        // intent.getIntExtras(OTBroadcastServiceKeys.EVENT_STATUS) -> (bool-int) consented?
        categoryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                MParticleUser user = getCurrentUser();

                if (user != null) {
                    String category = intent.getAction();
                    String purpose = consentMapping.get(category);
                    int status = intent.getIntExtra(OTBroadcastServiceKeys.EVENT_STATUS, -1);

                    Log.i("BroadcastService", "MP OT Intent name: " + category + " status = " + status);

                    OneTrustKit.this.createConsentEvent(user, purpose, status);
                } else {
                    deferConsentApplication = true;
                }

            }
        };

        MParticleUser user = getCurrentUser();
        if (user != null) {
            applyCurrentConsentState(user);
        } else {
            deferConsentApplication = true;
        }

        // Save mapping to disk. Will be retrieved by OneTrust Mobile SDK
        saveToDisk(mobileMappingValues);
        return null;
    }

    @Override
    public void onUserIdentified(@NonNull MParticleUser user, @Nullable MParticleUser previousUser) {
        if (deferConsentApplication) {
            applyCurrentConsentState(user);
            deferConsentApplication = false;
        }
    }

    private void applyCurrentConsentState(final MParticleUser user) {
        for(final String consentElement: consentMapping.keySet()) {
            // Register receiver from above based on cookie value dispatched by OneTrust SDK
            getContext().registerReceiver(categoryReceiver, new IntentFilter(consentElement));

            // Fetch Consent Status from OneTrust based on cookie value
            final int status = new OTPublishersHeadlessSDK(getContext()).getConsentStatusForGroupId(consentElement);

            // Dispatch creation of initial consent state till after init is done
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    OneTrustKit.this.createConsentEvent(user, consentMapping.get(consentElement), status);
                }
            });
        }
    }

    // Create an mParticle consent state based on One Trust mapping
    // Status is an integer that probably should be a boolean
    //  1 = Consent Given
    //  0 = Consent Not Given
    // -1 = Consent has not been collected/ sdk is not yet initialized
    private void createConsentEvent(MParticleUser user, String purpose, Integer status) {
        GDPRConsent gdprConsent = GDPRConsent
                .builder(status.intValue() == 1)
                .timestamp(System.currentTimeMillis())
                .build();

        final ConsentState state = ConsentState
                .builder()
                .addGDPRConsentState(purpose, gdprConsent)
                .build();

        user.setConsentState(state);
    }

     public void saveToDisk(String mappingData){
        Context context = getContext();
        SharedPreferences sharedPreferences = context.getSharedPreferences("com.onetrust.consent.sdk", context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(ONETRUST_PREFS, mappingData);
        editor.apply();
    }
}

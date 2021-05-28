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

import com.mparticle.MParticle;
import com.mparticle.consent.ConsentState;
import com.mparticle.consent.GDPRConsent;
import com.mparticle.identity.MParticleUser;
import com.mparticle.internal.Logger;
import com.onetrust.otpublishers.headless.Public.Keys.OTBroadcastServiceKeys;
import com.onetrust.otpublishers.headless.Public.OTPublishersHeadlessSDK;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class OneTrustKit extends KitIntegration {

    private final static String MP_MOBILE_CONSENT_GROUPS = "mobileConsentGroups";
    private final static String ONETRUST_PREFS = "OT_mP_Mapping";
    private Context m_context;

    private BroadcastReceiver categoryReceiver;

    @Override
    protected List<ReportingMessage> onKitCreate(Map<String, String> settings, final Context context) {
        // Retrieve mParticle --> OneTrust mapping values
        String mobileMappingValues = settings.get(MP_MOBILE_CONSENT_GROUPS);

        final Map<String, String> consentMapping = new HashMap<String, String>();

        if (mobileMappingValues != null) {
            try {
                // Convert mobileMappingValues and extract new consentMapping
                // value -> OneTrust Cookie Value
                // map -> mParticle Consent Purpose
                JSONArray consentJSONArray = new JSONArray(mobileMappingValues);
                for (int i = 0; i < consentJSONArray.length(); i++) {
                    JSONObject consentJSONObject = consentJSONArray.optJSONObject(i);

                    consentMapping.put(consentJSONObject.getString("value"), consentJSONObject.getString("map"));
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
                Log.i("BroadcastService", "MP OT Intent name: " + intent.getAction() +
                        " status = " + intent.getIntExtra(OTBroadcastServiceKeys.EVENT_STATUS, -1));

                OneTrustKit.this.createConsentEvent(intent.getAction(), intent.getIntExtra(OTBroadcastServiceKeys.EVENT_STATUS, -1));
            }
        };

        for(final String consentElement: consentMapping.keySet()) {
            // Register receiver from above based on cookie value dispatched by OneTrust SDK
            context.registerReceiver(categoryReceiver, new IntentFilter(consentElement));

            // Fetch Consent Status from OneTrust based on cookie value
            new OTPublishersHeadlessSDK(context).getConsentStatusForGroupId(consentElement);

            // Dispatch creation of initial consent state till after init is done
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    OneTrustKit.this.createConsentEvent(consentMapping.get(consentElement), 0);
                }
            });
        }


        // Save mapping to disk. Will be retrieved by OneTrust Mobile SDK
        saveToDisk(mobileMappingValues);
        return null;
    }

    // Create an mParticle consent state based on One Trust mapping
    // Status is an integer that probably should be a boolean
    //  1 = Consent Given
    //  0 = Consent Not Given
    // -1 = Consent has not been collected/ sdk is not yet initialized
    private void createConsentEvent(String purpose, Integer status) {
        MParticleUser user = MParticle.getInstance().Identity().getCurrentUser();

        GDPRConsent gdprConsent = GDPRConsent
                .builder(status.intValue() == 1)
                .timestamp(System.currentTimeMillis())
                .build();

        ConsentState state = ConsentState
                .builder()
                .addGDPRConsentState(purpose, gdprConsent)
                .build();

        user.setConsentState(state);
    }


    @Override
    public String getName() {
        return "OneTrust";
    }



    @Override
    public List<ReportingMessage> setOptOut(boolean optedOut) {
        return null;
    }
    
     public void saveToDisk(String mappingData){
        Context context = getContext();
        SharedPreferences sharedPreferences = context.getSharedPreferences("com.onetrust.consent.sdk", context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(ONETRUST_PREFS, mappingData);
        editor.apply();
    }
}

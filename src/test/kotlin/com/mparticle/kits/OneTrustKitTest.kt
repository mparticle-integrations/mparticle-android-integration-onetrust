package com.mparticle.kits

import android.content.Context
import com.mparticle.consent.CCPAConsent
import com.mparticle.consent.ConsentState
import com.mparticle.consent.GDPRConsent
import com.mparticle.identity.MParticleUser
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class KitTests {
    private val kit = OneTrustKit()

    @Test
    @Throws(Exception::class)
    fun testGetName() {
        val name = kit.name
        Assert.assertTrue(name.isNotEmpty())
    }

    /**
     * Kit *should* throw an exception when they're initialized with the wrong settings.
     *
     */
    @Test
    @Throws(Exception::class)
    fun testOnKitCreate() {
        var e: Exception? = null
        try {
            val kit = kit
            val settings: MutableMap<String, String> = mutableMapOf()
            settings["fake setting"] = "fake"
            kit.onKitCreate(settings, Mockito.mock(Context::class.java))
        } catch (ex: Exception) {
            e = ex
        }
        Assert.assertNotNull(e)
    }

    /**
     * This test should ensure that whatever the consent state is, if a new GDPR consent is created,
     * it should be added to the consent state  GDPR map
     */
    @Test
    fun testCreateConsentEventGDPR() {
        val user = Mockito.mock(MParticleUser::class.java)
        val gdprConsent = GDPRConsent.builder(false).build()
        var currentConsentState =
            ConsentState.builder().addGDPRConsentState("purpose1", gdprConsent).build()
        `when`(user.consentState).thenReturn(currentConsentState)
        assertEquals(1, currentConsentState.gdprConsentState.size)
        assertEquals(gdprConsent, currentConsentState.gdprConsentState.get("purpose1"))
        assertNull(currentConsentState.ccpaConsentState)
        currentConsentState =
            kit.createConsentEvent(user, "purpose2", 1, OneTrustKit.ConsentRegulation.GDPR)!!
        assertEquals(2, currentConsentState.gdprConsentState.size)
        assertEquals(gdprConsent, currentConsentState.gdprConsentState.get("purpose1"))
        assertTrue(currentConsentState.gdprConsentState.containsKey("purpose2"))
        assertEquals(true, currentConsentState.gdprConsentState.get("purpose2")!!.isConsented)
        assertNull(currentConsentState.ccpaConsentState)
    }

    /**
     * This test must ensure that any CCPA consent creates is added to the constent state.
     * By design a new CCPA consent overrides the previous one.
     */
    @Test
    fun testCreateConsentEventCCPA() {
        val user = Mockito.mock(MParticleUser::class.java)
        val ccpaConsent = CCPAConsent.builder(false).location("loc1").build()
        var currentConsentState = ConsentState.builder().setCCPAConsentState(ccpaConsent).build()
        `when`(user.consentState).thenReturn(currentConsentState)
        assertEquals(0, currentConsentState.gdprConsentState.size)
        assertEquals(ccpaConsent, currentConsentState.ccpaConsentState)
        assertEquals("loc1", currentConsentState.ccpaConsentState?.location)
        assertEquals(false, currentConsentState.ccpaConsentState?.isConsented)

        currentConsentState =
            kit.createConsentEvent(user, "ccpa", 1, OneTrustKit.ConsentRegulation.CCPA)!!
        assertEquals(0, currentConsentState.gdprConsentState.size)
        assertEquals(true, currentConsentState.ccpaConsentState?.isConsented)
        assertNull(currentConsentState.ccpaConsentState?.location)
    }

    @Test
    @Throws(Exception::class)
    fun testClassName() {
        val factory = KitIntegrationFactory()
        val integrations = factory.knownIntegrations
        val className = kit.javaClass.name
        for ((_, value) in integrations) {
            if (value == className) {
                return
            }
        }
        Assert.fail("$className not found as a known integration.")
    }

    @Test
    fun testParseConsent() {
        val consentMapping =
            """
                [
                    {
                        "value": "topic1",
                        "map": "purpose1"
                    },
                    {
                        "value": "topic2",
                        "map": "${OneTrustKit.CCPAPurposeValue}"
                    },
                    {
                        "value": "topic3",
                        "map": "purpose3"
                    },
                ]
            """.trimIndent()
        val map = kit.parseConsentMapping(consentMapping)
        assertEquals(3, map.size)
        map["topic1"].let {
            assertNotNull(it)
            assertEquals(OneTrustKit.ConsentRegulation.GDPR, it?.regulation)
            assertEquals("purpose1", it?.purpose)
        }
        map["topic2"].let {
            assertNotNull(it)
            assertEquals(OneTrustKit.ConsentRegulation.CCPA, it?.regulation)
            assertEquals(OneTrustKit.CCPAPurposeValue, it?.purpose)
        }
        map["topic3"].let {
            assertNotNull(it)
            assertEquals(OneTrustKit.ConsentRegulation.GDPR, it?.regulation)
            assertEquals("purpose3", it?.purpose)
        }
    }
}

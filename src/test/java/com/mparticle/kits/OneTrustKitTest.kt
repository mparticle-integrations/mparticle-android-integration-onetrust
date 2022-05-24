package com.mparticle.kits

import android.content.Context
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito

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
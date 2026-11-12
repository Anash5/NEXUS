package org.nexus.client.sample

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nexus.client.sample.service.NexusClientManager
import org.nexus.interop.contract.NexusResponse

class NexusClientManagerTest {

    private val dummyContext = object : android.content.ContextWrapper(null) {}
    private val clientManager = NexusClientManager(dummyContext)

    @Test
    fun testClientSidePayloadLimitRejection() = runBlocking {
        // UTF-8 payload exceeding 64 KB (65,536 bytes) must be rejected client-side before Binder transmission (Correction 3)
        val oversizedPayload = "A".repeat(70000)
        val response = clientManager.executeCapability("nexus.text.uppercase", oversizedPayload)

        assertFalse(response.isSuccess)
        assertEquals(NexusResponse.STATUS_PAYLOAD_TOO_LARGE, response.statusCode)
        assertTrue(response.errorMessage?.contains("64 KB") == true)
    }

    @Test
    fun testUnconnectedClientReturnsDisconnectedStatus() = runBlocking {
        val response = clientManager.executeCapability("nexus.text.uppercase", "short text")

        assertFalse(response.isSuccess)
        assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, response.statusCode)
    }
}

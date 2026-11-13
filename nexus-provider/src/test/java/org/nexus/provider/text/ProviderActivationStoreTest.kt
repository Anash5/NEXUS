package org.nexus.provider.text

import org.junit.Assert.*
import org.junit.Test
import org.nexus.provider.text.service.ProviderActivationStore
import org.nexus.provider.text.service.TextProviderService

class ProviderActivationStoreTest {

    private class InMemoryPrefsBackend : ProviderActivationStore.ActivationPreferenceBackend {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }
    }

    @Test
    fun testDefaultIsDisabled() {
        val store = ProviderActivationStore(InMemoryPrefsBackend())
        assertFalse(store.isActivationEnabled())
        assertFalse(store.resolveAndPersistForStartAction(null))
    }

    @Test
    fun testActionStartPersistsAcrossStickyRestart() {
        val sharedBackend = InMemoryPrefsBackend()
        val storeInstance1 = ProviderActivationStore(sharedBackend)

        assertTrue(storeInstance1.resolveAndPersistForStartAction(TextProviderService.ACTION_START))
        assertTrue(storeInstance1.isActivationEnabled())

        // Simulate new process / service instance reading persisted activation_enabled on START_STICKY (intent == null)
        val storeAfterRestart = ProviderActivationStore(sharedBackend)
        assertTrue(storeAfterRestart.isActivationEnabled())
        assertTrue(storeAfterRestart.resolveAndPersistForStartAction(null))
    }

    @Test
    fun testActionStopPersistsDeactivatedAcrossRestart() {
        val sharedBackend = InMemoryPrefsBackend()
        val store1 = ProviderActivationStore(sharedBackend)
        store1.resolveAndPersistForStartAction(TextProviderService.ACTION_START)
        assertTrue(store1.isActivationEnabled())

        assertFalse(store1.resolveAndPersistForStartAction(TextProviderService.ACTION_STOP))
        assertFalse(store1.isActivationEnabled())

        val store2 = ProviderActivationStore(sharedBackend)
        assertFalse(store2.isActivationEnabled())
        assertFalse(store2.resolveAndPersistForStartAction(null))
    }
}

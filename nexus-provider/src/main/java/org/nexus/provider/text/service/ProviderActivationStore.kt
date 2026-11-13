package org.nexus.provider.text.service

import android.content.Context

/**
 * Persists the user's explicit Provider activation preference (`activation_enabled`)
 * so START_STICKY OS restarts and Activity recreations honor the last user choice
 * while keeping explicit deactivation durable.
 */
class ProviderActivationStore(
    private val backend: ActivationPreferenceBackend
) {

    constructor(context: Context) : this(SharedPreferencesActivationBackend(context.applicationContext))

    interface ActivationPreferenceBackend {
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    private class SharedPreferencesActivationBackend(context: Context) : ActivationPreferenceBackend {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return prefs.getBoolean(key, defaultValue)
        }

        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).commit()
        }
    }

    fun isActivationEnabled(): Boolean = backend.getBoolean(KEY_ACTIVATION_ENABLED, false)

    fun setActivationEnabled(enabled: Boolean) {
        backend.putBoolean(KEY_ACTIVATION_ENABLED, enabled)
    }

    /**
     * Evaluates a service start intent action and updates persisted activation state:
     * - ACTION_START -> persists true, returns true
     * - ACTION_STOP -> persists false, returns false
     * - null (START_STICKY system restart) -> reads persisted value without overwriting it
     */
    fun resolveAndPersistForStartAction(action: String?): Boolean {
        return when (action) {
            TextProviderService.ACTION_START -> {
                setActivationEnabled(true)
                true
            }
            TextProviderService.ACTION_STOP -> {
                setActivationEnabled(false)
                false
            }
            else -> isActivationEnabled()
        }
    }

    companion object {
        const val PREFS_NAME = "nexus_provider_prefs"
        const val KEY_ACTIVATION_ENABLED = "activation_enabled"
    }
}

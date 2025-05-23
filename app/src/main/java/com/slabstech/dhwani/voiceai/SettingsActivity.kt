package com.slabstech.dhwani.voiceai

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Validate Transcription API Endpoint
            findPreference<EditTextPreference>("transcription_api_endpoint")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = validateUrl(newValue.toString())
                    if (!isValid) {
                        Toast.makeText(context, "Invalid Transcription API URL", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Validate Chat API Endpoint
            findPreference<EditTextPreference>("chat_api_endpoint")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = validateUrl(newValue.toString())
                    if (!isValid) {
                        Toast.makeText(context, "Invalid Chat API URL", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Validate Chat API Key
            findPreference<EditTextPreference>("chat_api_key")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = newValue.toString().isNotEmpty()
                    if (!isValid) {
                        Toast.makeText(context, "Chat API Key cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Test Endpoints Button
            findPreference<Preference>("test_endpoints")?.apply {
                setOnPreferenceClickListener {
                    testEndpoints()
                    true
                }
            }

            // Update TTS Enabled Summary
            findPreference<SwitchPreferenceCompat>("tts_enabled")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = if (newValue as Boolean) "Text-to-speech is enabled" else "Text-to-speech is disabled"
                    true
                }
                summary = if (isChecked) "Text-to-speech is enabled" else "Text-to-speech is disabled"
            }

            // Update Auto-Play TTS Summary
            findPreference<SwitchPreferenceCompat>("auto_play_tts")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = if (newValue as Boolean) "TTS audio plays automatically" else "TTS audio requires manual play"
                    true
                }
                summary = if (isChecked) "TTS audio plays automatically" else "TTS audio requires manual play"
            }

            // Update TTS Voice Summary
            findPreference<ListPreference>("tts_voice")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    val index = entryValues.indexOf(newValue)
                    if (index >= 0) {
                        preference.summary = entries[index]
                    }
                    true
                }
                val initialIndex = entryValues.indexOf(value)
                if (initialIndex >= 0) {
                    summary = entries[initialIndex]
                }
            }
        }

        private fun validateUrl(url: String): Boolean {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) return false
            if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return false
            val hostPart = trimmedUrl.substringAfter("://").substringBefore("/")
            if (hostPart.isEmpty()) return false
            val validHostPattern = Regex("^[a-zA-Z0-9.-]+(:[0-9]+)?$")
            return validHostPattern.matches(hostPart)
        }

        private fun testEndpoints() {


        }

        private suspend fun testEndpoint(url: String): Boolean = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }
}
package com.slabstech.dhwani.voiceai.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.slabstech.dhwani.voiceai.AuthManager
import com.slabstech.dhwani.voiceai.Message
import com.slabstech.dhwani.voiceai.MessageAdapter
import com.slabstech.dhwani.voiceai.R
import com.slabstech.dhwani.voiceai.RetrofitClient
import com.slabstech.dhwani.voiceai.TranslationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

object SpeechUtils {
    private const val AUTO_PLAY_KEY = "auto_play_tts"
    private const val MAX_TTS_INPUT_LENGTH = 1000 // Matches server-side limit in main.py

    // Language codes from resources and AnswerActivity
    private val europeanLanguages = setOf(
        "eng_Latn", // English
        "deu_Latn", // German
        "fra_Latn", // French
        "nld_Latn"  // Dutch
    )

    private val indianLanguages = setOf(
        "hin_Deva",  // Hindi
        "kan_Knda",  // Kannada
        "tam_Taml",  // Tamil
        "mal_Mlym",  // Malayalam
        "tel_Telu"   // Telugu
    )

    fun textToSpeech(
        context: Context,
        scope: LifecycleCoroutineScope,
        text: String, // Encrypted text (Base64-encoded AES-GCM)
        message: Message,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        ttsProgressBarVisibility: (Boolean) -> Unit,
        srcLang: String? = null,
        sessionKey: ByteArray? = null // Required for X-Session-Key header
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("tts_enabled", false)) {
            Log.d("SpeechUtils", "TTS disabled in preferences")
            return
        }
        val token = AuthManager.getToken(context)
        if (token == null) {
            Log.e("SpeechUtils", "Authentication token is null")
            Toast.makeText(context, "Authentication error. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }
        if (sessionKey == null) {
            Log.e("SpeechUtils", "Session key is null, cannot proceed with TTS")
            Toast.makeText(context, "Session error. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        // Truncate encrypted text to avoid exceeding server limits (rough estimate)
        val truncatedText = if (text.length > MAX_TTS_INPUT_LENGTH * 2) { // Encrypted text is larger than plain text
            Log.w("SpeechUtils", "Encrypted input text too long (${text.length} chars), truncating")
            text.substring(0, MAX_TTS_INPUT_LENGTH * 2)
        } else {
            text
        }

        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        scope.launch {
            ttsProgressBarVisibility(true)
            try {
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                Log.d("SpeechUtils", "Calling TTS API with input length: ${truncatedText.length}")
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService(context).textToSpeech(
                        input = truncatedText,
                        token = "Bearer $token",
                        sessionKey = cleanSessionKey
                    )
                }
                val audioBytes = withContext(Dispatchers.IO) {
                    response.byteStream().use { it.readBytes() }
                }
                if (audioBytes.isNotEmpty()) {
                    val audioFile = File(context.cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(audioFile).use { fos -> fos.write(audioBytes) }
                    }
                    if (audioFile.exists() && audioFile.length() > 0) {
                        message.audioFile = audioFile
                        withContext(Dispatchers.Main) {
                            val messageIndex = adapter.messages.indexOf(message)
                            if (messageIndex != -1) {
                                adapter.notifyItemChanged(messageIndex)
                            }
                            if (autoPlay) {
                                playAudio(
                                    context = context,
                                    audioFile = audioFile,
                                    recyclerView = recyclerView,
                                    adapter = adapter,
                                    message = message,
                                    playIconResId = android.R.drawable.ic_media_play,
                                    stopIconResId = R.drawable.ic_media_stop
                                )
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "TTS returned empty audio", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.e("SpeechUtils", "TTS timeout: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Text-to-speech timed out. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: retrofit2.HttpException) {
                Log.e("SpeechUtils", "TTS HTTP error: ${e.message}, response: ${e.response()?.errorBody()?.string()}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                Log.e("SpeechUtils", "TTS IO error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("SpeechUtils", "TTS failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    ttsProgressBarVisibility(false)
                }
            }
        }
    }

    fun translate(
        context: Context,
        scope: LifecycleCoroutineScope,
        sentences: List<String>,
        srcLang: String,
        tgtLang: String,
        sessionKey: ByteArray,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val token = AuthManager.getToken(context) ?: return

        // Encrypt sentences
        val encryptedSentences = sentences.map { RetrofitClient.encryptText(it, sessionKey) }
        val translationRequest = TranslationRequest(encryptedSentences, srcLang, tgtLang)

        scope.launch {
            try {
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                val response = RetrofitClient.apiService(context).translate(
                    translationRequest,
                    "Bearer $token",
                    cleanSessionKey
                )
                val translatedText = response.translations.joinToString("\n")
                onSuccess(translatedText)
            } catch (e: Exception) {
                Log.e("SpeechUtils", "Translation failed: ${e.message}", e)
                onError(e)
            }
        }
    }

    fun playAudio(
        context: Context,
        audioFile: File,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        message: Message,
        playIconResId: Int = android.R.drawable.ic_media_play,
        stopIconResId: Int = R.drawable.ic_media_stop,
        mediaPlayer: MediaPlayer? = null
    ) {
        if (!audioFile.exists()) {
            Toast.makeText(context, "Audio file doesn't exist", Toast.LENGTH_SHORT).show()
            return
        }

        val player = mediaPlayer ?: MediaPlayer()
        try {
            player.apply {
                reset()
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
                val messageIndex = adapter.messages.indexOf(message)
                if (messageIndex != -1) {
                    val holder = recyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                    holder?.audioControlButton?.setImageResource(stopIconResId)
                }
                setOnCompletionListener {
                    it.release()
                    if (messageIndex != -1) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val holder = recyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                            holder?.audioControlButton?.setImageResource(playIconResId)
                        }
                    }
                }
                setOnErrorListener { _, what, extra ->
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("SpeechUtils", "Audio playback failed: ${e.message}", e)
            player.release()
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "Audio playback failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleAudioPlayback(
        context: Context,
        message: Message,
        button: ImageButton,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        mediaPlayer: MediaPlayer?,
        playIconResId: Int = android.R.drawable.ic_media_play,
        stopIconResId: Int = R.drawable.ic_media_stop
    ): MediaPlayer? {
        var currentPlayer = mediaPlayer
        message.audioFile?.let { audioFile ->
            if (currentPlayer?.isPlaying == true) {
                currentPlayer.stop()
                currentPlayer.release()
                currentPlayer = null
                button.setImageResource(playIconResId)
            } else {
                currentPlayer = MediaPlayer()
                playAudio(
                    context = context,
                    audioFile = audioFile,
                    recyclerView = recyclerView,
                    adapter = adapter,
                    message = message,
                    playIconResId = playIconResId,
                    stopIconResId = stopIconResId,
                    mediaPlayer = currentPlayer
                )
                button.setImageResource(stopIconResId)
            }
        }
        return currentPlayer
    }
}
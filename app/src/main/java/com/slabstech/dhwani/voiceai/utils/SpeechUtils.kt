package com.slabstech.dhwani.voiceai.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.slabstech.dhwani.voiceai.Message
import com.slabstech.dhwani.voiceai.MessageAdapter
import com.slabstech.dhwani.voiceai.R
import com.slabstech.dhwani.voiceai.RetrofitClient
import com.slabstech.dhwani.voiceai.TTSRequest
import com.slabstech.dhwani.voiceai.TranslationRequest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object SpeechUtils {
    private const val AUTO_PLAY_KEY = "auto_play_tts"

    fun textToSpeech(
        context: Context,
        scope: LifecycleCoroutineScope,
        text: String,
        message: Message,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        ttsProgressBarVisibility: (Boolean) -> Unit
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("tts_enabled", false)) return
        val voice = prefs.getString(
            "tts_voice",
            "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality."
        ) ?: "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality."
        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        scope.launch {
            ttsProgressBarVisibility(true)
            try {
                val ttsRequest = TTSRequest(
                    input = text,
                    voice = voice,
                    model = "ai4bharat/indic-parler-tts",
                    response_format = "mp3",
                    speed = 1.0
                )
                Log.d("SpeechUtils", "TTS Request: $ttsRequest")
                val response = RetrofitClient.apiService(context).textToSpeech(ttsRequest)
                val audioBytes = response.byteStream().readBytes()
                Log.d("SpeechUtils", "TTS Response: byte size=${audioBytes.size}")
                if (audioBytes.isNotEmpty()) {
                    val audioFile = File(context.cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(audioFile).use { fos -> fos.write(audioBytes) }
                    if (audioFile.exists() && audioFile.length() > 0) {
                        message.audioFile = audioFile
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
                    } else {
                        Toast.makeText(context, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "TTS returned empty audio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SpeechUtils", "TTS failed: ${e.message}", e)
                Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                ttsProgressBarVisibility(false)
            }
        }
    }

    fun translate(
        context: Context,
        scope: LifecycleCoroutineScope,
        sentences: List<String>,
        srcLang: String,
        tgtLang: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val response = RetrofitClient.apiService(context).translate(
                    TranslationRequest(sentences, srcLang, tgtLang)
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
                    val holder = recyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                    holder?.audioControlButton?.setImageResource(playIconResId)
                }
            }
            setOnErrorListener { _, what, extra ->
                Toast.makeText(context, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                true
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
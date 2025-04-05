package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class VoiceDetectionActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var toggleRecordButton: ToggleButton
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: Toolbar
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_ENERGY_THRESHOLD = 0.02f
    private val MIN_RECORDING_DURATION_MS = 1000L
    private val CHUNK_DURATION_MS = 5000L // 5 seconds
    private var recordingStartTime: Long = 0L
    private var currentTheme: Boolean? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var sessionDialog: AlertDialog? = null
    private var mediaPlayer: MediaPlayer? = null
    private val audioQueue: Queue<File> = LinkedList() // Queue for sequential playback
    private var isPlaying = false

    // Hardcoded Retrofit setup
    private val speechToSpeechApi: SpeechToSpeechApi by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://slabstech-dhwani-internal-api-server.hf.space/")
            .client(okHttpClient)
            .build()
            .create(SpeechToSpeechApi::class.java)
    }

    interface SpeechToSpeechApi {
        @Multipart
        @POST("v1/speech_to_speech")
        suspend fun speechToSpeech(
            @Query("language") language: String,
            @Part file: MultipartBody.Part,
            @Part("voice") voice: RequestBody,
            @Header("Authorization") token: String
        ): ResponseBody
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_detection)

        checkAuthentication()

        try {
            toggleRecordButton = findViewById(R.id.toggleRecordButton)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_CODE
                )
            }

            toggleRecordButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    startRecording()
                } else {
                    stopRecording()
                }
            }

            bottomNavigation.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_answer -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Answers?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, AnswerActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_translate -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Translate?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, TranslateActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_docs -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Docs?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, DocsActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    else -> false
                }
            }
            bottomNavigation.selectedItemId = R.id.nav_voice
        } catch (e: Exception) {
            Log.e("VoiceDetectionActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@VoiceDetectionActivity) || !AuthManager.refreshTokenIfNeeded(this@VoiceDetectionActivity)) {
                Log.d("VoiceDetectionActivity", "Authentication failed, logging out")
                AuthManager.logout(this@VoiceDetectionActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
            return
        }

        sessionDialog = AlertDialog.Builder(this)
            .setMessage("Checking session...")
            .setCancelable(false)
            .create()
        sessionDialog?.show()

        lifecycleScope.launch {
            val tokenValid = AuthManager.refreshTokenIfNeeded(this@VoiceDetectionActivity)
            Log.d("VoiceDetectionActivity", "onResume: Token valid = $tokenValid")
            if (tokenValid) {
                sessionDialog?.dismiss()
                sessionDialog = null
            } else {
                sessionDialog?.dismiss()
                sessionDialog = null
                AlertDialog.Builder(this@VoiceDetectionActivity)
                    .setTitle("Session Expired")
                    .setMessage("Your session could not be refreshed. Please log in again.")
                    .setPositiveButton("OK") { _, _ ->
                        AuthManager.logout(this@VoiceDetectionActivity)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logout -> {
                AuthManager.logout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            toggleRecordButton.isChecked = false
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2 // Increase buffer size
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioFile = File(cacheDir, "voice_detection_audio.wav")
        val audioBuffer = ByteArray(bufferSize)
        val recordedData = mutableListOf<Byte>()
        var lastChunkTime = 0L

        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        audioRecord?.startRecording()

        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        val energy = calculateEnergy(audioBuffer, bytesRead)
                        withContext(Dispatchers.Main) {
                            audioLevelBar.progress = (energy * 100).toInt().coerceIn(0, 100)
                        }
                        if (energy > MIN_ENERGY_THRESHOLD) {
                            recordedData.addAll(audioBuffer.take(bytesRead))
                        }

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastChunkTime >= CHUNK_DURATION_MS && recordedData.isNotEmpty()) {
                            val chunkData = recordedData.toByteArray()
                            if (hasVoice(chunkData)) {
                                processAudioChunk(chunkData)
                            } else {
                                Log.d("VoiceDetectionActivity", "Chunk skipped: No voice detected")
                            }
                            recordedData.clear()
                            lastChunkTime = currentTime
                        }
                    } else if (bytesRead < 0) {
                        Log.e("VoiceDetectionActivity", "AudioRecord read error: $bytesRead")
                    }
                }

                // Process any remaining audio when recording stops
                if (recordedData.isNotEmpty()) {
                    val finalChunkData = recordedData.toByteArray()
                    if (hasVoice(finalChunkData)) {
                        processAudioChunk(finalChunkData)
                    } else {
                        Log.d("VoiceDetectionActivity", "Final chunk skipped: No voice detected")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                withContext(Dispatchers.Main) {
                    toggleRecordButton.isChecked = false
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun calculateEnergy(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample * sample
        }
        val meanSquare = sum / (bytesRead / 2)
        return sqrt(meanSquare.toDouble()).toFloat() / 32768.0f
    }

    private fun hasVoice(audioData: ByteArray): Boolean {
        var maxEnergy = 0f
        val chunkSize = 1024
        for (i in 0 until audioData.size step chunkSize) {
            val end = minOf(i + chunkSize, audioData.size)
            val chunk = audioData.copyOfRange(i, end)
            val energy = calculateEnergy(chunk, chunk.size)
            maxEnergy = maxOf(maxEnergy, energy)
            if (maxEnergy > MIN_ENERGY_THRESHOLD) return true
        }
        return maxEnergy > MIN_ENERGY_THRESHOLD
    }

    private fun writeWavFile(pcmData: ByteArray, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            val totalAudioLen = pcmData.size
            val totalDataLen = totalAudioLen + 36
            val channels = 1
            val sampleRate = SAMPLE_RATE
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8

            val header = ByteBuffer.allocate(44)
            header.order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * bitsPerSample / 8).toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(totalAudioLen)

            fos.write(header.array())
            fos.write(pcmData)
        }
    }

    private fun processAudioChunk(audioData: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chunkFile = File(cacheDir, "voice_chunk_${System.currentTimeMillis()}.wav")
            try {
                writeWavFile(audioData, chunkFile)
                sendAudioToSpeechApi(chunkFile)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Chunk processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendAudioToSpeechApi(audioFile: File) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val voiceDescription = "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality"

        val requestFile = audioFile.asRequestBody("audio/x-wav".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
        val voicePart = voiceDescription.toRequestBody("text/plain".toMediaType())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = speechToSpeechApi.speechToSpeech(
                    language = selectedLanguage,
                    file = filePart,
                    voice = voicePart,
                    token = "Bearer $token"
                )

                val audioBytes = response.bytes()
                if (audioBytes.isNotEmpty()) {
                    val responseAudioFile = File(cacheDir, "speech_output_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(responseAudioFile).use { fos -> fos.write(audioBytes) }

                    withContext(Dispatchers.Main) {
                        queueAndPlayAudio(responseAudioFile)
                        Toast.makeText(this@VoiceDetectionActivity, "Response queued", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VoiceDetectionActivity, "Empty audio response", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("VoiceDetectionActivity", "Speech-to-Speech failed: ${e.message}", e)
                    Toast.makeText(this@VoiceDetectionActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun queueAndPlayAudio(audioFile: File) {
        audioQueue.add(audioFile)
        if (!isPlaying) {
            playNextAudio()
        }
    }

    private fun playNextAudio() {
        if (audioQueue.isEmpty()) {
            isPlaying = false
            return
        }

        isPlaying = true
        val audioFile = audioQueue.poll()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile!!.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                audioFile.delete()
                playNextAudio() // Play the next in queue
            }
            setOnErrorListener { mp, what, extra ->
                Log.e("VoiceDetectionActivity", "MediaPlayer error: $what, $extra")
                Toast.makeText(this@VoiceDetectionActivity, "Playback error", Toast.LENGTH_SHORT).show()
                mp.release()
                mediaPlayer = null
                audioFile.delete()
                playNextAudio() // Continue with the next in queue
                true
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionDialog?.dismiss()
        sessionDialog = null
        mediaPlayer?.release()
        mediaPlayer = null
        audioRecord?.release()
        audioRecord = null
        audioFile?.delete()
        audioQueue.forEach { it.delete() }
        audioQueue.clear()
    }
}
package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.*
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

class DocsActivity : AppCompatActivity() {

    private val READ_STORAGE_PERMISSION_CODE = 101
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var currentTheme: Boolean? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private lateinit var sessionKey: ByteArray
    private val GCM_TAG_LENGTH = 16
    private val GCM_NONCE_LENGTH = 12

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)

        // Retrieve session key with validation
        sessionKey = prefs.getString("session_key", null)?.let { encodedKey ->
            try {
                val cleanKey = encodedKey.trim()
                if (!isValidBase64(cleanKey)) {
                    throw IllegalArgumentException("Invalid Base64 format for session key")
                }
                Base64.decode(cleanKey, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e("DocsActivity", "Invalid session key format: ${e.message}")
                null
            }
        } ?: run {
            Log.e("DocsActivity", "Session key missing")
            Toast.makeText(this, "Session error. Please log in again.", Toast.LENGTH_LONG).show()
            AuthManager.logout(this)
            ByteArray(0)
        }

        checkAuthentication()

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { _, _ -> }) // No audio playback in DocsActivity
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    READ_STORAGE_PERMISSION_CODE
                )
            }

            attachFab.setOnClickListener {
                pickFileLauncher.launch("*/*")
            }

            bottomNavigation.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_answer -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Answer?")
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
                    R.id.nav_voice -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Voice?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, VoiceDetectionActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_docs -> true
                    else -> false
                }
            }
            bottomNavigation.selectedItemId = R.id.nav_docs
        } catch (e: Exception) {
            Log.e("DocsActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@DocsActivity) || !AuthManager.refreshTokenIfNeeded(this@DocsActivity)) {
                AuthManager.logout(this@DocsActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
        }

        val dialog = AlertDialog.Builder(this)
            .setMessage("Checking session...")
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val tokenValid = AuthManager.refreshTokenIfNeeded(this@DocsActivity)
            if (tokenValid) {
                dialog.dismiss()
            } else {
                dialog.dismiss()
                AlertDialog.Builder(this@DocsActivity)
                    .setTitle("Session Expired")
                    .setMessage("Your session could not be refreshed. Please log in again.")
                    .setPositiveButton("OK") { _, _ -> AuthManager.logout(this@DocsActivity) }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true
        menu.findItem(R.id.action_target_language)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_auto_scroll -> {
                item.isChecked = !item.isChecked
                true
            }
            R.id.action_clear -> {
                messageList.clear()
                messageAdapter.notifyDataSetChanged()
                true
            }
            R.id.action_logout -> {
                AuthManager.logout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showMessageOptionsDialog(position: Int) {
        if (position < 0 || position >= messageList.size) return
        val message = messageList[position]
        val options = arrayOf("Delete", "Share", "Copy")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
                    }
                    1 -> shareMessage(message)
                    2 -> copyMessage(message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
    }

    private fun copyMessage(message: Message) {
        val copyText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = ClipData.newPlainText("Message", copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun encryptFile(file: ByteArray): ByteArray {
        return RetrofitClient.encryptAudio(file, sessionKey) // Reusing audio encryption for files
    }

    private fun handleFileUpload(uri: Uri) {
        val fileName = getFileName(uri)
        val inputStream = contentResolver.openInputStream(uri)
        var file = File(cacheDir, fileName)

        val isImage = fileName.lowercase().endsWith(".jpg") ||
                fileName.lowercase().endsWith(".jpeg") ||
                fileName.lowercase().endsWith(".png")

        if (isImage) {
            try {
                inputStream?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                file = compressImage(file)
            } catch (e: Exception) {
                Log.e("DocsActivity", "Image compression failed: ${e.message}", e)
                Toast.makeText(this, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                return
            } finally {
                inputStream?.close()
            }
        } else {
            inputStream?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Encrypt file
        val fileBytes = file.readBytes()
        val encryptedFileBytes = encryptFile(fileBytes)
        val encryptedFile = File(cacheDir, "encrypted_$fileName")
        FileOutputStream(encryptedFile).use { it.write(encryptedFileBytes) }

        val defaultQuery = "Describe the image"
        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = Message(defaultQuery, timestamp, true, uri)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        historyRecyclerView.requestLayout()
        scrollToLatestMessage()
        getVisualQueryResponse(defaultQuery, encryptedFile)
    }

    private fun compressImage(inputFile: File): File {
        val maxSize = 1_000_000
        val outputFile = File(cacheDir, "compressed_${inputFile.name}")

        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(inputFile.absolutePath, this)

                var sampleSize = 1
                if (outWidth > 1000 || outHeight > 1000) {
                    val scale = maxOf(outWidth, outHeight) / 1000.0
                    sampleSize = Math.pow(2.0, Math.ceil(Math.log(scale) / Math.log(2.0))).toInt()
                }
                inSampleSize = sampleSize
                inJustDecodeBounds = false
            }

            var bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, options)

            var quality = 90
            val baos = ByteArrayOutputStream()

            do {
                baos.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                quality -= 10
            } while (baos.size() > maxSize && quality > 10)

            FileOutputStream(outputFile).use { fos ->
                fos.write(baos.toByteArray())
            }

            bitmap.recycle()
            return outputFile

        } catch (e: Exception) {
            Log.e("DocsActivity", "Image compression failed: ${e.message}", e)
            throw e
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun scrollToLatestMessage() {
        val autoScrollEnabled = toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked ?: false
        if (autoScrollEnabled && messageList.isNotEmpty()) {
            historyRecyclerView.post {
                val itemCount = messageAdapter.itemCount
                if (itemCount > 0) {
                    historyRecyclerView.smoothScrollToPosition(itemCount - 1)
                }
            }
        }
    }

    private fun getVisualQueryResponse(query: String, file: File) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "malayalam" to "mal_Mlym",
            "telugu" to "tel_Telu",
            "german" to "deu_Latn",
            "french" to "fra_Latn",
            "dutch" to "nld_Latn",
            "spanish" to "spa_Latn",
            "italian" to "ita_Latn",
            "portuguese" to "por_Latn",
            "russian" to "rus_Cyrl",
            "polish" to "pol_Latn"
        )
        val srcLang = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = srcLang

        // Encrypt query and language fields
        val encryptedQuery = RetrofitClient.encryptText(query, sessionKey)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang, sessionKey)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang, sessionKey)
        Log.d("DocsActivity", "Encrypted fields - query: $encryptedQuery, src_lang: $encryptedSrcLang, tgt_lang: $encryptedTgtLang")

        // Create JSON body
        val visualQueryRequest = VisualQueryRequest(
            query = encryptedQuery,
            src_lang = encryptedSrcLang,
            tgt_lang = encryptedTgtLang
        )
        val jsonBody = Gson().toJson(visualQueryRequest)
        Log.d("DocsActivity", "Sending visual query JSON: $jsonBody")
        val dataBody = jsonBody.toRequestBody("application/json".toMediaType())

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val requestFile = file.asRequestBody("application/octet-stream".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                Log.d("DocsActivity", "File part - name: ${file.name}, size: ${file.length()}")
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                val response = RetrofitClient.apiService(this@DocsActivity).visualQuery(
                    filePart,
                    dataBody,
                    "Bearer $token",
                    cleanSessionKey
                )
                val answerText = response.answer
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Answer: $answerText", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()

                // Encrypt text for TTS
                val encryptedAnswerText = RetrofitClient.encryptText(answerText, sessionKey)
                SpeechUtils.textToSpeech(
                    context = this@DocsActivity,
                    scope = lifecycleScope,
                    text = encryptedAnswerText,
                    message = message,
                    recyclerView = historyRecyclerView,
                    adapter = messageAdapter,
                    ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE },
                    srcLang = tgtLang,
                    sessionKey = sessionKey
                )
            } catch (e: Exception) {
                Log.e("DocsActivity", "Visual query failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Query error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                file.delete()
            }
        }
    }

    private fun isValidBase64(str: String): Boolean {
        return str.matches(Regex("^[A-Za-z0-9+/=]+$"))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pickFileLauncher.launch("*/*")
        }
    }
}
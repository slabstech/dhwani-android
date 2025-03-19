package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import com.slabstech.dhwani.voiceai.utils.SpeechUtils

class DocsActivity : AppCompatActivity() {

    private val STORAGE_PERMISSION_CODE = 102
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastImageUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private val AUTO_PLAY_KEY = "auto_play_tts"
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val imageUri = result.data?.data
            imageUri?.let {
                lastImageUri = it
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Uploaded Image", timestamp, true, it)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                processImage(it, "describe image")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            if (!prefs.contains(AUTO_PLAY_KEY)) {
                prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
            }
            if (!prefs.contains("tts_enabled")) {
                prefs.edit().putBoolean("tts_enabled", false).apply()
            }

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { message, button ->
                if (message.isQuery && message.imageUri != null) {
                    showCustomQueryDialog(message.imageUri!!)
                } else if (!message.isQuery && message.audioFile != null) {
                    toggleAudioPlayback(message, button)
                }
            })
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            attachFab.setOnClickListener {
                openImagePicker()
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

    private fun openImagePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermission(permission)
            } else {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                pickImageLauncher.launch(intent)
            }
        }
    }

    private fun requestStoragePermission(permission: String) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            AlertDialog.Builder(this)
                .setTitle("Storage Permission Needed")
                .setMessage("This app needs storage access to upload images. Please grant the permission.")
                .setPositiveButton("OK") { _, _ ->
                    ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Denied")
                    .setMessage("Storage permission was permanently denied. Please enable it in Settings > Apps > Dhwani Voice AI > Permissions.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processImage(uri: Uri, query: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val imageFile = uriToFile(uri)
                if (imageFile == null || !imageFile.exists()) {
                    Toast.makeText(this@DocsActivity, "Failed to process image file", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    return@launch
                }

                getImageDescriptionAndTranslation(imageFile, query)
            } catch (e: Exception) {
                Log.e("DocsActivity", "Image processing error: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { outputStream ->
                inputStream?.use { it.copyTo(outputStream) }
            }
            tempFile
        } catch (e: Exception) {
            Log.e("DocsActivity", "Failed to convert Uri to File: ${e.message}", e)
            null
        }
    }

    private fun getImageDescriptionAndTranslation(imageFile: File, query: String) {
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "malayalam" to "mal_Mlym",
            "telugu" to "tel_Telu"
        )
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val tgtLang = languageMap[selectedLanguage] ?: "kan_Knda"

        val requestFile = imageFile.asRequestBody("image/jpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", imageFile.name, requestFile)
        val queryPart = query.toRequestBody("text/plain".toMediaType())

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService(this@DocsActivity).visualQuery(
                    file = filePart,
                    query = queryPart,
                    srcLang = "eng_Latn",
                    tgtLang = tgtLang
                )
                val description = response.answer
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Response ($selectedLanguage): $description", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                SpeechUtils.textToSpeech(
                    context = this@DocsActivity,
                    scope = lifecycleScope,
                    text = description,
                    message = message,
                    recyclerView = historyRecyclerView,
                    adapter = messageAdapter,
                    ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE }
                )

                if (messageList.size == 1) {
                    Toast.makeText(this@DocsActivity, "Tap the thumbnail to ask more!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "Processing failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Image query error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                imageFile.delete()
            }
        }
    }

    private fun toggleAudioPlayback(message: Message, button: ImageButton) {
        mediaPlayer = SpeechUtils.toggleAudioPlayback(
            context = this,
            message = message,
            button = button,
            recyclerView = historyRecyclerView,
            adapter = messageAdapter,
            mediaPlayer = mediaPlayer,
            playIconResId = android.R.drawable.ic_media_play,
            stopIconResId = R.drawable.ic_media_stop
        )
    }

    private fun showCustomQueryDialog(imageUri: Uri) {
        val editText = EditText(this)
        editText.hint = "Enter your question"
        AlertDialog.Builder(this)
            .setTitle("Ask About This Image")
            .setView(editText)
            .setPositiveButton("Ask") { _, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) {
                    processImage(imageUri, query)
                } else {
                    Toast.makeText(this, "Please enter a question", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true
        val historyItem = menu.add(Menu.NONE, 1001, Menu.NONE, "History")
        historyItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
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
                lastImageUri = null
                messageAdapter.notifyDataSetChanged()
                true
            }
            1001 -> {
                showHistoryDialog()
                true
            }
            R.id.action_share -> {
                if (messageList.isNotEmpty()) {
                    shareMessage(messageList.last())
                } else {
                    Toast.makeText(this, "No messages to share", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_logout -> {
                Toast.makeText(this, "Logout not applicable", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHistoryDialog() {
        Toast.makeText(this, "History not available without session persistence", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
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

    private fun showMessageOptionsDialog(position: Int) {
        if (position < 0 || position >= messageList.size) return

        val message = messageList[position]
        val options = arrayOf("Delete", "Share", "Copy") + if (message.isQuery && message.imageUri != null) arrayOf("Ask Another Question") else emptyArray()
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Delete" -> {
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
                    }
                    "Share" -> shareMessage(message)
                    "Copy" -> copyMessage(message)
                    "Ask Another Question" -> showCustomQueryDialog(message.imageUri!!)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", shareText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        messageList.forEach { it.audioFile?.delete() }
    }
}
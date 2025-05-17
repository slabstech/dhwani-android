package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter

class DocsActivity : MessageActivity() {

    private val READ_STORAGE_PERMISSION_CODE = 101
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DocsActivity", "onCreate called")
        setContentView(R.layout.activity_docs)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)
        attachFab = findViewById(R.id.attachFab)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_docs)

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
    }

    override fun onResume() {
        super.onResume()
        Log.d("DocsActivity", "onResume called")
    }

    private fun handleFileUpload(uri: Uri) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val fileName = getFileName(uri)
                val isImage = fileName.lowercase().endsWith(".jpg") ||
                        fileName.lowercase().endsWith(".jpeg") ||
                        fileName.lowercase().endsWith(".png")
                val isPdf = fileName.lowercase().endsWith(".pdf")

                withContext(Dispatchers.IO) {
                    val file = File(cacheDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Failed to open input stream")

                    if (isImage) {
                        val compressedFile = compressImage(file)
                        val fileBytes = compressedFile.readBytes()
                        val encryptedFileBytes = RetrofitClient.encryptAudio(fileBytes, sessionKey)
                        val encryptedFile = File(cacheDir, "encrypted_$fileName")
                        FileOutputStream(encryptedFile).use { it.write(encryptedFileBytes) }

                        withContext(Dispatchers.Main) {
                            val defaultQuery = "Describe the image"
                            val timestamp = DateUtils.getCurrentTimestamp()
                            val message = Message(defaultQuery, timestamp, true, uri)
                            messageList.add(message)
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            scrollToLatestMessage()
                            getVisualQueryResponse(defaultQuery, encryptedFile)
                        }
                    } else if (isPdf) {
                        // Get max page limit from preferences (default to 1)
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@DocsActivity)
                        val maxPages = prefs.getInt("pdf_max_pages", 3) // Options: 1 or 3
                        val processedFile = processPdfPages(file, maxPages)

                        withContext(Dispatchers.Main) {
                            // Show Toast with max pages
                            Toast.makeText(
                                this@DocsActivity,
                                "Summarizing up to $maxPages page(s)",
                                Toast.LENGTH_SHORT
                            ).show()

                            val defaultQuery = "Summarize the PDF"
                            val timestamp = DateUtils.getCurrentTimestamp()
                            val message = Message(defaultQuery, timestamp, true, uri)
                            messageList.add(message)
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            scrollToLatestMessage()
                            getPdfSummaryResponse(processedFile)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DocsActivity, "Unsupported file type. Please upload an image or PDF.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "File upload failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DocsActivity, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun processPdfPages(inputFile: File, maxPages: Int): File {
        try {
            val reader = PdfReader(inputFile)
            val srcDoc = PdfDocument(reader)
            val totalPages = srcDoc.numberOfPages
            val pagesToProcess = minOf(maxPages, totalPages)

            val outputFile = File(cacheDir, "processed_${inputFile.name}")
            val writer = PdfWriter(outputFile)
            val destDoc = PdfDocument(writer)

            for (i in 1..pagesToProcess) {
                destDoc.addPage(srcDoc.getPage(i).copyTo(destDoc))
            }

            destDoc.close()
            srcDoc.close()
            reader.close()

            return outputFile
        } catch (e: Exception) {
            Log.e("DocsActivity", "PDF page processing failed: ${e.message}", e)
            throw e
        }
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

        val encryptedQuery = RetrofitClient.encryptText(query, sessionKey)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang, sessionKey)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang, sessionKey)

        val visualQueryRequest = VisualQueryRequest(encryptedQuery, encryptedSrcLang, encryptedTgtLang)
        val jsonBody = Gson().toJson(visualQueryRequest)
        val dataBody = jsonBody.toRequestBody("application/json".toMediaType())

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@DocsActivity,
                progressBar = progressBar,
                apiCall = {
                    val requestFile = file.asRequestBody("application/octet-stream".toMediaType())
                    val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                    val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    RetrofitClient.apiService(this@DocsActivity).visualQuery(
                        filePart,
                        dataBody,
                        "Bearer $token",
                        cleanSessionKey
                    )
                },
                onSuccess = { response ->
                    val answerText = response.answer
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Answer: $answerText", timestamp, false)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()

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
                },
                onError = { e ->
                    Log.e("DocsActivity", "Visual query failed: ${e.message}", e)
                    Toast.makeText(this@DocsActivity, "Visual query failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun getPdfSummaryResponse(file: File) {
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
        val prompt = "Summarize the document in 3 sentences"

        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                val requestFile = file.asRequestBody("application/pdf".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val srcLangBody = srcLang.toRequestBody("text/plain".toMediaType())
                val tgtLangBody = tgtLang.toRequestBody("text/plain".toMediaType())
                val promptBody = prompt.toRequestBody("text/plain".toMediaType())

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.summaryApiService().summarizeDocument(
                        filePart,
                        srcLangBody,
                        tgtLangBody,
                        promptBody
                    )
                }

                val summaryText = response.summary
                val timestamp = DateUtils.getCurrentTimestamp()
                val message = Message("Summary: $summaryText", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                scrollToLatestMessage()

                // Encrypt the summary text for TTS
                val encryptedSummaryText = RetrofitClient.encryptText(summaryText, sessionKey)
                SpeechUtils.textToSpeech(
                    context = this@DocsActivity,
                    scope = lifecycleScope,
                    text = encryptedSummaryText,
                    message = message,
                    recyclerView = historyRecyclerView,
                    adapter = messageAdapter,
                    ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE },
                    srcLang = tgtLang,
                    sessionKey = sessionKey
                )
            } catch (e: Exception) {
                Log.e("DocsActivity", "PDF summarization failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "PDF summarization failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        // No audio playback in DocsActivity
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d("DocsActivity", "onDestroy called")
    }
}
package com.slabstech.dhwani.voiceai

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val refresh_token: String, val token_type: String)
data class TranscriptionRequest(val language: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryRequest(val query: String, val src_lang: String, val tgt_lang: String)
data class VisualQueryResponse(val answer: String)
data class ExtractTextResponse(val page_content: String)

interface ApiService {
    @POST("v1/token")
    suspend fun login(
        @Body loginRequest: LoginRequest,
        @Header("X-Session-Key") sessionKey: String
    ): TokenResponse

    @POST("v1/app/register")
    suspend fun appRegister(
        @Body registerRequest: RegisterRequest,
        @Header("X-Session-Key") sessionKey: String
    ): TokenResponse

    @Multipart
    @POST("v1/transcribe/")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part,
        @Query("language") language: String,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): TranscriptionResponse

    @POST("v1/chat")
    suspend fun chat(
        @Body chatRequest: ChatRequest,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): ChatResponse

    @POST("v1/audio/speech")
    suspend fun textToSpeech(
        @Query("input") input: String,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): ResponseBody

    @POST("v1/translate")
    suspend fun translate(
        @Body translationRequest: TranslationRequest,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): TranslationResponse

    @Multipart
    @POST("v1/visual_query")
    suspend fun visualQuery(
        @Part file: MultipartBody.Part,
        @Part("data") data: RequestBody,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): VisualQueryResponse

    @Multipart
    @POST("v1/speech_to_speech")
    suspend fun speechToSpeech(
        @Query("language") language: String,
        @Part file: MultipartBody.Part,
        @Part("voice") voice: RequestBody,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): Response<ResponseBody>

    @POST("v1/refresh")
    suspend fun refreshToken(@Header("Authorization") token: String): TokenResponse

    @Multipart
    @POST("v1/extract-text")
    suspend fun extractText(
        @Part file: MultipartBody.Part,
        @Query("page_number") pageNumber: Int,
        @Header("Authorization") token: String,
        @Header("X-Session-Key") sessionKey: String
    ): ExtractTextResponse
}

object RetrofitClient {
    private const val BASE_URL_DEFAULT = "https://example.com/"
    private const val GCM_TAG_LENGTH = 16
    private const val GCM_NONCE_LENGTH = 12
    private var lastAuthRefreshAttempt = 0L
    private const val AUTH_REFRESH_DEBOUNCE_MS = 5000L // 5 seconds

    fun encryptAudio(audio: ByteArray, sessionKey: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(audio)
        return nonce + ciphertext
    }

    fun encryptText(text: String, sessionKey: ByteArray): String {
        val nonce = ByteArray(GCM_NONCE_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val combined = nonce + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val authenticator = object : okhttp3.Authenticator {
            override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAuthRefreshAttempt < AUTH_REFRESH_DEBOUNCE_MS) {
                    Log.d("RetrofitClient", "Skipping authenticator refresh, too soon")
                    return null
                }
                lastAuthRefreshAttempt = currentTime

                val refreshToken = AuthManager.getRefreshToken(context) ?: return null
                try {
                    val refreshResponse = runBlocking {
                        apiService(context).refreshToken("Bearer $refreshToken")
                    }
                    val newToken = refreshResponse.access_token
                    val newExpiryTime = AuthManager.getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                    AuthManager.saveTokens(context, newToken, refreshResponse.refresh_token, newExpiryTime)
                    Log.d("RetrofitClient", "Authenticator refreshed token successfully")
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                } catch (e: Exception) {
                    Log.e("RetrofitClient", "Token refresh failed in authenticator: ${e.message}", e)
                    return null
                }
            }
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        }

        return OkHttpClient.Builder()
            .authenticator(authenticator)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun apiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("api_endpoint", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
package com.slabstech.dhwani.voiceai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface ApiService {
    @Multipart
    @POST("transcribe/")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part,
        @Query("language") language: String
    ): TranscriptionResponse

    @POST("v1/chat")
    suspend fun chat(
        @Body chatRequest: ChatRequest
    ): ChatResponse

    @POST("v1/audio/speech")
    suspend fun textToSpeech(
        @Body ttsRequest: TTSRequest
    ): ResponseBody

    @POST("v1/translate")
    suspend fun translate(
        @Body translationRequest: TranslationRequest
    ): TranslationResponse

    @Multipart
    @POST("v1/visual_query/")
    suspend fun visualQuery(
        @Part file: MultipartBody.Part,
        @Part("query") query: RequestBody,
        @Query("src_lang") srcLang: String,
        @Query("tgt_lang") tgtLang: String
    ): VisualQueryResponse
}

data class TranscriptionRequest(val language: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryResponse(val answer: String)
data class TTSRequest(
    val input: String,
    val voice: String,
    val model: String,
    val response_format: String,
    val speed: Double
)
package com.temi.temiTour

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit

//***************************************GPT STUFF DOWN HERE

data class ChatRequest(
    val model: String = "gpt-3.5-turbo",
    val messages: List<Message>
)
data class Message(
    val role: String,  // "user" for your messages, "assistant" for the response
    val content: String
)
data class ChatResponse(
    val choices: List<Choice>
)
data class Choice(
    val message: MessageContent
)
data class MessageContent(
    val role: String,
    val content: String
)

interface OpenAIApi {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    fun getChatResponse(@Body requestBody: ChatRequest): Call<ChatResponse>
}

object RetrofitClient {
    private const val BASE_URL = "https://api.openai.com/"

    fun getClient(): OpenAIApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)  // Set connection timeout
            .writeTimeout(30, TimeUnit.SECONDS)    // Set write timeout
            .readTimeout(30, TimeUnit.SECONDS)     // Set read timeout
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "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")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(OpenAIApi::class.java)
    }
}
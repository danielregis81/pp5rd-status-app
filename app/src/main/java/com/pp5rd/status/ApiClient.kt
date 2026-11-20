package com.pp5rd.status

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiClient(
    private val baseUrl: String,
    private val pistarUrl: String,
    private val useServiceToken: Boolean = false
) {

    private val client: OkHttpClient
    private val pistarClient: OkHttpClient

    init {
        // Cliente para o servidor de stats (com token se externo)
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        if (useServiceToken) {
            builder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("CF-Access-Client-Id", ServiceToken.CLIENT_ID)
                    .addHeader("CF-Access-Client-Secret", ServiceToken.CLIENT_SECRET)
                    .build()
                chain.proceed(request)
            }
        }

        client = builder.build()

        // Cliente para o Pi-Star (sem token, o túnel do Pi-Star é público)
        pistarClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // Baixa um arquivo do servidor (ex.: capa do livro) pela mesma URL e com o mesmo token do /stats
    suspend fun fetchBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/${path.trimStart('/')}").build()
            client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } catch (e: Exception) { null }
    }

    suspend fun fetchStats(): Result<ServerStats> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/stats")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    Result.success(Gson().fromJson(json, ServerStats::class.java))
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPiStar(): Result<PiStarData> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$pistarUrl/cyd/status.json")
                .build()
            val response = pistarClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    Result.success(Gson().fromJson(json, PiStarData::class.java))
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

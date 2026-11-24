package com.car.taiwanktv

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class OkHttpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(null)
            httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true) ->
                ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")

        for ((key, values) in headers) {
            builder.removeHeader(key)
            for (value in values) {
                builder.addHeader(key, value)
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val bodyString = response.body?.string() ?: ""
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                response.request.url.toString()
            )
        }
    }
}

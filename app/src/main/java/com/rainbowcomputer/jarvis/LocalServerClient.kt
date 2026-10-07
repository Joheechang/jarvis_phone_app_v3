package com.rainbowcomputer.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * PC 쪽 LocalCommandServer.gd와 통신하는 클라이언트. 그쪽 프로토콜을
 * 그대로 따른다:
 *   GET  /ping            -> {"ok": true, "app": "...", "assistant_name": "..."}
 *   POST /  {"token","text"} -> {"answer": "..."} 또는 새로 추가된 {"actions":[...]}
 *                              (또는 {"error": "..."})
 *
 * PC가 HTTPS를 켜뒀으면(Settings.local_voice_use_tls) 자체 서명 인증서를
 * 쓰기 때문에, 기본 신뢰 체계로는 연결이 거부된다. 이 앱은 "같은 집
 * Wi-Fi 안에서만 쓰는 개인용 도구"라는 전제로, HTTPS를 켠 경우에 한해
 * 인증서 검증을 생략하는 TrustManager를 쓴다 — 더 엄격하게 하려면 PC가
 * 내보낸 인증서 파일을 폰에 복사해 핀닝하는 방식으로 나중에 바꿀 수
 * 있다(README_설치방법.md의 "보안 참고" 항목 참고).
 */
class LocalServerClient(private val settings: SettingsStore) {

    data class JarvisResponse(
        val success: Boolean,
        val answer: String = "",
        val actions: JSONArray? = null,
        val error: String? = null,
    )

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection("/ping", "GET")
            val code = conn.responseCode
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            conn.disconnect()
            if (code in 200..299) {
                val obj = JSONObject(text)
                Result.success("연결 성공! (${obj.optString("app", "자비스")} · 비서 이름: ${obj.optString("assistant_name", "-")})")
            } else {
                Result.failure(Exception("서버가 오류를 돌려줬어요 (코드 $code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendCommand(text: String): JarvisResponse = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection("/", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().apply {
                put("token", settings.token)
                put("text", text)
            }.toString()
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
            conn.disconnect()

            val obj = JSONObject(respText)
            if (obj.has("error")) {
                return@withContext JarvisResponse(success = false, error = obj.optString("error"))
            }
            JarvisResponse(
                success = true,
                answer = obj.optString("answer", ""),
                actions = obj.optJSONArray("actions"),
            )
        } catch (e: Exception) {
            JarvisResponse(success = false, error = "PC에 연결하지 못했어요: ${e.message}")
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL(settings.baseUrl() + path)
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = trustAllSslContext().socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 25000 // PC가 AI 응답을 만드는 동안 조금 걸릴 수 있어 넉넉히 잡음
        return conn
    }

    private fun trustAllSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        }
    }
}

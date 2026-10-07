package com.rainbowcomputer.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Vosk 한국어 소형 모델로 "특정 한 단어(호출어)가 들렸는지"만 계속 감시하는
 * 역할. 완전히 오프라인으로 동작하고(모델은 최초 1회만 내려받음), 인식
 * 가능한 어휘를 호출어 하나로 제한하는 Vosk의 "grammar" 기능을 써서
 * 정확도를 높이고 배터리 소모도 줄였다.
 *
 * ⚠️ 실기기 테스트를 못 해본 코드다. Vosk 공식 문서/여러 실사용 예제를
 * 참고해 작성했지만(README_설치방법.md 참고), 오탐지(엉뚱한 말에 반응)나
 * 미탐지(불러도 반응 없음)가 있을 수 있다 — 호출어를 2음절 이상의 조금
 * 더 독특한 단어로 바꾸면 보통 나아진다.
 */
class WakeWordDetector(
    private val context: Context,
    private val wakeWord: String,
    private val onWakeWordHeard: () -> Unit,
) {
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var fired = false

    /** 성공하면 true. 모델이 아직 없거나 엔진 초기화에 실패하면 false. */
    fun start(): Boolean {
        val model = VoskModelManager.getOrLoadModel(context) ?: return false
        return try {
            fired = false
            val grammar = JSONArray().apply {
                put(wakeWord)
                put("[unk]")
            }.toString()
            val rec = Recognizer(model, 16000.0f, grammar)
            recognizer = rec
            val service = SpeechService(rec, 16000.0f)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = handleHypothesis(hypothesis, false)
                override fun onResult(hypothesis: String?) = handleHypothesis(hypothesis, true)
                override fun onFinalResult(hypothesis: String?) = handleHypothesis(hypothesis, true)
                override fun onError(exception: Exception?) {
                    Log.e(TAG, "호출어 인식 오류", exception)
                }
                override fun onTimeout() {
                    // 장시간 무음이어도 계속 대기해야 하므로 별도 처리 없음.
                }
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "호출어 인식 시작 실패", e)
            false
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "호출어 인식 정지 중 오류(무시 가능)", e)
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        speechService = null
        recognizer = null
    }

    private fun handleHypothesis(json: String?, isFinal: Boolean) {
        if (fired || json.isNullOrBlank()) return
        val text = try {
            val obj = JSONObject(json)
            if (obj.has("partial")) obj.optString("partial") else obj.optString("text")
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) return
        val normalized = text.replace(" ", "").replace("\n", "")
        val target = wakeWord.replace(" ", "")
        if (target.isNotBlank() && normalized.contains(target)) {
            fired = true
            mainHandler.post { onWakeWordHeard() }
        }
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}

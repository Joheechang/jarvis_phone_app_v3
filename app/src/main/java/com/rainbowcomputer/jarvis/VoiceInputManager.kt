package com.rainbowcomputer.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google 음성인식(SpeechRecognizer)을 감싼 래퍼. 안드로이드 기본 제공
 * 기능이라 별도 API 키나 비용이 들지 않는다(기기에 Google 앱이 있는
 * 일반적인 경우).
 *
 * RecognizerIntent.ACTION_RECOGNIZE_SPEECH로 띄우는 "구글 다이얼로그"
 * 대신, SpeechRecognizer를 직접 만들어 RecognitionListener로 결과만
 * 받는 방식을 쓴다 — 이렇게 해야 MainActivity뿐 아니라
 * JarvisListenService(포그라운드 서비스, 화면 UI 없음)에서도 똑같이
 * 쓸 수 있다. SpeechRecognizer는 반드시 메인 스레드에서 만들고 호출해야
 * 해서, 내부적으로 Handler(Looper.getMainLooper())로 보정한다.
 */
class VoiceInputManager(private val context: Context) {

    /** 성공하면 인식된 텍스트, 실패/무음/권한 없음 등이면 null. */
    suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                if (cont.isActive) cont.resume(null)
                return@post
            }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            var finished = false

            fun finishWith(result: String?) {
                if (finished) return
                finished = true
                mainHandler.post {
                    recognizer.stopListening()
                    recognizer.destroy()
                }
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    finishWith(null)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finishWith(matches?.firstOrNull())
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                mainHandler.post {
                    recognizer.stopListening()
                    recognizer.destroy()
                }
            }
        }
    }
}

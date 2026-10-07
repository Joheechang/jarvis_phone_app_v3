package com.rainbowcomputer.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "자비스야" 같은 호출어를 상시 대기하다가, 들리면 그 즉시 다음 음성
 * 명령을 듣고 실행하는 포그라운드 서비스. 호출어 감지 자체는
 * [WakeWordDetector](Vosk, 완전 오프라인)가 맡고, 실제 명령 인식은 기존에
 * 쓰던 안드로이드 기본 음성인식([VoiceInputManager])을 그대로 재사용한다
 * (호출어 전용 소형 모델보다 일반 명령 인식에는 그게 더 정확하기 때문).
 *
 * 마이크는 한 번에 한 쪽만 쓸 수 있어서, 호출어 대기 → 명령 듣기 → 실행 →
 * 다시 호출어 대기, 이렇게 교대로 전환한다. 교체할 때마다 Vosk 쪽
 * Recognizer는 새로 만들지만, 무거운 모델 자체([VoskModelManager]가
 * 메모리에 캐싱)는 한 번만 올려두고 계속 재사용한다.
 *
 * ⚠️ 실기기에서 검증하지 못한 기능이다 — 특히 "호출어 대기 → 명령 듣기로
 * 전환"하는 타이밍이 기기마다 다르게 느껴질 수 있다. 잘 안 되면 알려주면
 * 조정해드릴 수 있다.
 */
class WakeWordListenService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var detector: WakeWordDetector? = null

    @Volatile
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("준비하는 중..."))
        if (!busy) beginListening()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.stop()
        detector = null
        scope.cancel()
    }

    private fun beginListening() {
        if (!VoskModelManager.isModelReady(applicationContext)) {
            updateNotification("음성 모델이 없어요. 앱을 열어 먼저 다운로드해주세요.")
            stopSelf()
            return
        }
        val wakeWord = SettingsStore(applicationContext).effectiveWakeWord()
        scope.launch(Dispatchers.Default) {
            val newDetector = WakeWordDetector(applicationContext, wakeWord) { onWakeWordDetected() }
            val ok = newDetector.start()
            detector = newDetector
            scope.launch {
                updateNotification(if (ok) "\"$wakeWord\"라고 불러보세요" else "음성 인식 엔진을 시작하지 못했어요.")
            }
        }
    }

    private fun onWakeWordDetected() {
        if (busy) return
        busy = true
        detector?.stop()
        detector = null
        scope.launch {
            updateNotification("네, 말씀하세요...")
            val settings = SettingsStore(applicationContext)
            if (!settings.isConfigured()) {
                updateNotification("먼저 앱을 열어 PC 연결(IP·토큰)을 설정해주세요.")
            } else {
                val text = VoiceInputManager(applicationContext).listenOnce()
                if (text.isNullOrBlank()) {
                    updateNotification("명령을 못 들었어요. 다시 \"${settings.effectiveWakeWord()}\"라고 불러주세요.")
                } else {
                    updateNotification("\"$text\" → PC에 물어보는 중...")
                    val response = LocalServerClient(settings).sendCommand(text)
                    if (!response.success) {
                        updateNotification("실패: ${response.error}")
                    } else {
                        if (response.actions != null && response.actions.length() > 0) {
                            ActionPlanExecutor.execute(applicationContext, response.actions)
                        }
                        updateNotification(response.answer.ifBlank { "완료했어요." }.take(120))
                    }
                }
            }
            busy = false
            beginListening()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "자비스 호출어 대기",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🗣️ 호출어 대기 중")
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wakeword_channel"
        private const val NOTIFICATION_ID = 1002
    }
}

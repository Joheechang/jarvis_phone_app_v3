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
 * "항상 대기" 알림을 띄워두는 포그라운드 서비스. 진짜 "헤이 빅스비" 같은
 * 상시 음성 인식(wake-word)은 넣지 않았다 — 24시간 마이크를 열어두면
 * 배터리 소모가 크고, 사생활 민감도도 높고, 안드로이드 최신 버전들은
 * 백그라운드 상시 녹음을 점점 더 엄격히 막는 추세라(배터리 최적화,
 * 권한 자동 회수 등) 오히려 "켜놨는데 어느 순간 조용히 안 듣고 있더라"는
 * 더 나쁜 경험이 될 수 있다. 대신 이 서비스는 알림 하나를 계속 띄워두고,
 * 그 알림의 "🎙️ 말하기" 버튼을 누르는 즉시(앱을 열 필요 없이) 한 번
 * 듣고 실행하는 방식이다 — 손은 더 가지만, "꺼져 있는 줄 알았는데 켜져
 * 있었다/그 반대"가 없는 확실한 동작을 택했다.
 */
class JarvisListenService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text)))
        if (intent?.action == ACTION_SPEAK_NOW) {
            runListenAndExecute()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun runListenAndExecute() {
        scope.launch {
            updateNotification("듣고 있어요...")
            val settings = SettingsStore(applicationContext)
            if (!settings.isConfigured()) {
                updateNotification("먼저 앱을 열어 PC 연결(IP·토큰)을 설정해주세요.")
                return@launch
            }
            val text = VoiceInputManager(applicationContext).listenOnce()
            if (text.isNullOrBlank()) {
                updateNotification("음성을 못 알아들었어요. 다시 눌러 말씀해주세요.")
                return@launch
            }
            updateNotification("\"$text\" → PC에 물어보는 중...")
            val response = LocalServerClient(settings).sendCommand(text)
            if (!response.success) {
                updateNotification("실패: ${response.error}")
                return@launch
            }
            if (response.actions != null && response.actions.length() > 0) {
                ActionPlanExecutor.execute(applicationContext, response.actions)
            }
            updateNotification(response.answer.ifBlank { "완료했어요." }.take(120))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val speakIntent = Intent(this, JarvisListenService::class.java).setAction(ACTION_SPEAK_NOW)
        val speakPendingIntent = PendingIntent.getService(
            this, 0, speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, getString(R.string.action_speak), speakPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_listen_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SPEAK_NOW = "com.rainbowcomputer.jarvis.action.SPEAK_NOW"
    }
}

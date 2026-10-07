package com.rainbowcomputer.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rainbowcomputer.jarvis.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * 폰 앱의 화면 하나짜리 UI. PC 연결 설정, 접근성 권한 안내, "항상 대기"
 * 알림 켜고 끄기, 그리고 "눌러서 말하기" 버튼으로 실제 명령을 보낸다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private val logLines = mutableListOf<String>()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 결과는 각 버튼을 다시 누를 때 자연히 재확인됨 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        loadSettingsIntoUi()
        ensureRuntimePermissions()

        binding.btnSaveAndPing.setOnClickListener { saveSettingsAndPing() }
        binding.btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.switchAlwaysOnNotification.isChecked = settings.alwaysOnNotification
        binding.switchAlwaysOnNotification.setOnCheckedChangeListener { _, isChecked ->
            settings.alwaysOnNotification = isChecked
            if (isChecked) {
                ContextCompat.startForegroundService(this, Intent(this, JarvisListenService::class.java))
            } else {
                stopService(Intent(this, JarvisListenService::class.java))
            }
        }
        binding.btnSpeak.setOnClickListener { speakAndExecute() }

        binding.btnSaveWakeWord.setOnClickListener { saveWakeWord() }
        binding.btnDownloadModel.setOnClickListener { downloadModel() }
        binding.switchWakeWordEnabled.setOnCheckedChangeListener { _, isChecked ->
            onWakeWordSwitchChanged(isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.textAccessibilityStatus.text =
            if (JarvisAccessibilityService.isEnabled()) "✅ 접근성 권한이 켜져 있어요 — 앱 조작 기능을 쓸 수 있어요."
            else "⚪ 아직 꺼져 있어요 — 네비게이션/앱 실행 정도는 이 권한 없이도 되지만, 앱 내부 조작(메시지 전송 등)엔 필요해요."
        refreshModelStatus()
    }

    private fun loadSettingsIntoUi() {
        binding.editHost.setText(settings.host)
        binding.editPort.setText(settings.port.toString())
        binding.editToken.setText(settings.token)
        binding.checkHttps.isChecked = settings.useHttps
        binding.editWakeWord.setText(settings.effectiveWakeWord())
        binding.switchWakeWordEnabled.isChecked = settings.wakeWordEnabled
    }

    private fun saveWakeWord() {
        val word = binding.editWakeWord.text.toString().trim()
        if (word.isBlank()) {
            Toast.makeText(this, "호출어를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        settings.wakeWordText = word
        Toast.makeText(this, "\"$word\"(으)로 저장했어요.", Toast.LENGTH_SHORT).show()
        // 이미 호출어 대기가 켜져 있었다면, 바뀐 호출어로 다시 시작해준다.
        if (settings.wakeWordEnabled) {
            stopService(Intent(this, WakeWordListenService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, WakeWordListenService::class.java))
        }
    }

    private fun refreshModelStatus() {
        val ready = VoskModelManager.isModelReady(this)
        binding.textModelStatus.text = if (ready) {
            "✅ 모델이 준비됐어요. 호출어 대기를 켤 수 있어요."
        } else {
            "⚪ 아직 모델을 내려받지 않았어요. \"호출어 상시 대기\"를 쓰려면 먼저 다운로드해주세요."
        }
    }

    private fun downloadModel() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureRuntimePermissions()
        }
        binding.btnDownloadModel.isEnabled = false
        binding.progressModelDownload.visibility = android.view.View.VISIBLE
        binding.progressModelDownload.progress = 0
        binding.textModelStatus.text = "다운로드 중... 0%"
        lifecycleScope.launch {
            val result = VoskModelManager.downloadAndUnpack(this@MainActivity) { percent ->
                runOnUiThread {
                    binding.progressModelDownload.progress = percent
                    binding.textModelStatus.text = "다운로드 중... $percent%"
                }
            }
            binding.progressModelDownload.visibility = android.view.View.GONE
            binding.btnDownloadModel.isEnabled = true
            result.fold(
                onSuccess = {
                    binding.textModelStatus.text = "✅ 모델 준비 완료! 이제 \"호출어 상시 대기\"를 켤 수 있어요."
                },
                onFailure = {
                    binding.textModelStatus.text = "❌ 다운로드 실패: ${it.message}\n(인터넷 연결을 확인하고 다시 시도해주세요.)"
                }
            )
        }
    }

    private fun onWakeWordSwitchChanged(isChecked: Boolean) {
        if (isChecked && !VoskModelManager.isModelReady(this)) {
            Toast.makeText(this, "먼저 음성 인식 모델을 다운로드해주세요.", Toast.LENGTH_SHORT).show()
            binding.switchWakeWordEnabled.isChecked = false
            return
        }
        if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "마이크 권한이 필요해요.", Toast.LENGTH_SHORT).show()
            ensureRuntimePermissions()
            binding.switchWakeWordEnabled.isChecked = false
            return
        }
        settings.wakeWordEnabled = isChecked
        if (isChecked) {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordListenService::class.java))
        } else {
            stopService(Intent(this, WakeWordListenService::class.java))
        }
    }

    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun saveSettingsAndPing() {
        settings.host = binding.editHost.text.toString().trim()
        settings.port = binding.editPort.text.toString().toIntOrNull() ?: settings.port
        settings.token = binding.editToken.text.toString().trim()
        settings.useHttps = binding.checkHttps.isChecked

        if (!settings.isConfigured()) {
            binding.textPingResult.text = "PC IP 주소와 토큰을 먼저 입력해주세요."
            return
        }
        binding.textPingResult.text = "연결 확인 중..."
        lifecycleScope.launch {
            val result = LocalServerClient(settings).ping()
            binding.textPingResult.text = result.fold(
                onSuccess = { it },
                onFailure = { "연결 실패: ${it.message}\n(같은 Wi-Fi인지, PC에서 \"폰 연동 서버\"를 켰는지 확인해주세요.)" }
            )
        }
    }

    private fun speakAndExecute() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureRuntimePermissions()
            return
        }
        if (!settings.isConfigured()) {
            Toast.makeText(this, "먼저 PC 연결 설정을 저장해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog("🎙️ 듣는 중...")
        lifecycleScope.launch {
            val text = VoiceInputManager(this@MainActivity).listenOnce()
            if (text.isNullOrBlank()) {
                appendLog("❌ 음성을 못 알아들었어요.")
                return@launch
            }
            appendLog("나: $text")
            val response = LocalServerClient(settings).sendCommand(text)
            if (!response.success) {
                appendLog("❌ ${response.error}")
                return@launch
            }
            appendLog("자비스: ${response.answer}")
            if (response.actions != null && response.actions.length() > 0) {
                val results = ActionPlanExecutor.execute(this@MainActivity, response.actions)
                results.forEach { appendLog(it) }
            }
        }
    }

    private fun appendLog(line: String) {
        logLines.add(0, line)
        while (logLines.size > 30) logLines.removeAt(logLines.size - 1)
        binding.textLog.text = logLines.joinToString("\n")
    }
}

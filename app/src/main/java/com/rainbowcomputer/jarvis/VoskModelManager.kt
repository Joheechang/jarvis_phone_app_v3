package com.rainbowcomputer.jarvis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 호출어(wake-word) 인식에 쓰는 Vosk 한국어 소형 모델을 내려받고/풀고/메모리에
 * 올려두는 역할. 모델 파일(약 80MB대)은 이 프로젝트 안에 들어있지 않다 —
 * 샌드박스 환경이 구글/깃허브 등 외부 네트워크가 막혀있어 미리 받아둘 수
 * 없었기 때문에, 대신 "폰에서 처음 켤 때 공식 서버에서 직접 받는" 방식을
 * 택했다(폰은 실제 인터넷이 되니 문제 없음).
 *
 * 모델 출처: https://alphacephei.com/vosk/models (Apache-2.0,
 * vosk-model-small-ko-0.22, 공식 문서 기준 약 82MB).
 */
object VoskModelManager {

    private const val TAG = "VoskModelManager"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"
    private const val MODEL_DIR_NAME = "vosk-model-ko"
    private const val READY_MARKER = ".ready"

    @Volatile
    private var cachedModel: Model? = null

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(context: Context): Boolean =
        File(modelDir(context), READY_MARKER).exists()

    /**
     * 이미 로드해둔 Model이 있으면 그대로 재사용하고(메모리에 한 번만 올려서
     * 모델을 반복해서 다시 읽지 않도록), 없으면 디스크에서 새로 올린다.
     * 반드시 메인 스레드가 아닌 곳에서 호출할 것(디스크 I/O + 파싱으로
     * 수백 ms~수 초 걸릴 수 있음).
     */
    @Synchronized
    fun getOrLoadModel(context: Context): Model? {
        cachedModel?.let { return it }
        if (!isModelReady(context)) return null
        return try {
            val model = Model(modelDir(context).absolutePath)
            cachedModel = model
            model
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패", e)
            null
        }
    }

    /**
     * 모델 zip을 내려받아 [modelDir]에 풀어놓는다. Wi-Fi 환경을 권장하지만
     * (약 80MB) 여기서 네트워크 종류를 강제로 확인하지는 않는다 — 호출하는
     * 쪽(UI)에서 먼저 사용자에게 안내하는 게 더 자연스럽기 때문.
     *
     * onProgress: 0~100 사이 대략적인 다운로드 진행률(압축 해제 구간은
     * 100으로 고정 표시).
     */
    suspend fun downloadAndUnpack(
        context: Context,
        onProgress: (Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetDir = modelDir(context)
        val tmpZip = File(context.cacheDir, "vosk-model-download.zip")
        try {
            onProgress(0)
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
            }
            val total = conn.contentLength
            conn.inputStream.use { input ->
                FileOutputStream(tmpZip).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
            conn.disconnect()

            // 기존에 실패하다 남은 조각이 있으면 깨끗이 지우고 새로 푼다.
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            ZipInputStream(tmpZip.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // zip 안의 최상위 폴더명(vosk-model-small-ko-0.22/...)을
                    // 그대로 쓰지 않고 한 단계 벗겨내서 targetDir 바로 아래에
                    // 풀어놓는다 — 버전이 올라가도 경로를 안 바꿔도 되게.
                    val name = entry.name
                    val stripped = name.substringAfter('/', missingDelimiterValue = "")
                    if (stripped.isNotBlank()) {
                        val outFile = File(targetDir, stripped)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            tmpZip.delete()

            File(targetDir, READY_MARKER).writeText("ok")
            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "모델 다운로드/압축해제 실패", e)
            tmpZip.delete()
            Result.failure(e)
        }
    }
}

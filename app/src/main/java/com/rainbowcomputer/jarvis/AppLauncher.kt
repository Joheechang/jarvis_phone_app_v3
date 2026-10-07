package com.rainbowcomputer.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * "카카오톡 열어줘"/"유튜브 켜줘"처럼 이름만 듣고 설치된 앱을 실행한다.
 * 자주 쓰는 앱은 패키지명을 바로 알고 있으면 더 정확하고 빠르므로 표로
 * 미리 적어두고, 표에 없는 앱은 QUERY_ALL_PACKAGES 권한으로 설치된 전체
 * 앱 목록을 뒤져 이름이 비슷한 걸 찾는다(느슨한 부분일치).
 */
object AppLauncher {

    // 2026-10 — 가게에서 손님 응대/업무에 자주 쓰일 법한 앱 위주로 우선
    // 등록. 표에 없어도 아래 findByLabel()이 설치된 앱 이름과 느슨하게
    // 비교해서 찾아주므로, 목록이 전부일 필요는 없다.
    private val KNOWN_PACKAGES = mapOf(
        "카카오톡" to "com.kakao.talk",
        "카톡" to "com.kakao.talk",
        "카카오맵" to "net.daum.android.map",
        "네이버지도" to "com.nhn.android.nmap",
        "카카오내비" to "com.locnall.KimGiSa",
        "티맵" to "com.skt.tmap.ku",
        "유튜브" to "com.google.android.youtube",
        "네이버" to "com.nhn.android.search",
        "문자" to "com.android.mms",
        "전화" to "com.android.dialer",
        "카메라" to "com.android.camera",
        "캘린더" to "com.android.calendar",
        "메모" to "com.google.android.keep",
        "지메일" to "com.google.android.gm",
    )

    /** 패키지명을 찾았으면 실행까지 시도하고 결과를 알려준다. */
    fun openApp(context: Context, spokenName: String): OpenResult {
        val normalized = spokenName.trim()
        val packageName = KNOWN_PACKAGES[normalized] ?: findByLabel(context, normalized)
            ?: return OpenResult(false, "\"$normalized\"라는 이름의 앱을 이 폰에서 찾지 못했어요.")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return OpenResult(false, "\"$normalized\" 앱은 찾았지만 실행 가능한 화면이 없어요($packageName).")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return OpenResult(true, "\"$normalized\" 앱을 실행했어요.")
    }

    /**
     * 설치된 앱들의 "화면에 보이는 이름"(라벨)을 뒤져서 느슨하게 일치하는
     * 걸 찾는다. 공백/대소문자를 무시하고 부분 포함 여부로 비교한다 —
     * 음성 인식 결과는 철자가 완전히 정확하지 않을 수 있어서다.
     */
    private fun findByLabel(context: Context, spokenName: String): String? {
        val pm = context.packageManager
        val target = normalize(spokenName)
        if (target.isEmpty()) return null

        // PackageManager.ApplicationInfoFlags(API 33+ 전용)를 쓰면 minSdk 26
        // 기기에서 클래스를 못 찾아 바로 죽는다 — 구버전 오버로드는
        // "deprecated"일 뿐 모든 안드로이드 버전에서 계속 동작하므로 그대로
        // 쓴다(@Suppress로 경고만 숨김).
        @Suppress("DEPRECATION")
        val apps: List<ApplicationInfo> = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList()
        }

        var bestMatch: String? = null
        for (app in apps) {
            // 런처에 안 보이는 시스템/백그라운드 전용 구성요소는 제외한다.
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue
            val label = normalize(pm.getApplicationLabel(app).toString())
            if (label == target) {
                return app.packageName // 완전 일치면 바로 확정
            }
            if (bestMatch == null && (label.contains(target) || target.contains(label))) {
                bestMatch = app.packageName
            }
        }
        return bestMatch
    }

    private fun normalize(s: String): String = s.replace(" ", "").lowercase()

    data class OpenResult(val success: Boolean, val message: String)
}

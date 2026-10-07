package com.rainbowcomputer.jarvis

import android.content.Context
import org.json.JSONArray

/**
 * PC(LocalCommandServer.gd)의 응답에 담긴 "actions" 배열을 실제 폰
 * 동작으로 옮긴다. PC가 보내는 action 종류는 3가지:
 *   - {"type":"navigate","query":"스타벅스 둔산점"}       → NavigationLauncher
 *   - {"type":"open_app","app_name":"카카오톡"}            → AppLauncher
 *   - {"type":"ui_automation","steps":[...]}               → JarvisAccessibilityService
 * 모르는 type은 조용히 건너뛴다(PC 쪽이 먼저 업데이트되고 폰 앱이 아직
 * 안 올라간 경우에도 앱이 죽지 않게 하기 위함).
 */
object ActionPlanExecutor {

    suspend fun execute(context: Context, actions: JSONArray?): List<String> {
        if (actions == null || actions.length() == 0) return emptyList()
        val logs = mutableListOf<String>()
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            when (action.optString("type", "")) {
                "navigate" -> {
                    val query = action.optString("query", "")
                    val ok = if (query.isNotBlank()) NavigationLauncher.navigateTo(context, query) else false
                    logs.add(if (ok) "🧭 \"$query\" 길안내를 실행했어요." else "🧭 \"$query\" 길안내 실행에 실패했어요(지도 앱을 찾지 못함).")
                }
                "open_app" -> {
                    val appName = action.optString("app_name", "")
                    val result = AppLauncher.openApp(context, appName)
                    logs.add((if (result.success) "📱 " else "⚠️ ") + result.message)
                }
                "ui_automation" -> {
                    val steps = action.optJSONArray("steps")
                    if (steps == null || steps.length() == 0) {
                        logs.add("⚠️ 실행할 조작 단계가 비어 있었어요.")
                    } else {
                        val result = JarvisAccessibilityService.executeSteps(steps)
                        logs.add((if (result.success) "✅ " else "⚠️ ") + result.message)
                    }
                }
                else -> logs.add("⚠️ 아직 지원하지 않는 동작이에요: ${action.optString("type")}")
            }
        }
        return logs
    }
}

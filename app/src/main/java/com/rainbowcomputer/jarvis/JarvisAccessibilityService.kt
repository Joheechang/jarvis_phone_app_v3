package com.rainbowcomputer.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Bixby처럼 다른 앱도 직접 조작" 기능의 핵심. 안드로이드 접근성
 * (Accessibility) API로 현재 화면의 글자를 읽고, 버튼을 대신 누르거나
 * 글자를 입력한다 — 시각장애인 보조 도구들이 쓰는 것과 같은 공식 API라
 * "해킹"은 아니지만, 어떤 앱 화면이든 들여다보고 조작할 수 있는 매우
 * 강력한 권한이라 안드로이드가 설정 > 접근성에서 사람이 직접 켜도록
 * 강제한다(앱이 스스로 켤 수 없음 — MainActivity의 "접근성 설정 열기"
 * 버튼은 그 설정 화면으로 안내만 해준다).
 *
 * 동작 원리: PC(Main.gd)가 AI로 "이 요청을 처리하려면 어떤 화면 조작이
 * 필요한지"를 미리 단계별 JSON으로 만들어 보내준다(예: "카카오톡 열기 →
 * '홍길동' 글자 누르기 → 입력창에 글자 넣기 → '전송' 누르기"). 이
 * 서비스는 그 단계를 순서대로 실행하되, 각 단계마다 "그 글자/버튼이
 * 화면에 실제로 나타날 때까지" 잠깐 기다렸다가 실행한다 — 앱마다 화면
 * 전환 속도가 다르기 때문.
 *
 * ⚠️ 한계: 이 실행기는 "화면에 보이는 글자"로 버튼을 찾는 방식이라,
 * 앱 업데이트로 문구가 바뀌거나 아이콘만 있고 글자가 없는 버튼이면
 * 못 찾을 수 있다. 개발 환경에 실제 기기가 없어 실제 앱 화면으로
 * 직접 검증하지 못했으므로, 실패하면 무리하게 다음 단계로 넘어가지
 * 않고 그 자리에서 멈추고 "N번째 단계(무엇을 찾다가) 실패"를 그대로
 * 알려주도록 만들었다 — README_설치방법.md의 "앱 조작이 자꾸 실패해요"
 * 항목 참고.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 특정 이벤트에 반응할 필요는 없다 — executeSteps()가 필요할 때마다
        // rootInActiveWindow를 직접 다시 읽어오는 polling 방식이라서다.
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        data class StepResult(val success: Boolean, val message: String)

        /**
         * PC가 내려준 action plan 중 "ui_automation" 타입 하나(steps 배열)를
         * 처음부터 순서대로 실행한다. 중간에 하나라도 실패하면 즉시 멈추고
         * 그 결과를 돌려준다(뒷 단계를 억지로 진행하지 않음 — 예를 들어
         * 메시지 입력창을 못 찾았는데 "전송" 버튼을 찾아 눌러버리면 엉뚱한
         * 내용이 전송될 위험이 있기 때문).
         */
        suspend fun executeSteps(steps: JSONArray): StepResult {
            val svc = instance ?: return StepResult(
                false,
                "접근성 권한이 꺼져 있어서 화면을 조작할 수 없어요. 폰 앱에서 \"접근성 설정 열기\"로 자비스를 먼저 켜주세요."
            )
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val action = step.optString("action", "")
                val result = svc.runStep(action, step)
                if (!result.success) {
                    return StepResult(false, "${i + 1}번째 단계(${action})에서 멈췄어요: ${result.message}")
                }
                delay(STEP_DELAY_MS) // 화면 전환 애니메이션이 끝날 시간을 조금 줌
            }
            return StepResult(true, "요청한 화면 조작을 전부 마쳤어요.")
        }

        private const val STEP_DELAY_MS = 350L
        private const val DEFAULT_WAIT_MS = 4000L
    }

    private suspend fun runStep(action: String, step: JSONObject): StepResult = when (action) {
        "open_app" -> {
            val appName = step.optString("app_name", "")
            val r = AppLauncher.openApp(applicationContext, appName)
            StepResult(r.success, r.message)
        }
        "click_text" -> clickByText(step.optString("text", ""), step.optLong("timeout_ms", DEFAULT_WAIT_MS))
        "input_text" -> inputText(step.optString("text", ""), step.optString("target_hint", ""), step.optLong("timeout_ms", DEFAULT_WAIT_MS))
        "wait_for_text" -> waitForNode(step.optString("text", ""), step.optLong("timeout_ms", DEFAULT_WAIT_MS))
            ?.let { StepResult(true, "찾음") } ?: StepResult(false, "\"${step.optString("text")}\" 글자를 화면에서 못 찾았어요.")
        "scroll_forward" -> scroll(forward = true)
        "scroll_backward" -> scroll(forward = false)
        "press_back" -> { performGlobalAction(GLOBAL_ACTION_BACK); StepResult(true, "뒤로가기") }
        "press_home" -> { performGlobalAction(GLOBAL_ACTION_HOME); StepResult(true, "홈으로") }
        "wait_ms" -> { delay(step.optLong("duration_ms", 500L)); StepResult(true, "대기") }
        else -> StepResult(false, "알 수 없는 동작 종류예요: $action")
    }

    /** 화면에서 text와 일치/포함하는 노드가 나타날 때까지 기다렸다가 돌려준다. */
    private suspend fun waitForNode(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val found = findNodeByText(root, text)
                if (found != null) return found
            }
            delay(200)
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val target = text.trim()
        if (target.isEmpty()) return null
        // findAccessibilityNodeInfosByText는 "부분 포함"으로 검색해준다(공식 API 동작).
        val matches = root.findAccessibilityNodeInfosByText(target)
        if (matches.isNotEmpty()) return matches[0]
        // 글자 대신 content-description(아이콘 설명)만 있는 버튼 대비 — 트리를 직접 훑는다.
        return searchByContentDescription(root, target)
    }

    private fun searchByContentDescription(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.contains(target, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchByContentDescription(child, target)
            if (found != null) return found
        }
        return null
    }

    /** 노드 자신이나 가까운 조상 중 클릭 가능한 걸 찾아 누른다. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        // 클릭 가능한 조상이 없으면, 좌표를 찍어 제스처로 탭한다(최후 수단).
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun clickByText(text: String, timeoutMs: Long): StepResult {
        val node = waitForNode(text, timeoutMs)
            ?: return StepResult(false, "\"$text\"라는 글자/버튼을 화면에서 못 찾았어요.")
        val ok = clickNode(node)
        return StepResult(ok, if (ok) "\"$text\" 눌렀어요." else "\"$text\"를 찾았지만 누르지 못했어요.")
    }

    /**
     * target_hint(예: 입력창 근처 안내 문구)로 후보를 좁혀보고, 못 찾으면
     * 현재 포커스된 입력란(focused editable)에 그냥 입력한다 — 메시지
     * 앱은 보통 화면에 입력창이 하나뿐이라 포커스 기준으로도 대부분
     * 맞는다.
     */
    private suspend fun inputText(text: String, targetHint: String, timeoutMs: Long): StepResult {
        var target: AccessibilityNodeInfo? = null
        if (targetHint.isNotBlank()) {
            target = waitForNode(targetHint, timeoutMs)
        }
        if (target == null) {
            val root = rootInActiveWindow
            target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        if (target == null) {
            return StepResult(false, "글자를 입력할 입력창을 찾지 못했어요.")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return StepResult(ok, if (ok) "\"$text\" 입력했어요." else "입력창은 찾았지만 글자를 넣지 못했어요.")
    }

    private fun scroll(forward: Boolean): StepResult {
        val root = rootInActiveWindow ?: return StepResult(false, "현재 화면 정보를 읽지 못했어요.")
        val scrollable = findScrollable(root) ?: return StepResult(false, "스크롤할 수 있는 영역을 못 찾았어요.")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = scrollable.performAction(action)
        return StepResult(ok, if (ok) "스크롤했어요." else "스크롤에 실패했어요.")
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }
}

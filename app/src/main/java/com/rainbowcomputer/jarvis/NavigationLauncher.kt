package com.rainbowcomputer.jarvis

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * "스타벅스 둔산점으로 길안내해줘" 같은 음성 명령을 실제 내비게이션 앱
 * 실행으로 바꾼다. PC는 좌표를 모르고 "목적지 이름(텍스트)"만 넘겨주기
 * 때문에, 좌표 기반 SDK 연동(예: 카카오내비 NaviClient — 개발자 앱 키와
 * Location(위경도)이 필요함, developers.kakao.com/docs 참고) 대신 각
 * 앱이 제공하는 "텍스트 검색/길안내" URL 스킴을 쓴다.
 *
 * 확실히 검증된 것: 구글맵의 google.navigation:q=<검색어> 는 수년간
 * 안정적으로 쓰이는 공식 방식이라 가장 신뢰도가 높다(안드로이드
 * 공식 문서에 명시돼 있음).
 *
 * ⚠️ 미검증(개발 환경에 실제 기기가 없어 직접 테스트하지 못함): 카카오맵/
 * 네이버지도의 search 스킴은 각 서비스 문서/커뮤니티 자료를 참고해
 * 작성했지만, 앱 버전에 따라 파라미터가 바뀌었을 수 있다. 실제로 안
 * 열리면 이 함수가 자동으로 다음 우선순위(구글맵 → 지도 앱 아무거나
 * 열리는 범용 geo: 인텐트)로 넘어가므로 "길안내 자체가 실패"하는 일은
 * 없지만, 특정 앱이 안 열리면 README_설치방법.md의 "내비게이션이 원하는
 * 앱으로 안 열려요" 항목을 참고해 스킴을 조정해야 할 수 있다.
 */
object NavigationLauncher {

    /** 설치 여부와 무관하게 "시도해볼 인텐트 후보"를 우선순위대로 돌려준다. */
    private fun candidateIntents(context: Context, query: String): List<Intent> {
        val encoded = Uri.encode(query)
        val list = mutableListOf<Intent>()

        // 1) 구글맵 — 공식 문서에 나온 안정적인 방식. 설치돼 있으면 최우선.
        if (isInstalled(context, "com.google.android.apps.maps")) {
            list.add(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
                    .setPackage("com.google.android.apps.maps")
            )
        }

        // 2) 네이버지도 — nmap://search?query=&appname=<내 패키지명> 스킴
        //    (네이버 개발자 문서 기준, appname은 자기 패키지명을 그대로 씀).
        if (isInstalled(context, "com.nhn.android.nmap")) {
            list.add(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("nmap://search?query=$encoded&appname=${context.packageName}")
                )
            )
        }

        // 3) 카카오맵 — search 스킴(⚠️ 미검증, 위 설명 참고).
        if (isInstalled(context, "net.daum.android.map")) {
            list.add(Intent(Intent.ACTION_VIEW, Uri.parse("kakaomap://search?q=$encoded")))
        }

        // 4) 티맵 — route 스킴은 좌표가 필요해 텍스트 검색엔 안 맞는 경우가
        //    많아, 범용 길찾기 화면만 열어주는 보수적인 방식으로 둔다.
        if (isInstalled(context, "com.skt.tmap.ku")) {
            list.add(Intent(Intent.ACTION_VIEW, Uri.parse("tmap://search?name=$encoded")))
        }

        // 5) 마지막 안전망 — 설치된 아무 지도 앱이나 열어주는 범용 인텐트.
        //    여기 도달했다는 건 위 앱이 하나도 없다는 뜻이라, 사용자 기본
        //    지도 앱(보통 구글맵)이 처리해준다.
        list.add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))

        return list
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * 후보들을 순서대로 실제 실행해보고, 처음으로 성공한 결과를 돌려준다.
     * 전부 실패하면(지도 앱이 정말 하나도 없는 극히 드문 경우) false.
     */
    fun navigateTo(context: Context, query: String): Boolean {
        for (intent in candidateIntents(context, query)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
        return false
    }
}

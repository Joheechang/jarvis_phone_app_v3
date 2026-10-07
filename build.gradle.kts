// 최상위 build.gradle.kts — 모듈(app)에서 쓸 플러그인 버전만 여기서
// 한 번에 선언해둔다(실제 적용은 app/build.gradle.kts에서).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

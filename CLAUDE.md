# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a native Android application (`com.example.minseo3`) built with Java and the AndroidX stack. It targets API 36 (Android 16) with a minimum SDK of 24 (Android 7.0). The project uses AGP 9.1.1 and Gradle with Kotlin DSL.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests (JVM, no device needed)
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.example.minseo3.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build outputs
./gradlew clean
```

## Architecture

Single-module Android app (`app/`). The only module is `:app` with package `com.example.minseo3`.

- **`MainActivity.java`** — sole Activity, entry point; uses `EdgeToEdge` for system bar handling and `ConstraintLayout` as the root layout
- **`res/layout/activity_main.xml`** — layout for `MainActivity`
- **`res/values/`** — `colors.xml`, `strings.xml`, `themes.xml` (Material3-based theme); night variants in `res/values-night/`

## Key Dependencies

All versions are managed via `gradle/libs.versions.toml` (version catalog):

| Library | Purpose |
|---|---|
| `androidx.appcompat` | AppCompatActivity base class |
| `androidx.activity` | `EdgeToEdge` and `ComponentActivity` |
| `androidx.constraintlayout` | Root layout in activity_main |
| `com.google.android.material` | Material3 theme |
| `junit` | Local unit tests |
| `androidx.test.espresso` | UI instrumented tests |

## Code Conventions

- Source language is **Java** (not Kotlin). New files should be `.java`.
- Build scripts use **Kotlin DSL** (`.gradle.kts`).
- `compileSdk` uses the `release()` DSL block (AGP 9.x feature) targeting API 36 with `minorApiLevel = 1`.

## Device Screenshot Capture

Capturing a screenshot from the connected Android device on **Windows + Git Bash** must use the save-to-device-then-pull pattern. Do NOT pipe `screencap` stdout to a local file.

Chain with `;`, not `&&` — `screencap` exits 1 even on success (see below), which would block the remaining steps under `&&`.

```bash
adb shell screencap -p /sdcard/screen.png; \
MSYS_NO_PATHCONV=1 adb pull /sdcard/screen.png 'D:\workspace\Minseo3\screen.png'; \
MSYS_NO_PATHCONV=1 adb shell rm /sdcard/screen.png
```

Why each step matters:
1. **Save on device, then pull** — this device has two displays, so `adb shell screencap -p` on stdout interleaves a usage warning (text) with PNG bytes, corrupting the image. The warning also makes `screencap` exit with code 1 even though the on-device PNG is fine. Not a CRLF issue. Use `;` (not `&&`) so the non-zero exit does not abort the pull/rm.
2. **`MSYS_NO_PATHCONV=1` on BOTH `adb pull` and `adb shell rm`** — Git Bash rewrites POSIX-looking paths like `/sdcard/...` into Windows paths (`C:/Program Files/Git/sdcard/...`) before `adb` sees them. Required on any `adb` invocation whose argument is a device-side path, not just `pull`.
3. **Local destination as Windows path** — use `D:\...` style for the pull target (single-quoted to preserve backslashes).

### Multi-display note

This device exposes two displays (seen via `adb shell dumpsys SurfaceFlinger --display-id`):

- HWC 0 (`4630946474867211650`) — off / black screen
- HWC 3 (`4630946213010294403`) — **default; where the app runs**

`screencap` without `-d` captures the default (HWC 3), which is the right one here. If you ever need the other display, pass `-d <id>` BEFORE `-p` (e.g., `screencap -d <id> -p /sdcard/out.png`); reversed order (`-p -d <id>`) is rejected by this device's screencap and only prints usage. Avoid `-a`: on this device it writes files without a `.png` suffix, which makes `screencap` save raw framebuffer bytes instead of a PNG.

## Workflow preferences

- **빌드까지만, 설치는 안 함.** 코드 변경 후 `./gradlew assembleDebug` 로 빌드 검증만
  하고 APK 를 기기에 설치하지 않는다. 사용자가 "설치해줘" 등 명시적으로 요청했을
  때만 `adb install`. (설치 테스트용 유틸은 `scripts/apk.sh` 참고.)
- **커밋까지만, push 는 안 함.** 작업 완료 후 `git commit` 으로 로컬 히스토리에
  남기되, `git push` 는 사용자가 명시적으로 요청했을 때만. PR 생성 (`/ship`,
  `gh pr create` 등) 도 명시 요청이 있을 때만.
- 이유: 사용자가 빌드/커밋 결과를 직접 확인한 뒤 기기 설치와 원격 push 를
  본인 리듬으로 진행. 자동 설치/자동 push 는 "이 타이밍이 아닌데" 감을 유발.

## Skill routing

When the user's request matches an available skill, ALWAYS invoke it using the Skill
tool as your FIRST action. Do NOT answer directly, do NOT use other tools first.
The skill has specialized workflows that produce better results than ad-hoc answers.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke office-hours
- Bugs, errors, "why is this broken", 500 errors → invoke investigate
- Ship, deploy, push, create PR → invoke ship
- QA, test the site, find bugs → invoke qa
- Code review, check my diff → invoke review
- Update docs after shipping → invoke document-release
- Weekly retro → invoke retro
- Design system, brand → invoke design-consultation
- Visual audit, design polish → invoke design-review
- Architecture review → invoke plan-eng-review
- Save progress, checkpoint, resume → invoke checkpoint
- Code quality, health check → invoke health

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

# 🛠️ Engineering & Build Guide

This document defines the protocols for setting up the development environment, understanding the underlying technology stack, and compiling **ArchiveTune** from the source.

---

## 🏗️ Operational Readiness

To ensure build stability and environment parity, the following hardware and software configurations are mandatory.

### **Development Environment**

* **IDE:** [Android Studio](https://developer.android.com/studio), with support for the checked-in Android Gradle Plugin; command-line builds are also supported.
* **Java Runtime:** **JDK 21**.
* **Android SDK:** compile SDK **37**, NDK **30.0.16248370** and the build tools selected by the project. Runtime minimum is Android 8.0/API 26.
* **Gradle:** use the checked-in **9.6.1** wrapper, not a system Gradle installation.
* **Version Control:** Git 2.40+.

### **Technical DNA (Skill Requirements)**

The ArchiveTune codebase is built on a modern, reactive architecture. Contributors are expected to have a high level of familiarity with:

* **Kotlin (Advanced):** Proficiency in Coroutines, Flow API, and functional paradigms.
* **Jetpack Compose:** Understanding of State Hoisting, Recomposition optimization, and Material 3 design systems.
* **Gradle (KTS):** Ability to navigate Kotlin DSL build scripts and Version Catalogs (`libs.versions.toml`).
* **Modern Android Architecture:** Deep understanding of MVVM, Repository patterns, and UDF (Unidirectional Data Flow).

---

## 📐 Architectural Manifesto

ArchiveTune separates UI, data and playback responsibilities; preserve the concrete ownership and fork invariants in [AGENTS.md](AGENTS.md) and [architecture](docs/claude/ARCHITECTURE.md).

1. **UI Layer (Compose):** Handles user interactions and renders state emitted by ViewModels.
2. **Domain Layer:** Contains business logic, Use Cases, and high-level audio processing interfaces.
3. **Data Layer:** Coordinates provider APIs, Room data and preferences. Pool credentials use a separate Keystore-encrypted cache; that does not establish encryption for every database/preference.
4. **Service Layer (Media3):** A specialized background layer managing the `MediaSession` and low-latency audio pipelines.

---

## 🚀 Environment Initialization

1. **Clone the Source:**
```bash
git clone --recurse-submodules https://github.com/NatuneGroup/ArchiveTune.git
cd ArchiveTune
git switch dev
git submodule update --init --recursive
```


2. **Secret Management:**
ArchiveTune uses a modular properties system. If your build requires specific API keys (e.g., Discord Client IDs), define them in your `local.properties`:
```properties
# Path to your Android SDK
sdk.dir=/Users/yourname/Library/Android/sdk

```


3. **Syncing the Core:**
Open the project in Android Studio or use the wrapper below with JDK 21 and the SDK path configured. Keep the checked-in submodule revisions; do not update them to upstream tips. Version catalogs and build scripts define dependency versions, not proof that an unvalidated local change passed tests.

---

## 📦 Build Pipelines

Use the Gradle Wrapper with an explicit variant. GMS/FOSS, mobile/TV and ABI axes do not imply every combination ships.

| Command | Output | Context |
| --- | --- | --- |
| `./gradlew assembleGmsMobileUniversalDebug` | Debug APK under `app/build/outputs/apk/` | Local development; install/launch the APK on an API 26+ device or emulator. |
| `./gradlew lintGmsMobileUniversalDebug` | Android lint reports | Same universal GMS mobile variant as PR CI. |
| `./gradlew :app:testGmsMobileUniversalDebugUnitTest` | Unit-test reports | Separate from APK assembly; also compiles `app/src/test`. |

Debug builds use AGP's local debug key. Release/Nightly publication uses the historical
signing identity from existing CI secrets; never restore a committed keystore or assume
a debug build establishes release shrinking/native packaging/signature compatibility.
Standard packages exclude optional automix models/runtime; `-Pautomix=true` opts in.

---

## 🛡️ Code Quality & Static Analysis

Before promotion, obtain both exact-head gates:

* **Build and lint:** `Build Pull Request` / `build`, using the explicit assembly/lint tasks above.
* **Logic verification:** `Unit tests` / `Compile and run unit tests`, dispatched separately and run concurrently with the build.
  Its init script also isolates test classes and verifies critical-policy coverage;
  see [coverage and instability checks](docs/engineering/quality.md#unit-test-coverage-isolation-and-intermittent-failures).
* **Style:** match surrounding code. The isolated quality project runs Spotless/ktfmt
  through `python3 scripts/check_code_quality.py --base <base-commit>` on a build host.

Run `python3 scripts/check_repository.py --worktree` for lightweight documentation,
architecture, dependency-version, file-budget and attribution checks. Install the
staged-content pre-commit hook and follow the concrete naming conventions in
[quality gates](docs/engineering/quality.md). These checks do not start a JVM.
Use the [incident runbook](docs/engineering/incident-runbook.md) for reported failures.
The [generated toolchain reference](docs/engineering/toolchain.md) tracks current
versions and CI commands; [Android QA](docs/android-qa.md) includes an auth-free
synthetic playback check. A [remote devcontainer](.devcontainer/devcontainer.json)
and optional [.env.example](.env.example) document reproducible development setup.

The current constrained mission prohibits local JVM/Gradle/LSP execution and defers focused
tests/execution to final remote CI. This is mission-scoped, not a general contributor rule.
Its current implementation is locally integrated, uncompiled and unreleased; earlier green
`e0ada1758` does not validate it. No current device QA was run.

`dev` is Nightly integration, `main` is Stable. Final Canary `C202610141722` shipped at
`a1a04ea02`; `canary` is frozen. Use a new dev PR after review, preserve history on merge
and verify the resulting Nightly/manifest. See [release gates](docs/claude/RELEASES.md);
compilation alone is not UI, language, physical USB/DAC or listening validation.

---

## ⚖️ Troubleshooting

> [!IMPORTANT]
> **Memory:** Use the checked-in resource settings first. Collect the failing task and logs before changing heap, workers, KSP or configuration-cache policy; larger heaps are not automatically suitable for constrained hosts.

> [!WARNING]
> **Toolchain mismatch:** Check JDK 21, wrapper/AGP/Kotlin versions and SDK/NDK installation against the checked-in project. Do not add legacy Compose compiler settings or reorder dependency mirrors to work around an unrelated resolution failure.

---

<div align="center">
<sub>ArchiveTune: Engineering audio freedom. Part of the <strong>Koiverse</strong> ecosystem.</sub>
</div>
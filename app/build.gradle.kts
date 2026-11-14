import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.takahirom.roborazzi")
}

/**
 * Per-developer configuration, read from `local.properties` (gitignored).
 *
 * **The OAuth credentials are NOT in this repo.** Each developer creates their own Google Cloud
 * project and supplies its client ids here, because:
 *  - the Android client authenticates by package name + signing SHA-1, so someone else's key can
 *    never match this app's registration — they must register their own anyway;
 *  - the Web client's secret is treated as confidential by Google's own docs;
 *  - a shared consent screen means someone else's users see your app name and your quota.
 *
 * A missing value is not fatal at build time — the app compiles and starts, and login fails with
 * "build not configured" instead of silently authenticating against a stranger's project.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String): String = (localProps.getProperty(key) ?: "").trim()

android {
    namespace = "com.mido.phonebridge"
    compileSdk = 35

    /*
     * Stable signing. Android ties runtime permissions (notifications, full-screen
     * intent, notification-listener binding) to the app's signing identity. If the
     * key changes between builds, the OS treats the install as a DIFFERENT app and
     * resets those grants. Pinning one project keystore keeps the identity stable,
     * so an update preserves permissions and data.
     */
    /*
     * Signing. ONE keystore, and a missing one is a hard error.
     *
     * This used to fall back to `~/.android/debug.keystore` so a fresh clone would still build.
     * That fallback was removed (Mido, 2026-11-03: *"no fucking fallbacks of any kind"*), and the
     * reason is the one a fallback always hides: it produced a BUILD SUCCESS with the wrong
     * identity. A different signing key is a different app to Android — an APK signed with the
     * debug keystore will not install over one signed with the project key, and its SHA-1 is not
     * the one registered in Google Cloud Console, so OAuth rejects it. Everything about the
     * artifact would be wrong while every signal said it was fine.
     *
     * A missing keystore is a setup problem with exactly one fix — restore the keystore — so the
     * build says that, immediately, instead of shipping something that looks right.
     *
     * The keystore is deliberately gitignored: it IS the app's identity, and committing it would
     * let anyone publish an update as this app. Restore it from the password manager or the
     * documented outage copy; never commit it.
     */
    signingConfigs {
        create("stable") {
            // NOTE: no existence check here, deliberately — see `requireKeystoreForSigning`
            // below. Throwing from this block runs at CONFIGURATION time, so it would also
            // fail `testDebugUnitTest` and `lint`, which need no keystore at all. A check
            // in the wrong place is not a stricter check, it is a broken build for tasks
            // that never sign anything.
            storeFile = file("../keystore/stmp.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }


    defaultConfig {
        applicationId = "com.mido.phonebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 334
        versionName = "0.52.19"

        // OAuth, from local.properties. Empty when unset, which the app treats as "not configured".
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProp("google.clientId")}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${localProp("google.serverClientId")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${localProp("google.clientSecret")}\"")

        // Where the EXTERNAL AI backend lives (see AiBackend.EXTERNAL). Empty means "not
        // configured", and ExternalModel falls back to its compiled default. Kept in
        // local.properties so a different host does not need a code change.
        buildConfigField("String", "EXTERNAL_AI_URL", "\"${localProp("ai.externalUrl")}\"")

        /*
         * arm64 ONLY. The litertlm AAR ships native libraries for BOTH arm64-v8a and
         * x86_64, and we were packaging both: 25.1 MB of `lib/x86_64/liblitertlm_jni.so`
         * that can never load on the target phone, on every single APK. That is 36% of a
         * 69 MB download spent on a library for an emulator nobody runs — and it is what
         * made the Taildrop transfer of 0.23.x stall repeatedly (two 150s timeouts, then a
         * background run that moved ~10 MB of 69 MB and froze).
         *
         * The target device is a Samsung S22 Ultra (arm64-v8a) and the app is sideloaded by
         * hand, so x86_64 has no use here. If an emulator is ever needed, add it back
         * deliberately — do NOT "fix" a future x86_64 install by deleting this filter and
         * shipping the dead weight to the phone again.
         */
        /*
         * Opt-in emulator ABI. The filter above stays arm64-only unless the build is
         * explicitly asked for x86_64:
         *
         *     ./gradlew assembleDebug -PemuAbi=true
         *
         * Nothing else changes, so a normal build and the APK that reaches the phone are
         * byte-for-byte what they were. This exists because an x86_64 emulator cannot load
         * `liblitertlm_jni.so` from an arm64-only APK, and the alternative — deleting the
         * filter permanently — is exactly what the comment above forbids.
         */
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
            if (project.findProperty("emuAbi") == "true") {
                abiFilters.add("x86_64")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    // APK filename: `stmp-vX.Y.Z.apk` and NOTHING else (Mido, 2026-10-26).
    // Old default was `app-debug.apk` — wrong name. Set once here, drawn from the
    // one versionName in defaultConfig, so the name is never a thing a build step
    // has to "remember".
    val apkVersionName = android.defaultConfig.versionName
    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let { out ->
                out.outputFileName = "stmp-v${apkVersionName}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    /*
     * Android Lint (the ESLint equivalent for Android). Runs on build so issues
     * cannot silently accumulate.
     */
    lint {
        // **`abortOnError` alone was not enough, and that is what let 8 errors ship.**
        // `assembleDebug` does not run lint at all — verified: zero lint tasks in its graph —
        // and `assembleRelease` runs only `lintVital`, which is a narrow subset. So a build
        // that succeeded said nothing about lint, and the first time it ever ran was in CI.
        //
        // The fix is not a setting, it is participation: `check` now depends on lint below, and
        // `check` is what CI and any future `./gradlew check` runs.
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        // Treat these as ERRORS even though they are warnings by default: each has already
        // caused a real defect in this project, or guards something that would crash a device
        // rather than merely look wrong.
        error += setOf(
            "NewApi",              // caught BigInteger.TWO on API 33 — ed25519 was dead below 33
            "InlinedApi",          // the same class of bug, via inlined constants
            "MissingSuperCall",    // caught the missing super.onBackPressed()
            "Recycle",             // a leaked cursor/typed-array is a slow leak, always a bug
            "StaticFieldLeak",     // a static Context outlives the Activity that owned it
            "MissingApplicationIcon",
            "DataExtractionRules",
        )
        disable += setOf(
            "GradleDependency",
            "OldTargetApi",
            "NewerVersionAvailable",
        )
        // These stay as warnings rather than errors.
        warning += setOf("SetTextI18n", "HardcodedText", "UnusedResources")
        error += setOf("NewApi", "InlinedApi", "MissingApplicationIcon", "DataExtractionRules")
    }

    testOptions {
        unitTests {
            /*
             * Run unit tests on ROBOLECTRIC's Android runtime instead of a bare JVM.
             *
             * Without this, `testDebugUnitTest` executes against whatever jars the test classpath
             * happens to hold — which is how two releases shipped green tests for code that could
             * not run on the phone:
             *
             *   - reference `org.json` returns "" from optString on a JSON null, Android returns
             *     "null". A phantom host-key mismatch, invisible to the tests.
             *   - JSch's Multi-Release JAR resolves `META-INF/versions/15/` on a desktop JVM and
             *     not on Android, so ed25519 generated fine in tests and threw on the device.
             *
             * `isIncludeAndroidResources` is on because Robolectric needs the merged resources to
             * build its Android environment.
             */
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.logging", "stdout")

                // Test parallelism, chosen by MACHINE SIZE rather than guessed once.
                //
                // Unset meant ONE fork: all 419 Robolectric tests in a single JVM, which was the
                // single largest step in CI (2m00 of a 6m run). CI has 4 cores and 16 GB, so two
                // forks is free there.
                //
                // It is NOT free on the dev box. This VM shares 24 GB with the gateway and has
                // already been OOM-killed by an unbounded compile (see AGENTS.md); Robolectric
                // loads a full Android runtime per fork, so two forks on a tight box risks the
                // gateway rather than the build. Hence the explicit branch, not a constant: the
                // number is a fact about the machine, and the machine is what selects it.
                //
                // `CI` is set to "true" by GitHub Actions. Anywhere else stays at 1.
                test.maxParallelForks = if (System.getenv("CI") == "true") 2 else 1

                // Fixed English locale: number and date formatting (and so SummaryFailureTest and
                // the screenshots) no longer depend on the machine's region.
                test.jvmArgs("-Duser.language=en", "-Duser.country=US")
                // Native graphics draws elevation and shadows the way a device does.
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

roborazzi {
    // Reference images are committed; the default build/ location would be wiped by clean.
    outputDir.set(file("src/test/screenshots"))
}

/*
 * Kotlin compiler options. This lives outside `android {}` because the old
 * `kotlinOptions` block inside it is an error as of Kotlin 2.4 — the DSL moved to
 * the top-level `kotlin { compilerOptions { } }` extension.
 */
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Material 3: real MaterialButton (ripple + state list + 48dp targets) and a
    // proper dark theme. Every control in this app is built programmatically, but
    // the components and the theme machinery are exactly what we do not want to
    // hand-roll (a custom GradientDrawable background is what killed the ripple).
    implementation("com.google.android.material:material:1.12.0")
implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // On-device LLM inference for the request summariser (LiteRT-LM — Google's
    // supported successor to the deprecated MediaPipe tasks-genai API). The model
    // FILE is not bundled — it is a separate ~475MB asset (see ai/ModelRegistry.kt).
    // This dependency is the runtime only; if the asset is absent the app falls back
    // to HeuristicSummarizer and nothing else changes.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // SSH transport for the ssh gate. `com.github.mwiede:jsch` is the maintained fork of
    // JSch (the original jcraft artifact has not been updated in a decade and carries
    // algorithms modern OpenSSH rejects). 2.28.8 supports ed25519, which is what a key
    // generated on a current phone should be.
    //
    // NOT a favourite, just the one that needs no BouncyCastle: sshj is a better-engineered
    // library, but it pulls in bcprov, and this APK already carries a ~475 MB model — the
    // fewer heavyweight dependencies the better. The surface used here is tiny (connect,
    // auth with a key, exec, capture output), so the abstraction cost is low either way.
    implementation("com.github.mwiede:jsch:2.28.8")
    // The shell lexer (module :shellhl). No Android imports inside it, no dependencies of
    // its own — it exists so the SSH card can colour a command from the same tokens it uses
    // to decide where lines break. See docs/plans/SHELL-HIGHLIGHT-PLAN.md.
    implementation(project(":shellhl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // BiometricPrompt for the approval gate: STRONG biometrics or device credential.
    implementation("androidx.biometric:biometric:1.1.0")
    // Google Identity Authorization API (Credential Manager successor for
    // authorization-code + offline access). Replaces the retired GoogleSignIn.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // **Robolectric, so unit tests run ANDROID's implementations, not the reference ones.**
    //
    // Two bugs shipped today for the same reason: the test JVM was not the device's runtime.
    //   1. `org.json` — the reference jar returns "" from `optString` on a JSON null; Android
    //      returns the STRING "null". That turned every unpinned SSH connection into a phantom
    //      host-key mismatch, and the tests were green the whole time.
    //   2. JSch's Multi-Release JAR — the desktop JVM reads `META-INF/versions/15/`, Android does
    //      not, so ed25519 worked in tests and could not run on the phone at all.
    //
    // Robolectric runs the tests against real Android framework classes, which removes the first
    // class of divergence outright. (The second is covered by `Ed25519Test` now implementing the
    // crypto in-app: there is no library left whose packaging can differ.)
    //
    // `org.json:json` is deliberately GONE: keeping it would let the reference implementation win
    // the classpath and re-open exactly the hole this is closing.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.77.0")
}

/*
 * The signing keystore is REQUIRED — for tasks that actually sign, and nowhere else.
 *
 * SCOPE IS THE POINT. This lives at the top level and fires from `taskGraph.whenReady`, so it
 * runs once the requested tasks are known:
 *
 *   - `testDebugUnitTest`, `lint`, `compileDebugKotlin`  -> pass on a machine with no keystore
 *     (exactly a CI pull-request job, which has no secrets by design)
 *   - `assembleRelease`, `assembleDebug`, `bundle*`      -> FAIL BEFORE COMPILING if the key
 *     is missing
 *
 * A throw inside `signingConfigs { }` would run at CONFIGURATION time and fail the test and
 * lint tasks too. That is not a stricter check, it is a broken build for tasks that never
 * sign anything.
 *
 * WHAT THIS REPLACES: a silent fallback to `~/.android/debug.keystore`. It produced BUILD
 * SUCCESS with the wrong identity — an APK that will not install over existing STMP, whose
 * SHA-1 is not the one registered in Google Cloud Console, so OAuth rejects it. Wrong
 * artifact, every signal green. Removed 2026-11-03 (Mido: "no fucking fallbacks of any kind").
 */
gradle.taskGraph.whenReady(object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
    override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
        // Anchor on a BUILD TYPE suffix. A loose `startsWith("bundle")` also matches AGP's
        // internal `bundleDebugClassesToCompileJar` / `bundleDebugClassesToRuntimeJar`, which
        // appear in a plain `testDebugUnitTest` graph and produce no signed artifact — so the
        // guard fired on the test job and a keystore-less CI pull request could never pass.
        // `assembleDebug`, `assembleRelease`, `bundleRelease` all end in a build type; nothing
        // that only compiles or packages classes does.
        val artifactTask = Regex("^(assemble|bundle)[A-Za-z]*?(Debug|Release)$")
        val signsSomething = graph.allTasks.any { artifactTask.matches(it.name) }
        if (!signsSomething) return

        val key = rootProject.file("keystore/stmp.jks")
        if (!key.isFile) {
            throw GradleException(
                "Signing keystore not found: " + key.absolutePath + "\n" +
                    "This build has NO fallback key on purpose. A debug-signed APK is a " +
                    "different app identity: it will not install over an existing STMP, and its " +
                    "SHA-1 does not match the one registered in Google Cloud Console, so OAuth " +
                    "would fail.\n" +
                    "Restore keystore/stmp.jks (alias androiddebugkey, password android, " +
                    "SHA-1 87:DD:D5:DE:FA:06:89:33:F4:4E:C6:99:53:3A:1B:00:ED:31:31:03)."
            )
        }
    }
})

/*
 * Make `check` actually run lint.
 *
 * `assembleDebug` has ZERO lint tasks in its graph (verified) and `assembleRelease` runs only
 * `lintVital` — a narrow subset. So `./gradlew assembleDebug`, which is the build this project
 * used for every release handover, could never fail on lint. Eight lint errors shipped that way,
 * including a class-load crash on API < 33.
 *
 * `abortOnError = true` was already set and did nothing, because the task never ran. A severity
 * setting cannot make a task participate; only the graph can.
 *
 * This wires `lintDebug` in front of `check` for the app module. Deliberately NOT attached to
 * `assembleDebug`: a debug build is the fast inner loop, and blocking it on a full lint pass
 * would push people to `-x lint`. `check` is the gate, and it is what CI runs.
 */
gradle.taskGraph.whenReady(object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
    override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
        val wantsCheck = graph.allTasks.any { it.name == "check" }
        if (!wantsCheck) return
        val lintDebug = project(":app").tasks.findByName("lintDebug") ?: return
        val check = graph.allTasks.firstOrNull { it.name == "check" } ?: return
        check.dependsOn(lintDebug)
    }
})

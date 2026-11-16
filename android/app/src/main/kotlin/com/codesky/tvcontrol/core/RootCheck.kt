package com.codesky.tvcontrol.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Is this TV rooted, and if so, can an app like this one actually use it?
 *
 * Those are two different questions and the check keeps them apart. A box can ship a
 * `su` that only the adb shell may run (common on developer builds with `ro.debuggable=1`),
 * which makes "rooted" true for a person at a terminal and false for every app. The
 * verdict therefore has four levels instead of a yes/no:
 *
 *  - `app`   - this app ran `su` and got uid 0;
 *  - `shell` - a `su` binary or an adb root daemon exists, but this app cannot use it;
 *  - `weak`  - nothing is rooted yet, but the build is one that adb can usually root;
 *  - `none`  - a locked-down production build.
 *
 * Everything here is read-only. The one process it starts on purpose is `su -c id`, with
 * a hard timeout, because a root manager may pop a grant prompt on screen.
 */
object RootCheck {

    private val SU_PATHS = listOf(
        "/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
        "/su/bin/su", "/system/xbin/daemonsu", "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su", "/odm/bin/su",
    )

    private val ROOT_APPS = listOf(
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.huskydg.magisk" to "Magisk (Delta)",
        "io.github.vvb2060.magisk" to "Magisk (Alpha)",
        "me.weishu.kernelsu" to "KernelSU",
        "me.bmax.apatch" to "APatch",
        "eu.chainfire.supersu" to "SuperSU",
        "com.thirdparty.superuser" to "Superuser",
        "com.koushikdutta.superuser" to "Superuser (ClockworkMod)",
        "com.noshufou.android.su" to "Superuser (ChainsDD)",
        "com.kingroot.kinguser" to "KingRoot",
    )

    private val MAGISK_TRACES = listOf("/sbin/.magisk", "/sbin/.core", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap")

    private const val SU_TIMEOUT_MS = 4000L

    @Serializable
    data class Check(
        val id: String,
        val label: String,
        /** true = a sign of root, false = absent, null = could not be determined. */
        val found: Boolean?,
        val detail: String,
    )

    @Serializable
    data class Report(
        val level: String,
        val rooted: Boolean,
        val appCanUseRoot: Boolean,
        val headline: String,
        val verdict: String,
        val checks: List<Check>,
        val actions: List<String>,
        val checkedAt: Long,
    )

    suspend fun collect(context: Context): Report = withContext(Dispatchers.IO) {
        val suFiles = SU_PATHS.filter { File(it).exists() }
        val suOnPath = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
            .map { "$it/su" }.filter { it !in SU_PATHS && File(it).exists() }
        val allSu = suFiles + suOnPath

        val apps = ROOT_APPS.filter { (pkg, _) -> installed(context, pkg) }.map { it.second }
        val traces = MAGISK_TRACES.filter { File(it).exists() }

        val debuggable = prop("ro.debuggable")
        val secure = prop("ro.secure")
        val tags = prop("ro.build.tags").ifEmpty { Build.TAGS.orEmpty() }
        val adbRoot = prop("service.adb.root")
        val bootState = prop("ro.boot.verifiedbootstate")
        val flashLocked = prop("ro.boot.flash.locked")
        val seLinux = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()

        val systemRw = mountedWritable("/system") ?: mountedWritable("/")
        val magiskMounts = runCatching {
            File("/proc/self/mounts").readLines().any { it.contains("magisk", true) || it.contains("/.magisk") }
        }.getOrDefault(false)

        val myUid = android.os.Process.myUid()
        // Always try: SELinux hides /system/xbin/su from app processes, so a missing file in the
        // list above proves nothing. Exec tells the cases apart - ENOENT means no su on PATH,
        // EACCES means it is there and Android refuses this app.
        val suResult = trySu()
        val suExists = allSu.isNotEmpty() || suResult.exists

        val adbdRootable = debuggable == "1" || adbRoot == "1"
        val testKeys = tags.contains("test-keys")

        val checks = listOf(
            Check(
                "su-binary", "su binary",
                suExists,
                when {
                    allSu.isNotEmpty() -> allSu.joinToString(", ") + describeModes(allSu)
                    suResult.exists -> "Present but hidden from this app (Android denies it access to the file)"
                    else -> "Not found in ${SU_PATHS.size} usual locations or on PATH"
                },
            ),
            Check(
                "su-run", "This app can run su",
                if (!suResult.ran || !suExists) null else suResult.uid0,
                suResult.detail,
            ),
            Check(
                "root-app", "Root manager installed",
                apps.isNotEmpty(),
                if (apps.isEmpty()) "Magisk, KernelSU, APatch, SuperSU and friends: none found" else apps.joinToString(", "),
            ),
            Check(
                "magisk-traces", "Magisk / KernelSU traces",
                traces.isNotEmpty() || magiskMounts,
                if (traces.isEmpty() && !magiskMounts) "No root-framework files or mounts visible to this app" else (traces + if (magiskMounts) listOf("magisk mounts") else emptyList()).joinToString(", "),
            ),
            Check(
                "debuggable", "Debuggable build (ro.debuggable)",
                debuggable == "1",
                "ro.debuggable=${debuggable.ifEmpty { "?" }}, ro.secure=${secure.ifEmpty { "?" }}" +
                    if (debuggable == "1") " - adbd can run as root" else "",
            ),
            Check(
                "adb-root", "adb root daemon",
                adbRoot == "1",
                if (adbRoot == "1") "service.adb.root=1 - adbd is running as root" else "service.adb.root=${adbRoot.ifEmpty { "unset" }}",
            ),
            Check(
                "build-tags", "Build signed with test keys",
                testKeys,
                "ro.build.tags=${tags.ifEmpty { "?" }}, type ${Build.TYPE}",
            ),
            Check(
                "system-rw", "/system mounted read-write",
                systemRw,
                when (systemRw) {
                    true -> "Writable - modified system image"
                    false -> "Read-only (normal)"
                    null -> "Could not read the mount table"
                },
            ),
            Check(
                "verified-boot", "Bootloader / verified boot",
                bootState.isNotEmpty() && bootState != "green",
                "verifiedbootstate=${bootState.ifEmpty { "?" }}, flash.locked=${flashLocked.ifEmpty { "?" }}" +
                    when (bootState) {
                        "orange" -> " - unlocked bootloader"
                        "yellow" -> " - custom signing key"
                        "red" -> " - verification failed"
                        else -> ""
                    },
            ),
            Check(
                "selinux", "SELinux",
                seLinux == "0",
                when (seLinux) {
                    "1" -> "Enforcing"
                    "0" -> "Permissive - protections are off"
                    else -> "State not readable by this app"
                },
            ),
            Check(
                "own-uid", "This app's own user id",
                myUid == 0,
                "uid $myUid (apps normally run as 10000+)",
            ),
        )

        val level = when {
            suResult.uid0 -> "app"
            suExists || adbRoot == "1" || apps.isNotEmpty() || traces.isNotEmpty() -> "shell"
            adbdRootable || testKeys || systemRw == true || (bootState.isNotEmpty() && bootState != "green") -> "weak"
            else -> "none"
        }

        val (headline, verdict) = when (level) {
            "app" -> "Rooted" to "This TV is rooted and this app was granted root: `su` returned uid 0."
            "shell" -> "Rooted - for adb only" to
                (if (suExists) "A su binary exists${allSu.firstOrNull()?.let { " ($it)" } ?: ""}, " else "Root tooling is present, ") +
                (if (adbRoot == "1" || debuggable == "1") "and adbd can run as root, so an adb shell is uid 0. " else "") +
                "But this app could not run it (${suResult.detail.lowercase().trimEnd('.')}). " +
                "Root works from your computer's terminal, not from apps on the TV."
            "weak" -> "Not rooted (rootable)" to
                "No su binary and no root manager, but this is a developer-style build " +
                (if (adbdRootable) "(debuggable)" else if (testKeys) "(test keys)" else "(unlocked bootloader)") +
                ", which adb can usually root with `adb root`."
            else -> "Not rooted" to "No su, no root manager and no developer-build markers. This looks like a locked production system."
        }

        val actions = buildList {
            if (level == "shell" || level == "weak") {
                add("From your computer: adb root, then adb shell id - uid=0 means the shell has full root.")
            }
            if (level == "shell") {
                add("Apps cannot use that root. To give this app root, a root manager (Magisk / KernelSU) or an su that grants app uids is needed; it is not required for any current feature.")
            }
            if (level == "none") {
                add("Everything this app does (accessibility, ad blocking by Private DNS) works without root.")
            }
        }

        Report(
            level = level,
            rooted = level == "app" || level == "shell",
            appCanUseRoot = suResult.uid0,
            headline = headline,
            verdict = verdict,
            checks = checks,
            actions = actions,
            checkedAt = System.currentTimeMillis(),
        )
    }

    // --------------------------------------------------------------------- probes

    private data class SuResult(val ran: Boolean, val uid0: Boolean, val detail: String, val exists: Boolean = false)

    /** Runs `su -c id` once with a hard timeout; a grant prompt must not hang the request. */
    private fun trySu(): SuResult {
        val process = try {
            ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        } catch (error: Exception) {
            val reason = error.message.orEmpty()
            val denied = reason.contains("EACCES", true) || reason.contains("Permission denied", true)
            return SuResult(
                ran = true,
                uid0 = false,
                exists = denied,
                detail = when {
                    denied -> "su exists but Android denies this app permission to execute it"
                    reason.contains("ENOENT", true) || reason.contains("No such file", true) -> "su is not on this app's PATH"
                    else -> "Could not start su: ${reason.take(80)}"
                },
            )
        }
        return try {
            if (!process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                SuResult(ran = true, uid0 = false, exists = true, detail = "su did not answer in ${SU_TIMEOUT_MS / 1000}s (waiting on a grant prompt?)")
            } else {
                val out = process.inputStream.bufferedReader().readText().trim()
                if (out.contains("uid=0")) {
                    SuResult(ran = true, uid0 = true, exists = true, detail = "su -c id: ${out.substringBefore(' ')}")
                } else {
                    SuResult(ran = true, uid0 = false, exists = true, detail = "su refused: ${out.lineSequence().firstOrNull().orEmpty().take(80).ifEmpty { "no output" }}")
                }
            }
        } catch (error: Exception) {
            SuResult(ran = true, uid0 = false, exists = true, detail = "su failed: ${error.message.orEmpty().take(80)}")
        } finally {
            process.destroyForcibly()
        }
    }

    private fun prop(key: String): String = runCatching {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(1, TimeUnit.SECONDS)
        process.destroyForcibly()
        text
    }.getOrDefault("")

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** true / false for the mount named, null when /proc/mounts cannot be read or lists no such mount. */
    private fun mountedWritable(mountPoint: String): Boolean? = runCatching {
        File("/proc/self/mounts").readLines()
            .map { it.split(' ') }
            .lastOrNull { it.size > 3 && it[1] == mountPoint }
            ?.let { parts -> parts[3].split(',').contains("rw") }
    }.getOrNull()

    private fun describeModes(paths: List<String>): String {
        val first = File(paths.first())
        val readable = if (first.canExecute()) " (executable by this app)" else " (not executable by this app)"
        return readable
    }
}

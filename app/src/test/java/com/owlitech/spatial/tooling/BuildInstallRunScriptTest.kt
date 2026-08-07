package com.owlitech.spatial.tooling

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInstallRunScriptTest {
    @Test
    fun helpListsParametersWithoutExternalCommands() = Fixture().use { f ->
        val result = f.run("-Help")
        assertEquals(result.output, 0, result.code)
        listOf("-Serial", "-AdbPath", "-NoBuild", "-NoLaunch", "-ClearAppData", "-GrantCameraPermission", "-CaptureLogcat")
            .forEach { assertTrue("Missing $it\n${result.output}", result.output.contains(it)) }
        assertFalse(Files.exists(f.gradleLog)); assertFalse(Files.exists(f.adbLog))
    }

    @Test
    fun unsafeDeviceSelectionsFailClosed() {
        data class Case(val devices: String, val serial: String? = null, val expected: String)
        val cases = listOf(
            Case("", expected = "Exactly one attached online ADB device"),
            Case("SERIAL-ONE\tdevice\nSECOND\tdevice", expected = "Exactly one attached online ADB device"),
            Case("SERIAL-ONE\tdevice", "MISSING", "Requested ADB serial was not found"),
            Case("SERIAL-ONE\toffline", "SERIAL-ONE", "is not online in 'device' state"),
            Case("SERIAL-ONE\tunauthorized", "SERIAL-ONE", "is not online in 'device' state"),
        )
        cases.forEach { case -> Fixture().use { f ->
            val args = mutableListOf("-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            case.serial?.let { args += listOf("-Serial", it) }
            val result = f.run(*args.toTypedArray(), env = mapOf("FAKE_ADB_DEVICES" to case.devices))
            assertTrue(result.output, result.code != 0)
            assertTrue(result.output, result.output.contains(case.expected))
            assertFalse(f.calls().any { "install" in it.args })
        } }
    }

    @Test
    fun oneDeviceBuildsExactApkPrintsEvidenceAndScopesEveryAdbCommand() = Fixture().use { f ->
        val result = f.run("-AdbPath", f.pathAdb.toString(), "-Serial", SERIAL, "-NoLaunch")
        assertEquals(result.output, 0, result.code)
        assertEquals(listOf(":app:assembleDebug"), f.gradleArgs())
        listOf("Git HEAD: $HEAD", "Git worktree: clean", "APK path: ${f.apk}", "APK SHA-256:", "Manufacturer: OwliTest", "Model: Test Device", "Battery level: 73")
            .forEach { assertTrue("Missing $it\n${result.output}", result.output.contains(it)) }
        val calls = f.calls(); assertEquals(listOf("devices", "-l"), calls.first().args)
        calls.drop(1).forEach { assertEquals(listOf("-s", SERIAL), it.args.take(2)) }
        assertTrue(calls.any { it.args == listOf("-s", SERIAL, "install", "-r", f.apk.toString()) })
    }

    @Test
    fun adbDiscoveryPrecedenceIsExplicitSdkRootAndroidHomeThenPath() {
        data class Case(val explicit: Boolean, val sdk: Boolean, val home: Boolean, val expected: String)
        listOf(
            Case(true, true, true, "explicit"), Case(false, true, true, "sdk"),
            Case(false, false, true, "home"), Case(false, false, false, "path"),
        ).forEach { case -> Fixture().use { f ->
            val sdk = f.root.resolve("sdk root"); val home = f.root.resolve("android home")
            if (case.sdk) f.adb(sdk.resolve("platform-tools/adb.exe"), "sdk")
            if (case.home) f.adb(home.resolve("platform-tools/adb.exe"), "home")
            val args = mutableListOf<String>()
            if (case.explicit) args += listOf("-AdbPath", f.adb(f.root.resolve("explicit adb/adb.exe"), "explicit").toString())
            args += "-NoLaunch"
            val result = f.run(*args.toTypedArray(), env = mapOf(
                "ANDROID_SDK_ROOT" to if (case.sdk) sdk.toString() else null,
                "ANDROID_HOME" to if (case.home) home.toString() else null,
            ))
            assertEquals(result.output, 0, result.code); assertEquals(case.expected, f.calls().first().source)
        } }
    }

    @Test
    fun failedBuildDeletesOldApkAndNeverContactsAdb() = Fixture().use { f ->
        Files.createDirectories(f.apk.parent); Files.writeString(f.apk, "old")
        val result = f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch", env = mapOf("FAKE_GRADLE_EXIT" to "7"))
        assertTrue(result.output, result.code != 0); assertFalse(Files.exists(f.apk)); assertTrue(f.calls().isEmpty())
    }

    @Test
    fun noBuildRejectsMissingOrStaleApkAndAcceptsMatchingProvenance() {
        Fixture().use { f ->
            val missing = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            assertTrue(missing.code != 0); assertTrue(missing.output.contains("Expected debug APK does not exist")); assertTrue(f.calls().isEmpty())
        }
        Fixture().use { f ->
            Files.createDirectories(f.apk.parent); Files.writeString(f.apk, "old")
            Files.writeString(f.provenance, """{"SchemaVersion":1,"GitHead":"${"b".repeat(40)}","WorktreeFingerprint":"wrong","ApkSha256":"wrong"}""")
            val stale = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            assertTrue(stale.code != 0); assertTrue(stale.output.contains("APK is stale")); assertTrue(f.calls().isEmpty())
        }
        Fixture().use { f ->
            assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch").code)
            Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog)
            val reused = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            assertEquals(reused.output, 0, reused.code); assertFalse(Files.exists(f.gradleLog)); assertTrue(f.calls().any { "install" in it.args })
        }
    }

    @Test
    fun noBuildRejectsTrackedAndUntrackedDirtyWorktrees() {
        data class Case(val status: String, val diff: String)
        listOf(
            Case(" M tracked.txt\n", "diff --git a/tracked.txt b/tracked.txt\n"),
            Case("?? Scratch.kt\n", ""),
        ).forEach { case -> Fixture().use { f ->
            assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch").code)
            Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog)
            val result = f.run(
                "-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch",
                env = mapOf("FAKE_GIT_STATUS" to case.status, "FAKE_GIT_DIFF" to case.diff),
            )
            assertTrue(result.output, result.code != 0)
            assertTrue(result.output, result.output.contains("worktree is dirty"))
            assertFalse(Files.exists(f.gradleLog)); assertTrue(f.calls().isEmpty())
        } }
    }

    @Test
    fun noBuildRejectsSameUntrackedPathAfterOnlyContentsChange() = Fixture().use { f ->
        val scratch = f.root.resolve("Scratch.kt")
        val dirty = mapOf("FAKE_GIT_STATUS" to "?? Scratch.kt\n", "FAKE_GIT_DIFF" to "")
        Files.writeString(scratch, "first contents")
        assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch", env = dirty).code)
        Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog)

        Files.writeString(scratch, "changed contents only")
        val result = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch", env = dirty)
        assertTrue(result.output, result.code != 0)
        assertTrue(result.output, result.output.contains("worktree is dirty"))
        assertFalse(Files.exists(f.gradleLog)); assertTrue(f.calls().isEmpty())
    }

    @Test
    fun dirtyNoBuildStopsBeforeInstallClearGrantLaunchOrLogcat() = Fixture().use { f ->
        assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch").code)
        Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog)
        val log = f.root.resolve("must not exist/log.txt")
        val result = f.run(
            "-NoBuild", "-AdbPath", f.pathAdb.toString(),
            "-ClearAppData", "-GrantCameraPermission", "-CaptureLogcat", log.toString(),
            env = mapOf("FAKE_GIT_STATUS" to "?? Scratch.kt\n"),
        )
        assertTrue(result.output, result.code != 0)
        assertTrue(result.output, result.output.contains("worktree is dirty"))
        assertFalse(Files.exists(f.gradleLog)); assertTrue(f.calls().isEmpty()); assertFalse(Files.exists(log))
    }

    @Test
    fun noBuildRejectsMissingProvenanceApkHashMismatchAndHeadMismatch() {
        Fixture().use { f ->
            Files.createDirectories(f.apk.parent); Files.writeString(f.apk, "current-apk")
            val result = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            assertTrue(result.output, result.code != 0)
            assertTrue(result.output, result.output.contains("provenance sidecar is missing")); assertTrue(f.calls().isEmpty())
        }
        Fixture().use { f ->
            assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch").code)
            Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog); Files.writeString(f.apk, "tampered-apk")
            val result = f.run("-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch")
            assertTrue(result.output, result.code != 0)
            assertTrue(result.output, result.output.contains("APK is stale")); assertTrue(f.calls().isEmpty())
        }
        Fixture().use { f ->
            assertEquals(0, f.run("-AdbPath", f.pathAdb.toString(), "-NoLaunch").code)
            Files.deleteIfExists(f.gradleLog); Files.deleteIfExists(f.adbLog)
            val result = f.run(
                "-NoBuild", "-AdbPath", f.pathAdb.toString(), "-NoLaunch",
                env = mapOf("FAKE_GIT_HEAD" to "b".repeat(40)),
            )
            assertTrue(result.output, result.code != 0)
            assertTrue(result.output, result.output.contains("APK is stale")); assertTrue(f.calls().isEmpty())
        }
    }

    @Test
    fun installFailurePreventsLaunchAndLaunchFailureIsNonZero() {
        Fixture().use { f ->
            val result = f.run("-AdbPath", f.pathAdb.toString(), env = mapOf("FAKE_ADB_INSTALL_EXIT" to "9"))
            assertTrue(result.output, result.code != 0); assertFalse(f.calls().any { it.args.containsAll(listOf("am", "start")) })
        }
        Fixture().use { f ->
            assertTrue(f.run("-AdbPath", f.pathAdb.toString(), env = mapOf("FAKE_ADB_START_EXIT" to "8")).code != 0)
        }
    }

    @Test
    fun optionalActionsRemainOptInAndLogcatIsBounded() {
        Fixture().use { f ->
            val result = f.run("-AdbPath", f.pathAdb.toString())
            assertEquals(result.output, 0, result.code)
            val text = f.calls().joinToString("\n") { it.args.joinToString(" ") }
            listOf("pm clear", "pm grant", "logcat").forEach { assertFalse(text.contains(it)) }
        }
        Fixture().use { f ->
            val log = f.root.resolve("logs with spaces/owlitech log.txt")
            val result = f.run("-AdbPath", f.pathAdb.toString(), "-ClearAppData", "-GrantCameraPermission", "-CaptureLogcat", log.toString())
            assertEquals(result.output, 0, result.code)
            val text = f.calls().joinToString("\n") { it.args.joinToString(" ") }
            listOf("shell pm clear com.owlitech.spatial", "shell pm grant com.owlitech.spatial android.permission.CAMERA", "logcat -c", "logcat -d -v threadtime --pid=4242")
                .forEach { assertTrue("Missing $it\n$text", text.contains(it)) }
            assertTrue(Files.readString(log).contains("Owli fake logcat"))
        }
    }

    @Test
    fun pathsContainingSpacesArePreserved() = Fixture().use { f ->
        val adb = f.adb(f.root.resolve("adb tools with spaces/adb.exe"), "spaces")
        val log = f.root.resolve("captured logs with spaces/log.txt")
        val result = f.run("-AdbPath", adb.toString(), "-Serial", SERIAL, "-CaptureLogcat", log.toString())
        assertEquals(result.output, 0, result.code); assertEquals("spaces", f.calls().first().source)
        assertTrue(Files.exists(log)); assertTrue(result.output.contains(f.root.toString()))
    }

    private data class Result(val code: Int, val output: String)
    private data class Call(val source: String, val args: List<String>)

    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("owli tooling path with spaces ")
        private val scripts = root.resolve("scripts"); private val bin = root.resolve("fake bin")
        val apk: Path = root.resolve("app/build/outputs/apk/debug/app-debug.apk")
        val provenance: Path = Paths.get("$apk.provenance.json")
        val adbLog: Path = root.resolve("adb.log"); val gradleLog: Path = root.resolve("gradle.log")
        val pathAdb: Path

        init {
            Files.createDirectories(scripts); Files.createDirectories(bin)
            Files.copy(repoRoot().resolve("scripts/Build-Install-Run.ps1"), scripts.resolve("Build-Install-Run.ps1"))
            executable(root.resolve("gradlew.bat"), GRADLE); executable(bin.resolve("git"), GIT)
            pathAdb = adb(bin.resolve("adb.exe"), "path")
        }

        fun adb(path: Path, source: String): Path { executable(path, ADB.replace("__SOURCE__", py(source))); return path }

        fun run(vararg args: String, env: Map<String, String?> = emptyMap()): Result {
            val pb = ProcessBuilder(listOf("pwsh", "-NoProfile", "-File", scripts.resolve("Build-Install-Run.ps1").toString()) + args)
                .directory(root.toFile()).redirectErrorStream(true)
            pb.environment().apply {
                this["PATH"] = "$bin${java.io.File.pathSeparator}${System.getenv("PATH").orEmpty()}"
                remove("ANDROID_SDK_ROOT"); remove("ANDROID_HOME")
                putAll(mapOf("FAKE_GIT_HEAD" to HEAD, "FAKE_GIT_STATUS" to "", "FAKE_GIT_DIFF" to "", "FAKE_ADB_DEVICES" to "$SERIAL\tdevice product:p model:m device:d", "FAKE_ADB_LOG" to adbLog.toString(), "FAKE_GRADLE_LOG" to gradleLog.toString(), "FAKE_GRADLE_EXIT" to "0", "FAKE_ADB_INSTALL_EXIT" to "0", "FAKE_ADB_START_EXIT" to "0"))
                env.forEach { (k, v) -> if (v == null) remove(k) else this[k] = v }
            }
            val process = pb.start(); check(process.waitFor(90, TimeUnit.SECONDS)) { "pwsh timeout" }
            return Result(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
        }

        fun calls(): List<Call> = if (!Files.exists(adbLog)) emptyList() else Files.readAllLines(adbLog).filter { it.isNotBlank() }.map {
            val p = it.split('\t', limit = 2); Call(p[0], if (p.size == 1) emptyList() else p[1].split('\u001f'))
        }
        fun gradleArgs(): List<String> = if (!Files.exists(gradleLog)) emptyList() else Files.readString(gradleLog).trim().split('\u001f')
        override fun close() { if (Files.exists(root)) Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        private fun executable(path: Path, text: String) {
            Files.createDirectories(path.parent); Files.writeString(path, text, StandardCharsets.UTF_8)
            try { Files.setPosixFilePermissions(path, PERMS) } catch (_: UnsupportedOperationException) { path.toFile().setExecutable(true) }
        }
    }

    companion object {
        const val SERIAL = "SERIAL-ONE"; const val HEAD = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val PERMS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)
        fun repoRoot(): Path { var p = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); repeat(8) { if (Files.exists(p.resolve("settings.gradle.kts"))) return p; p = p.parent }; error("repo root") }
        fun py(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

        const val GRADLE = """#!/usr/bin/env python3
import os,pathlib,sys
r=pathlib.Path(__file__).resolve().parent
pathlib.Path(os.environ['FAKE_GRADLE_LOG']).write_text('\x1f'.join(sys.argv[1:]))
e=int(os.environ['FAKE_GRADLE_EXIT'])
if e: print('simulated Gradle failure');sys.exit(e)
a=r/'app/build/outputs/apk/debug/app-debug.apk';a.parent.mkdir(parents=True,exist_ok=True);a.write_bytes(b'current-apk');print('BUILD SUCCESSFUL')
"""
        const val GIT = """#!/usr/bin/env python3
import os,sys
a=sys.argv[1:]
if 'rev-parse' in a: print(os.environ['FAKE_GIT_HEAD'])
elif 'status' in a: print(os.environ.get('FAKE_GIT_STATUS',''),end='')
elif 'diff' in a: print(os.environ.get('FAKE_GIT_DIFF',''),end='')
else: sys.exit(2)
"""
        const val ADB = """#!/usr/bin/env python3
import os,pathlib,sys
s=__SOURCE__;a=sys.argv[1:]
with pathlib.Path(os.environ['FAKE_ADB_LOG']).open('a') as f:f.write(s+'\t'+'\x1f'.join(a)+'\n')
if a==['devices','-l']: print('List of devices attached');print(os.environ.get('FAKE_ADB_DEVICES',''));sys.exit(0)
if len(a)<3 or a[0]!='-s':sys.exit(91)
r=a[2:]
if r[:2]==['shell','getprop']:
 v={'ro.product.manufacturer':'OwliTest','ro.product.model':'Test Device','ro.product.name':'owli_product','ro.product.device':'owli_device','ro.build.version.release':'15','ro.build.version.sdk':'35','ro.build.fingerprint':'owli/test:15/build'};print(v.get(r[2],''));sys.exit(0)
if r==['shell','dumpsys','battery']:print('level: 73\nstatus: 2');sys.exit(0)
if r[:2]==['install','-r']:
 e=int(os.environ['FAKE_ADB_INSTALL_EXIT']);print('Success' if not e else 'Failure [TEST]');sys.exit(e)
if r==['shell','pm','clear','com.owlitech.spatial']:print('Success');sys.exit(0)
if r==['shell','pm','grant','com.owlitech.spatial','android.permission.CAMERA']:sys.exit(0)
if r[:4]==['shell','am','start','-W']:
 e=int(os.environ['FAKE_ADB_START_EXIT']);print('Status: ok' if not e else 'Error: launch');sys.exit(e)
if r==['shell','pidof','com.owlitech.spatial']:print('4242');sys.exit(0)
if r==['logcat','-c']:sys.exit(0)
if r[:4]==['logcat','-d','-v','threadtime']:print('Owli fake logcat');sys.exit(0)
sys.exit(92)
"""
    }
}

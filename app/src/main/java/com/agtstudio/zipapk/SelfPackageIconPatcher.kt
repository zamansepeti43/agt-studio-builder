package com.agtstudio.zipapk

import com.iappyx.container.ApkInjector
import com.iappyx.container.KeyManager
import java.io.File
import java.util.zip.ZipFile

/** Rebuilds a self-package APK with the user's selected logo and signs it. */
object SelfPackageIconPatcher {
    fun patchAndSign(inputApk: File, outputApk: File, logoBytes: ByteArray) {
        val injector = ApkInjector(KeyManager.KEY_ALIAS)

        val read = ApkInjector::class.java.getDeclaredMethod("readApk", File::class.java).apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val entries = read.invoke(injector, inputApk) as LinkedHashMap<String, ByteArray>

        val iconPaths = entries.keys.filter {
            it.startsWith("res/drawable") && it.endsWith("/notify_panel_notification_icon_bg.png")
        }
        require(iconPaths.isNotEmpty()) { "APK icon resource bulunamadı." }
        iconPaths.forEach { entries[it] = logoBytes }

        entries.keys.removeAll { it.startsWith("META-INF/") }

        val unsigned = File(outputApk.parentFile, "unsigned_${outputApk.name}")
        val write = ApkInjector::class.java.getDeclaredMethod(
            "writeApkAligned", Map::class.java, File::class.java
        ).apply { isAccessible = true }
        write.invoke(injector, entries, unsigned)

        val sign = ApkInjector::class.java.getDeclaredMethod(
            "applyV2Signature", File::class.java, File::class.java
        ).apply { isAccessible = true }
        sign.invoke(injector, unsigned, outputApk)
        unsigned.delete()

        ZipFile(outputApk).use { zip ->
            require(zip.entries().asSequence().any {
                it.name.endsWith("/notify_panel_notification_icon_bg.png") &&
                    zip.getInputStream(it).readBytes().contentEquals(logoBytes)
            }) { "Logo APK içine yazılamadı." }
        }
    }
}

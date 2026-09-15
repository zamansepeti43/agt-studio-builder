package com.agtstudio.zipapk

import com.iappyx.container.ApkInjector
import com.iappyx.container.KeyManager
import java.io.File
import java.util.zip.ZipFile

/** Rebuilds a self-package APK with the user's selected logo and app name, then signs it. */
object SelfPackageIconPatcher {
    private const val BUILDER_LABEL = "AGT Studio APK Oluşturucu"

    fun patchAndSign(inputApk: File, outputApk: File, logoBytes: ByteArray, appLabel: String) {
        val injector = ApkInjector(KeyManager.KEY_ALIAS)

        val read = ApkInjector::class.java.getDeclaredMethod("readApk", File::class.java).apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val entries = read.invoke(injector, inputApk) as LinkedHashMap<String, ByteArray>

        val manifest = entries["AndroidManifest.xml"]
            ?: error("APK AndroidManifest.xml bulunamadı.")

        // The self-package is the Builder APK itself, so unlike a normal HTML build
        // it does not pass through ApkInjector.patchManifest(). Patch the Builder's
        // own launcher label directly so the name entered by the user is the actual
        // installed APK name (without the AGT Studio prefix).
        val replaceLabel = ApkInjector::class.java.getDeclaredMethod(
            "replaceAllUtf16", ByteArray::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        entries["AndroidManifest.xml"] = replaceLabel.invoke(
            injector,
            manifest,
            BUILDER_LABEL,
            appLabel
        ) as ByteArray

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

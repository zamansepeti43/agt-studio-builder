package com.agtstudio.zipapk

import android.content.Context
import android.net.Uri
import com.iappyx.container.ApkInjector
import com.iappyx.container.KeyManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkBuildEngine(private val context: Context) {
    suspend fun build(zipUri: Uri, appName: String, logoUri: Uri?, onProgress: (String) -> Unit): File {
        val safeName = appName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "AGT_App" }
        val packageName = "com.agtstudio.generated." + safeName.lowercase().replace(Regex("[^a-z0-9]"), "").take(18).ifBlank { "app" }
        val work = File(context.cacheDir, "agt_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onProgress("ZIP okunuyor…")
            val input = File(work, "input.zip")
            context.contentResolver.openInputStream(zipUri)!!.use { i -> FileOutputStream(input).use { o -> i.copyTo(o) } }
            val web = File(work, "web").apply { mkdirs() }
            extractSafely(input, web)
            val index = web.walkTopDown().firstOrNull { it.isFile && it.name.equals("index.html", true) }
                ?: error("ZIP içinde index.html bulunamadı.")
            val root = index.parentFile ?: web
            val assets = linkedMapOf<String, ByteArray>()
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                assets[rel] = f.readBytes()
            }
            if (logoUri != null) {
                onProgress("Logo ekleniyor…")
                context.contentResolver.openInputStream(logoUri)?.use { assets["agt-logo.png"] = it.readBytes() }
            }
            val template = File(work, "shell_template.apk")
            context.assets.open("shell_template.apk").use { i -> FileOutputStream(template).use { o -> i.copyTo(o) } }
            KeyManager.ensureKeyExists(context)
            val outDir = File(context.getExternalFilesDir(null), "AGT Studio/APKs").apply { mkdirs() }
            val out = File(outDir, "$safeName.apk")
            onProgress("APK oluşturuluyor ve imzalanıyor…")
            ApkInjector(KeyManager.KEY_ALIAS).inject(template, out, packageName, safeName, assets)
            onProgress("APK hazır: ${out.name}")
            return out
        } finally { work.deleteRecursively() }
    }

    private fun extractSafely(zip: File, dest: File) {
        ZipFile(zip).use { z ->
            val root = dest.canonicalFile
            val e = z.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                val target = File(root, entry.name).canonicalFile
                require(target.path == root.path || target.path.startsWith(root.path + File.separator)) { "ZIP içinde güvensiz yol bulundu." }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    z.getInputStream(entry).use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
                }
            }
        }
    }
}

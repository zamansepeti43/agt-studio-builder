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
            context.contentResolver.openInputStream(zipUri)?.use { i ->
                FileOutputStream(input).use { o -> i.copyTo(o) }
            } ?: error("ZIP dosyası okunamadı.")

            val web = File(work, "web").apply { mkdirs() }
            extractSafely(input, web)

            // Accept normal website ZIPs, nested project folders, index.htm, and
            // archives that contain another website ZIP. The generated shell
            // always receives a canonical index.html at the selected web root.
            var index = findEntryHtml(web)
            if (index == null) {
                val nestedZip = web.walkTopDown()
                    .firstOrNull { it.isFile && it.extension.equals("zip", true) }
                if (nestedZip != null) {
                    val nestedDir = File(work, "nested").apply { mkdirs() }
                    extractSafely(nestedZip, nestedDir)
                    index = findEntryHtml(nestedDir)
                    if (index != null) {
                        web.deleteRecursively()
                        nestedDir.copyRecursively(web, overwrite = true)
                        index = findEntryHtml(web)
                    }
                }
            }

            val entry = index ?: error("ZIP içinde HTML sayfası bulunamadı. index.html veya index.htm bulunmalı.")
            val root = entry.parentFile ?: web
            val canonicalIndex = File(root, "index.html")
            if (entry.name != "index.html") {
                entry.copyTo(canonicalIndex, overwrite = true)
            }

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
            context.assets.open("shell_template.apk").use { i ->
                FileOutputStream(template).use { o -> i.copyTo(o) }
            }

            KeyManager.ensureKeyExists(context)
            val outDir = File(context.getExternalFilesDir(null), "AGT Studio/APKs").apply { mkdirs() }
            val out = File(outDir, "$safeName.apk")
            onProgress("APK oluşturuluyor ve imzalanıyor…")
            ApkInjector(KeyManager.KEY_ALIAS).inject(template, out, packageName, safeName, assets)
            onProgress("APK hazır: ${out.name}")
            return out
        } finally {
            work.deleteRecursively()
        }
    }

    private fun findEntryHtml(root: File): File? {
        val preferred = root.walkTopDown()
            .filter { it.isFile }
            .firstOrNull { it.name.equals("index.html", true) || it.name.equals("index.htm", true) }
        if (preferred != null) return preferred

        // Last-resort compatibility: accept the first HTML document in the ZIP
        // so older/simple web projects without an index filename can still build.
        return root.walkTopDown()
            .filter { it.isFile }
            .firstOrNull { it.extension.equals("html", true) || it.extension.equals("htm", true) }
    }

    private fun extractSafely(zip: File, dest: File) {
        ZipFile(zip).use { z ->
            val root = dest.canonicalFile
            val e = z.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                val target = File(root, entry.name).canonicalFile
                require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                    "ZIP içinde güvensiz yol bulundu."
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    z.getInputStream(entry).use { i ->
                        FileOutputStream(target).use { o -> i.copyTo(o) }
                    }
                }
            }
        }
    }
}

package com.agtstudio.zipapk

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.iappyx.container.ApkInjector
import com.iappyx.container.KeyManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile

class ApkBuildEngine(private val context: Context) {
    suspend fun build(zipUri: Uri, appName: String, logoUri: Uri?, onProgress: (String) -> Unit): File {
        val displayName = appName.trim().take(37).ifBlank { "AGT Uygulama" }
        val fileName = displayName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(60)
            .ifBlank { "AGT Uygulama" }

        val work = File(context.cacheDir, "agt_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onProgress("ZIP okunuyor…")
            val input = File(work, "input.zip")
            context.contentResolver.openInputStream(zipUri)?.use { i ->
                FileOutputStream(input).use { o -> i.copyTo(o) }
            } ?: error("ZIP dosyası okunamadı.")

            // AGT Studio self-package: preserve the native builder while allowing
            // the selected logo to become its launcher icon.
            val selfApk = findSelfApk(input, work)
            if (selfApk != null) {
                onProgress("AGT Studio paketi algılandı…")
                val generated = File(work, "$fileName.apk")
                if (logoUri != null) {
                    onProgress("Seçilen logo APK'ya uygulanıyor…")
                    val logoBytes = createIconPng(logoUri)
                    KeyManager.ensureKeyExists(context)
                    SelfPackageIconPatcher.patchAndSign(selfApk, generated, logoBytes)
                } else {
                    selfApk.inputStream().use { source -> generated.outputStream().use { target -> source.copyTo(target) } }
                }
                return saveApkToDownloads(generated, fileName, onProgress)
            }

            val displayNamePackage = displayName
                .replace("ç", "c").replace("Ç", "c")
                .replace("ğ", "g").replace("Ğ", "g")
                .replace("ı", "i").replace("İ", "i")
                .replace("ö", "o").replace("Ö", "o")
                .replace("ş", "s").replace("Ş", "s")
                .replace("ü", "u").replace("Ü", "u")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
                .take(18)
                .ifBlank { "app" }
            val packageName = "com.agtstudio.generated.$displayNamePackage"

            val web = File(work, "web").apply { mkdirs() }
            extractSafely(input, web)

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
            if (entry.name != "index.html") entry.copyTo(canonicalIndex, overwrite = true)

            val assets = linkedMapOf<String, ByteArray>()
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                assets[rel] = f.readBytes()
            }

            val icons = linkedMapOf<String, ByteArray>()
            if (logoUri != null) {
                onProgress("Logo hazırlanıyor…")
                val logoBytes = createIconPng(logoUri)
                assets["agt-logo.png"] = logoBytes
                val iconPaths = listOf(
                    "res/mipmap-mdpi-v4/ic_launcher.png",
                    "res/mipmap-hdpi-v4/ic_launcher.png",
                    "res/mipmap-xhdpi-v4/ic_launcher.png",
                    "res/mipmap-xxhdpi-v4/ic_launcher.png",
                    "res/mipmap-xxxhdpi-v4/ic_launcher.png"
                )
                iconPaths.forEach { icons[it] = logoBytes }
            }

            val template = File(work, "shell_template.apk")
            context.assets.open("shell_template.apk").use { i ->
                FileOutputStream(template).use { o -> i.copyTo(o) }
            }

            KeyManager.ensureKeyExists(context)
            val generated = File(work, "$fileName.apk")
            onProgress("APK oluşturuluyor ve imzalanıyor…")
            ApkInjector(KeyManager.KEY_ALIAS).inject(
                template,
                generated,
                packageName,
                displayName,
                assets,
                icons
            )

            return saveApkToDownloads(generated, fileName, onProgress)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun findSelfApk(input: File, work: File): File? {
        ZipFile(input).use { zip ->
            val names = listOf(
                "AGT-Studio-Builder.apk",
                "agt-studio-builder.apk",
                "payload/AGT-Studio-Builder.apk",
                "payload/agt-studio-builder.apk"
            )
            for (name in names) {
                val entry = zip.getEntry(name) ?: continue
                val out = File(work, "self-builder.apk")
                zip.getInputStream(entry).use { source -> out.outputStream().use { target -> source.copyTo(target) } }
                return out
            }
            val apkEntry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.lowercase(Locale.ROOT).endsWith(".apk") &&
                    (it.name.substringBeforeLast('/').isEmpty() || it.name.substringBeforeLast('/').equals("payload", true))
            }
            if (apkEntry != null) {
                val out = File(work, "self-builder.apk")
                zip.getInputStream(apkEntry).use { source -> out.outputStream().use { target -> source.copyTo(target) } }
                return out
            }
        }
        return null
    }

    private fun saveApkToDownloads(generated: File, fileName: String, onProgress: (String) -> Unit): File {
        onProgress("APK Download klasörüne kaydediliyor…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.apk")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AGT Studio/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("APK Download klasörüne kaydedilemedi.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    generated.inputStream().use { input -> input.copyTo(output) }
                } ?: error("APK dosyası yazılamadı.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                onProgress("✓ APK hazır: Download/AGT Studio/$fileName.apk")
                return generated
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AGT Studio").apply { mkdirs() }
            val out = File(outDir, "$fileName.apk")
            generated.inputStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            onProgress("✓ APK hazır: Download/AGT Studio/$fileName.apk")
            return out
        }
    }

    private fun createIconPng(uri: Uri): ByteArray {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Logo okunamadı.")
        val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(square)
        val scale = minOf(512f / bitmap.width, 512f / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (512f - w) / 2f
        val top = (512f - h) / 2f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), null)
        if (square !== bitmap) bitmap.recycle()
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.PNG, 100, out)
            square.recycle()
            out.toByteArray()
        }
    }

    private fun findEntryHtml(root: File): File? {
        val preferred = root.walkTopDown().filter { it.isFile }
            .firstOrNull { it.name.equals("index.html", true) || it.name.equals("index.htm", true) }
        if (preferred != null) return preferred
        return root.walkTopDown().filter { it.isFile }
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

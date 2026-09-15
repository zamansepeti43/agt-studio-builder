package com.agtstudio.zipapk

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var txtZip: TextView
    private lateinit var txtLogo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var edtName: EditText
    private lateinit var btnShare: TextView
    private var zipUri: Uri? = null
    private var logoUri: Uri? = null
    private var lastApkFile: File? = null
    private var lastApkName: String? = null

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val fileName = getDisplayName(uri)
        if (!fileName.lowercase().endsWith(".zip")) {
            zipUri = null
            txtZip.text = "Henüz ZIP seçilmedi"
            txtStatus.text = "Lütfen yalnızca .ZIP dosyası seç."
            toast("Bu dosya ZIP değil. Lütfen .zip dosyası seç.")
            return@registerForActivityResult
        }
        zipUri = uri
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val size = getDisplaySize(uri)
        txtZip.text = if (size != null) "Seçildi: $fileName • $size" else "Seçildi: $fileName"
        txtStatus.text = "ZIP hazır. Şimdi isim ve logo seçebilirsin."
    }

    private val logoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        logoUri = uri
        findViewById<ImageView>(R.id.imgLogo).setImageURI(uri)
        txtLogo.text = getDisplayName(uri)
        txtStatus.text = "Logo hazır."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtZip = findViewById(R.id.txtZip)
        txtLogo = findViewById(R.id.txtLogo)
        txtStatus = findViewById(R.id.txtStatus)
        progress = findViewById(R.id.progress)
        edtName = findViewById(R.id.edtAppName)
        btnShare = findViewById(R.id.btnShare)

        findViewById<View>(R.id.btnPickZip).setOnClickListener {
            zipPicker.launch(arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
            ))
        }
        findViewById<View>(R.id.btnPickLogo).setOnClickListener {
            logoPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
        }
        findViewById<View>(R.id.btnCreate).setOnClickListener { createApk() }
        btnShare.setOnClickListener { shareLastApk() }
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Seçilen dosya"
    }

    private fun getDisplaySize(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                val bytes = cursor.getLong(sizeIndex)
                return when {
                    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
                    else -> "$bytes B"
                }
            }
        }
        return null
    }

    private fun createApk() {
        val zip = zipUri
        val name = edtName.text.toString().trim()
        if (zip == null) { toast("Önce bir ZIP dosyası seç."); return }
        val selectedName = getDisplayName(zip)
        if (!selectedName.lowercase().endsWith(".zip")) {
            zipUri = null
            txtZip.text = "Henüz ZIP seçilmedi"
            toast("Lütfen yalnızca .zip dosyası seç.")
            return
        }
        if (name.isBlank()) { edtName.error = "Uygulama adını gir."; return }

        btnShare.visibility = View.GONE
        progress.visibility = View.VISIBLE
        txtStatus.text = "ZIP kontrol ediliyor…"
        lifecycleScope.launch {
            try {
                val result = ApkBuildEngine(this@MainActivity).build(zip, name, logoUri) { msg ->
                    runOnUiThread { txtStatus.text = msg }
                }
                lastApkFile = result
                lastApkName = result.name
                progress.visibility = View.GONE
                txtStatus.text = "✓ APK başarıyla kaydedildi\nDownload/AGT Studio/${result.name}"
                btnShare.visibility = View.VISIBLE
                btnShare.isEnabled = true
                toast("APK kaydedildi. Aşağıdaki PAYLAŞ butonunu kullanabilirsin.")
            } catch (e: Exception) {
                progress.visibility = View.GONE
                btnShare.visibility = View.GONE
                txtStatus.text = "Hata: ${e.message ?: "Bilinmeyen hata"}"
                toast(e.message ?: "APK oluşturulamadı.")
            }
        }
    }

    private fun shareLastApk() {
        val name = lastApkName
        if (name.isNullOrBlank()) {
            toast("Önce APK oluştur.")
            return
        }
        val uri = findDownloadedApkUri(name) ?: lastApkFile?.let { file ->
            if (file.exists()) {
                FileProvider.getUriForFile(this, "com.agtstudio.zipapk.fileprovider", file)
            } else null
        }
        if (uri == null) {
            toast("APK dosyası bulunamadı. Download/AGT Studio klasörünü kontrol et.")
            txtStatus.text = "APK bulunamadı. Lütfen yeniden oluştur."
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "APK paylaş"))
        } catch (e: Exception) {
            toast("Paylaşım açılamadı: ${e.message ?: "Bilinmeyen hata"}")
        }
    }

    private fun findDownloadedApkUri(fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/AGT Studio/")
        contentResolver.query(collection, projection, selection, args, "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
            }
        }
        return null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

package com.agtstudio.zipapk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var txtZip: TextView
    private lateinit var txtLogo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var edtName: EditText
    private var zipUri: Uri? = null
    private var logoUri: Uri? = null

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        zipUri = uri
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val fileName = getDisplayName(uri)
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

        findViewById<View>(R.id.btnPickZip).setOnClickListener {
            zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*"))
        }
        findViewById<View>(R.id.btnPickLogo).setOnClickListener {
            logoPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
        }
        findViewById<View>(R.id.btnCreate).setOnClickListener { createApk() }
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
        if (zip == null) { toast("Önce ZIP dosyasını seç."); return }
        if (name.isBlank()) { edtName.error = "Uygulama adını gir."; return }

        progress.visibility = View.VISIBLE
        txtStatus.text = "ZIP kontrol ediliyor…"
        lifecycleScope.launch {
            try {
                val result = ApkBuildEngine(this@MainActivity).build(zip, name, logoUri) { msg ->
                    runOnUiThread { txtStatus.text = msg }
                }
                progress.visibility = View.GONE
                txtStatus.text = "✓ APK hazır: ${result.name}"
                toast("APK oluşturuldu. Dosya: ${result.absolutePath}")
            } catch (e: Exception) {
                progress.visibility = View.GONE
                txtStatus.text = "Hata: ${e.message ?: "Bilinmeyen hata"}"
                toast(e.message ?: "APK oluşturulamadı.")
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

package com.medvault.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    private var isSignedIn = false
    
    // File chooser support
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var selectedReportUris: List<Uri> = emptyList()
    private var cameraImageUri: Uri? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1
    private val PERMISSION_REQUEST_CODE = 100
    private val CONFIRM_DEVICE_CREDENTIAL_REQUEST_CODE = 2
    
    companion object {
        private const val RC_SIGN_IN = 9001
        private const val MEDVAULT_FOLDER = "MedVault_Data"
        private const val REPORTS_FOLDER = "Reports"
        private const val DATA_FILE = "patient_records.json"
    }

    private class RemoteChangedException : Exception("REMOTE_CHANGED")

    private data class DriveDataSnapshot(
        val data: String?,
        val revision: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request necessary permissions
        requestPermissions()

        // Setup Google Sign In
        setupGoogleSignIn()

        // Setup WebView
        setupWebView()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    @Throws(java.io.IOException::class)
    private fun createImageFile(): JavaFile {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: JavaFile = externalCacheDir ?: cacheDir
        return JavaFile.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Enable media features
            mediaPlaybackRequiresUserGesture = false
            
            // Enable caching
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // Enable zoom
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // Add JavaScript interface for Android storage
        webView.addJavascriptInterface(AndroidStorage(), "AndroidStorage")
        
        // Add JavaScript interface for Google Drive
        webView.addJavascriptInterface(GoogleDriveInterface(), "GoogleDrive")

        // Add JavaScript interface for Biometrics
        webView.addJavascriptInterface(BiometricInterface(), "BiometricAuth")

        // Set WebView client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "tel") {
                    openDialer(uri)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Check if user is already signed in
                val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (account != null) {
                    initializeDriveService(account)
                    notifyDriveReady(true)
                } else {
                    notifyDriveReady(false)
                }
            }
        }

        // Set WebChrome client for permissions and file chooser
        webView.webChromeClient = object : WebChromeClient() {
            // Handle permission requests (microphone, camera, etc.)
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // Handle file chooser for Android 5.0+
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing file chooser
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                this@MainActivity.cameraImageUri = null

                // Set up camera intent
                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    var photoFile: JavaFile? = null
                    try {
                        photoFile = createImageFile()
                    } catch (ex: java.io.IOException) {
                        android.util.Log.e("MainActivity", "Unable to create Image File", ex)
                    }

                    if (photoFile != null) {
                        val authorities = "$packageName.fileprovider"
                        val photoURI = FileProvider.getUriForFile(
                            this@MainActivity,
                            authorities,
                            photoFile
                        )
                        this@MainActivity.cameraImageUri = photoURI
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    } else {
                        takePictureIntent = null
                    }
                } else {
                    takePictureIntent = null
                }

                // Set up selection intent (document chooser)
                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val intentArray: Array<Intent> = if (takePictureIntent != null) {
                    arrayOf(takePictureIntent)
                } else {
                    emptyArray()
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select Source")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                }

                val useCameraDirectly = fileChooserParams?.isCaptureEnabled == true && takePictureIntent != null

                try {
                    if (useCameraDirectly) {
                        startActivityForResult(takePictureIntent!!, FILE_CHOOSER_REQUEST_CODE)
                    } else {
                        startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                    }
                    return true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    this@MainActivity.cameraImageUri = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                    return false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("WebView", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("MedVault")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result?.cancel()
                    }
                    .setOnCancelListener {
                        result?.cancel()
                    }
                    .show()
                return true
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("MedVault")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm()
                    }
                    .setOnCancelListener {
                        result?.confirm()
                    }
                    .show()
                return true
            }
        }

        // Load the HTML file
        webView.loadUrl("file:///android_asset/public/index.html")
    }

    private fun openDialer(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open phone dialer", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle file chooser result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RC_SIGN_IN -> {
                val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
                handleSignInResult(task)
            }
            CONFIRM_DEVICE_CREDENTIAL_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    notifyBiometricStatus(true, "Authentication succeeded")
                } else {
                    notifyBiometricStatus(false, "Authentication failed")
                }
            }
            FILE_CHOOSER_REQUEST_CODE -> {
                if (filePathCallback == null) return

                val results = if (resultCode == Activity.RESULT_OK) {
                    if (data?.clipData != null) {
                        // Multiple files selected
                        val count = data.clipData!!.itemCount
                        Array(count) { i ->
                            data.clipData!!.getItemAt(i).uri
                        }
                    } else if (data?.data != null) {
                        // Single file selected
                        arrayOf(data.data!!)
                    } else if (cameraImageUri != null) {
                        // Camera capture
                        arrayOf(cameraImageUri!!)
                    } else {
                        null
                    }
                } else {
                    null
                }

                selectedReportUris = results?.toList() ?: emptyList()
                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
                cameraImageUri = null // Reset camera image URI
            }
        }
    }

    inner class GoogleDriveInterface {
        @JavascriptInterface
        fun isSignedIn(): Boolean {
            return isSignedIn
        }

        @JavascriptInterface
        fun signIn() {
            runOnUiThread {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Cannot open report link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun syncToCloud(dataJson: String, expectedRevision: String?) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    uploadToGoogleDrive(dataJson, expectedRevision)
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(true, "Data uploaded to Google Drive")
                    }
                } catch (e: RemoteChangedException) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "REMOTE_CHANGED")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "Upload failed: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun syncFromCloud() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val data = downloadFromGoogleDrive()
                    withContext(Dispatchers.Main) {
                        notifyDataReceived(data)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "Download failed: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun uploadReport(
            requestId: String,
            patientId: String,
            originalName: String,
            mimeType: String,
            base64Content: String
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val report = uploadReportToGoogleDrive(patientId, originalName, mimeType, base64Content)
                    report.put("requestId", requestId)
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(true, report.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report upload failed")
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(false, payload.toString())
                    }
                }
            }
        }

        @JavascriptInterface
        fun getSelectedReportCount(): Int {
            return selectedReportUris.size
        }

        @JavascriptInterface
        fun uploadSelectedReports(requestId: String, patientId: String) {
            val uris = selectedReportUris.toList()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reports = uploadSelectedReportUrisToGoogleDrive(patientId, uris)
                    selectedReportUris = emptyList()
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("reports", reports)
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(true, payload.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report upload failed")
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(false, payload.toString())
                    }
                }
            }
        }

        @JavascriptInterface
        fun deleteReport(requestId: String, fileId: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    deleteReportFromGoogleDrive(fileId)
                    val payload = JSONObject().put("requestId", requestId)
                    withContext(Dispatchers.Main) {
                        notifyReportDeleteComplete(true, payload.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report delete failed")
                    withContext(Dispatchers.Main) {
                        notifyReportDeleteComplete(false, payload.toString())
                    }
                }
            }
        }

        @JavascriptInterface
        fun exportPdf(fileName: String, payloadJson: String) {
            runOnUiThread {
                exportPrescriptionPdf(fileName, payloadJson)
            }
        }
    }

    inner class AndroidStorage {
        @JavascriptInterface
        fun setItem(key: String, value: String) {
            getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .edit()
                .putString(key, value)
                .apply()
        }

        @JavascriptInterface
        fun getItem(key: String): String? {
            return getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .getString(key, null)
        }

        @JavascriptInterface
        fun removeItem(key: String) {
            getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply()
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread {
                this@MainActivity.finish()
            }
        }
    }

    inner class BiometricInterface {
        @JavascriptInterface
        fun isBiometricAvailable(): Boolean {
            val biometricManager = BiometricManager.from(this@MainActivity)
            val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG
            return biometricManager.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
        }

        @JavascriptInterface
        fun isDeviceSecure(): Boolean {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            return keyguardManager.isDeviceSecure
        }

        @JavascriptInterface
        fun authenticate() {
            runOnUiThread {
                showBiometricPrompt()
            }
        }

        @JavascriptInterface
        fun authenticateWithPattern() {
            runOnUiThread {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                    "MedVault Unlock",
                    "Please draw your pattern or enter your PIN/password to log in"
                )
                if (intent != null) {
                    startActivityForResult(intent, CONFIRM_DEVICE_CREDENTIAL_REQUEST_CODE)
                } else {
                    notifyBiometricStatus(false, "Confirm Device Credential not available")
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    notifyBiometricStatus(false, errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    notifyBiometricStatus(true, "Authentication succeeded")
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    notifyBiometricStatus(false, "Authentication failed")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MedVault Secure Unlock")
            .setSubtitle("Log in using biometric authentication")
            .setNegativeButtonText("Use PIN / OTP")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun notifyBiometricStatus(success: Boolean, message: String) {
        webView.post {
            webView.evaluateJavascript("javascript:onBiometricResult($success, '$message')", null)
        }
    }

    private data class PdfOutput(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val isPendingMediaStoreItem: Boolean
    )

    private fun exportPrescriptionPdf(fileName: String, payloadJson: String) {
        val safeName = buildPdfFileName(fileName)
        val output = try {
            createPdfOutput(safeName)
        } catch (e: Exception) {
            notifyPdfExportComplete(false, "Could not create PDF file: ${e.message}")
            Toast.makeText(this@MainActivity, "Could not create PDF file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val payload = JSONObject(payloadJson)
            val document = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 42f
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            var y = margin

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(10, 25, 41)
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 100, 100)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(95, 95, 95)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25, 25, 25)
                textSize = 12f
            }
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(65, 65, 65)
                textSize = 10.5f
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 210, 210)
                strokeWidth = 1f
            }

            fun newPage() {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }

            fun ensureSpace(height: Float) {
                if (y + height > pageHeight - margin) newPage()
            }

            fun drawWrappedText(text: String, x: Float, paint: Paint, maxWidth: Float, lineHeight: Float) {
                val words = text.replace("\r", "").split(Regex("\\s+"))
                var line = ""
                if (words.isEmpty()) {
                    ensureSpace(lineHeight)
                    canvas.drawText("", x, y, paint)
                    y += lineHeight
                    return
                }
                words.forEach { word ->
                    val candidate = if (line.isBlank()) word else "$line $word"
                    if (paint.measureText(candidate) <= maxWidth) {
                        line = candidate
                    } else {
                        ensureSpace(lineHeight)
                        canvas.drawText(line, x, y, paint)
                        y += lineHeight
                        line = word
                    }
                }
                if (line.isNotBlank()) {
                    ensureSpace(lineHeight)
                    canvas.drawText(line, x, y, paint)
                    y += lineHeight
                }
            }

            fun drawField(label: String, value: String, x: Float, width: Float) {
                ensureSpace(34f)
                canvas.drawText(label.uppercase(Locale.US), x, y, labelPaint)
                y += 15f
                drawWrappedText(value.ifBlank { "N/A" }, x, bodyPaint, width, 15f)
            }

            fun drawSection(title: String) {
                ensureSpace(32f)
                y += 8f
                canvas.drawText(title.uppercase(Locale.US), margin, y, headingPaint)
                y += 8f
                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
                y += 18f
            }

            canvas.drawText("Prescription", margin, y, titlePaint)
            canvas.drawText("MedVault Patient Record", pageWidth - margin - smallPaint.measureText("MedVault Patient Record"), y, smallPaint)
            y += 22f
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 28f

            val leftWidth = 240f
            val rightX = 320f
            val rowStart = y
            drawField("Patient Name", payload.optString("patientName", "N/A"), margin, leftWidth)
            y = rowStart
            drawField("Date of Visit", payload.optString("dateOfVisit", "N/A"), rightX, 210f)
            y += 8f
            val rowTwoStart = y
            drawField("Phone Number", payload.optString("phoneNumber", "N/A"), margin, leftWidth)
            y = rowTwoStart
            drawField("Patient ID", payload.optString("patientId", "N/A"), rightX, 210f)

            drawSection("Diagnosis")
            drawWrappedText(payload.optString("diagnosis", "N/A"), margin, bodyPaint, pageWidth - (margin * 2), 16f)

            drawSection("Medicines")
            drawWrappedText(payload.optString("medicines", "Not prescribed yet"), margin, bodyPaint, pageWidth - (margin * 2), 16f)

            drawSection("Prescription Details")
            val prescriptions = payload.optJSONArray("prescriptions") ?: JSONArray()
            if (prescriptions.length() == 0) {
                drawWrappedText("No structured prescriptions added", margin, bodyPaint, pageWidth - (margin * 2), 16f)
            } else {
                for (i in 0 until prescriptions.length()) {
                    val item = prescriptions.optJSONObject(i) ?: JSONObject()
                    ensureSpace(46f)
                    canvas.drawText("${i + 1}.", margin, y, bodyPaint)
                    drawWrappedText(item.optString("medicine", "Medicine"), margin + 24f, bodyPaint, pageWidth - margin * 2 - 24f, 16f)
                    val meta = item.optString("meta", "")
                    if (meta.isNotBlank()) drawWrappedText(meta, margin + 24f, smallPaint, pageWidth - margin * 2 - 24f, 14f)
                    val instructions = item.optString("instructions", "")
                    if (instructions.isNotBlank()) drawWrappedText(instructions, margin + 24f, smallPaint, pageWidth - margin * 2 - 24f, 14f)
                    y += 8f
                }
            }

            ensureSpace(72f)
            y += 42f
            canvas.drawLine(pageWidth - margin - 180f, y, pageWidth - margin, y, linePaint)
            y += 15f
            canvas.drawText("Doctor Signature", pageWidth - margin - 145f, y, smallPaint)

            document.finishPage(page)
            document.writeTo(java.io.FileOutputStream(output.descriptor.fileDescriptor))
            document.close()
            output.descriptor.close()
            completePdfOutput(output, true)
            sharePdf(output.uri, safeName)
            notifyPdfExportComplete(true, "PDF ready to export")
        } catch (e: Exception) {
            try {
                output.descriptor.close()
            } catch (_: Exception) {
            }
            completePdfOutput(output, false)
            notifyPdfExportComplete(false, e.message ?: "PDF export failed")
            Toast.makeText(this@MainActivity, "PDF export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPdfOutput(fileName: String): PdfOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MedVault")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Downloads storage is not available")
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: throw Exception("Could not open PDF file")
            return PdfOutput(uri, descriptor, true)
        }

        val directory = JavaFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MedVault")
        if (!directory.exists() && !directory.mkdirs()) {
            throw Exception("Could not create Downloads/MedVault")
        }
        val file = JavaFile(directory, fileName)
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        return PdfOutput(uri, descriptor, false)
    }

    private fun sharePdf(uri: Uri, fileName: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newUri(contentResolver, fileName, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Prescription PDF")
            putExtra(Intent.EXTRA_TITLE, fileName)
            putExtra(Intent.EXTRA_TEXT, "Prescription PDF exported from MedVault.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Export prescription PDF").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to export PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun completePdfOutput(output: PdfOutput, success: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && output.isPendingMediaStoreItem) {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(output.uri, values, null, null)
            } else {
                contentResolver.delete(output.uri, null, null)
            }
        }
    }

    private fun buildPdfFileName(fileName: String): String {
        val baseName = sanitizeFileName(fileName.removeSuffix(".pdf").ifBlank { "Prescription" })
        return "$baseName.pdf"
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(Exception::class.java)
            initializeDriveService(account)
            isSignedIn = true
            notifyDriveReady(true)
        } catch (e: Exception) {
            isSignedIn = false
            notifyDriveReady(false)
            Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory(),
            credential
        )
            .setApplicationName("MedVault")
            .build()
    }

    private fun compressGzip(content: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { gzip ->
            gzip.write(content.toByteArray(Charsets.UTF_8))
        }
        return bos.toByteArray()
    }

    private fun decompressGzip(bytes: ByteArray): String {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
            // Fallback for plain uncompressed JSON text
            return bytes.toString(Charsets.UTF_8)
        }
        val bis = java.io.ByteArrayInputStream(bytes)
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(bis).use { gzip ->
            gzip.copyTo(bos)
        }
        return bos.toString("UTF-8")
    }

    private suspend fun uploadToGoogleDrive(dataJson: String, expectedRevision: String?) = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")

        val folderId = getOrCreateFolder(service)
        val existingFile = findFileMetadata(service, folderId, DATA_FILE)
        val existingFileId = existingFile?.id
        val currentRevision = existingFile?.let { driveRevisionToken(it) }
        val expected = expectedRevision?.trim().orEmpty()

        if (existingFileId != null && expected.isNotEmpty() && currentRevision != expected) {
            throw RemoteChangedException()
        }

        // Compress the JSON payload to gzip
        val compressedBytes = compressGzip(dataJson)
        val content = com.google.api.client.http.ByteArrayContent(
            "application/octet-stream",
            compressedBytes
        )

        if (existingFileId != null) {
            val updateMetadata = File().apply {
                name = DATA_FILE
                mimeType = "application/octet-stream"
            }
            service.files().update(existingFileId, updateMetadata, content)
                .setFields("id")
                .execute()
        } else {
            val createMetadata = File().apply {
                name = DATA_FILE
                mimeType = "application/octet-stream"
                parents = listOf(folderId)
            }
            service.files().create(createMetadata, content)
                .setFields("id")
                .execute()
        }

        checkAndCreateBackup(service, folderId, dataJson)
    }

    private suspend fun downloadFromGoogleDrive(): DriveDataSnapshot = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext DriveDataSnapshot(null, null)

        val folderId = getOrCreateFolder(service)
        var file = findFileMetadata(service, folderId, DATA_FILE)
        
        if (file == null) {
            val restored = restoreFromBackup(service, folderId)
            if (restored != null) {
                file = restored
            } else {
                return@withContext DriveDataSnapshot(null, null)
            }
        }

        val outputStream = ByteArrayOutputStream()
        service.files().get(file.id).executeMediaAndDownloadTo(outputStream)
        
        // Decompress, automatically falling back to plain UTF-8 if not gzipped
        val decompressedJson = decompressGzip(outputStream.toByteArray())
        
        DriveDataSnapshot(decompressedJson, driveRevisionToken(file))
    }

    private fun checkAndCreateBackup(service: Drive, mainFolderId: String, dataJson: String) {
        val prefs = getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
        val lastBackupTime = prefs.getLong("medvault_last_backup_time", 0L)
        val now = System.currentTimeMillis()
        
        // 48 hours = 172800000 milliseconds
        if (now - lastBackupTime >= 172800000L) {
            try {
                val backupFolderId = getOrCreateFolder(service, "backup", mainFolderId)
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val timestamp = sdf.format(Date(now))
                val backupFileName = "patient_records_$timestamp.json"
                
                val compressedBytes = compressGzip(dataJson)
                val content = com.google.api.client.http.ByteArrayContent(
                    "application/octet-stream",
                    compressedBytes
                )
                
                val backupMetadata = File().apply {
                    name = backupFileName
                    mimeType = "application/octet-stream"
                    parents = listOf(backupFolderId)
                }
                
                service.files().create(backupMetadata, content)
                    .setFields("id")
                    .execute()
                
                prefs.edit().putLong("medvault_last_backup_time", now).apply()
                rotateBackups(service, backupFolderId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun rotateBackups(service: Drive, backupFolderId: String) {
        try {
            val result = service.files().list()
                .setQ("name contains 'patient_records_' and '$backupFolderId' in parents and trashed=false")
                .setSpaces("drive")
                .setOrderBy("name desc")
                .setFields("files(id, name)")
                .execute()
                
            val files = result.files
            if (files != null && files.size > 5) {
                for (i in 5 until files.size) {
                    val fileId = files[i].id
                    service.files().delete(fileId).execute()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreFromBackup(service: Drive, mainFolderId: String): File? {
        return try {
            val backupFolderId = getOrCreateFolder(service, "backup", mainFolderId)
            val result = service.files().list()
                .setQ("name contains 'patient_records_' and '$backupFolderId' in parents and trashed=false")
                .setSpaces("drive")
                .setOrderBy("name desc")
                .setFields("files(id, name, modifiedTime, version, headRevisionId)")
                .execute()
                
            val latestBackup = result.files?.firstOrNull() ?: return null
            val backupFileId = latestBackup.id ?: return null
            
            val copyMetadata = File().apply {
                name = DATA_FILE
                parents = listOf(mainFolderId)
            }
            
            val copiedFile = service.files().copy(backupFileId, copyMetadata)
                .setFields("id, modifiedTime, version, headRevisionId")
                .execute()
                
            copiedFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getOrCreateFolder(
        service: Drive,
        folderName: String = MEDVAULT_FOLDER,
        parentFolderId: String? = null
    ): String {
        val parentClause = parentFolderId?.let { " and '$it' in parents" } ?: ""
        val result = service.files().list()
            .setQ("name='${escapeDriveQueryValue(folderName)}' and mimeType='application/vnd.google-apps.folder'$parentClause and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return if (result.files.isEmpty()) {
            val folderMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                parentFolderId?.let { parents = listOf(it) }
            }
            service.files().create(folderMetadata)
                .setFields("id")
                .execute()
                .id
        } else {
            result.files[0].id
        }
    }

    private fun escapeDriveQueryValue(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun findFile(service: Drive, folderId: String, fileName: String): String? {
        val result = service.files().list()
            .setQ("name='${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        return if (result.files.isNotEmpty()) result.files[0].id else null
    }

    private fun findFileMetadata(service: Drive, folderId: String, fileName: String): File? {
        val result = service.files().list()
            .setQ("name='${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, modifiedTime, version, headRevisionId)")
            .execute()

        return result.files.firstOrNull()
    }

    private fun driveRevisionToken(file: File): String {
        val version = file.version?.toString().orEmpty()
        val modifiedTime = file.modifiedTime?.toString().orEmpty()
        val headRevisionId = file.headRevisionId.orEmpty()
        return listOf(version, modifiedTime, headRevisionId).joinToString("|")
    }

    private fun notifyDriveReady(isReady: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("if(typeof onDriveReady === 'function') onDriveReady($isReady);", null)
        }
    }

    private fun notifySyncComplete(success: Boolean, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onSyncComplete === 'function') onSyncComplete($success, ${JSONObject.quote(message)});",
                null
            )
        }
    }

    private suspend fun uploadReportToGoogleDrive(
        patientId: String,
        originalName: String,
        mimeType: String,
        base64Content: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        val medVaultFolderId = getOrCreateFolder(service)
        val reportsFolderId = getOrCreateFolder(service, REPORTS_FOLDER, medVaultFolderId)
        val bytes = Base64.decode(base64Content, Base64.DEFAULT)
        val uploadedAt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val driveName = buildReportFileName(patientId, originalName, uploadedAt)

        val fileMetadata = File().apply {
            name = driveName
            parents = listOf(reportsFolderId)
        }
        val content = com.google.api.client.http.ByteArrayContent(
            mimeType.ifBlank { "application/octet-stream" },
            bytes
        )

        val uploadedFile = service.files().create(fileMetadata, content)
            .setFields("id, name, webViewLink, webContentLink")
            .execute()

        JSONObject()
            .put("report", JSONObject()
                .put("id", uploadedFile.id)
                .put("name", uploadedFile.name)
                .put("originalName", originalName)
                .put("mimeType", mimeType)
                .put("uploadedAt", uploadedAt)
                .put("webViewLink", uploadedFile.webViewLink ?: "")
                .put("webContentLink", uploadedFile.webContentLink ?: "")
            )
    }

    private suspend fun uploadSelectedReportUrisToGoogleDrive(
        patientId: String,
        uris: List<Uri>
    ): JSONArray = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        val medVaultFolderId = getOrCreateFolder(service)
        val reportsFolderId = getOrCreateFolder(service, REPORTS_FOLDER, medVaultFolderId)
        if (uris.isEmpty()) {
            throw Exception("No report file was selected. Please attach the report again.")
        }
        val reports = JSONArray()

        uris.forEach { uri ->
            val originalName = getDisplayName(uri)
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = readReportBytes(uri, mimeType)
            val uploadedAt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val driveName = buildReportFileName(patientId, originalName, uploadedAt)

            val fileMetadata = File().apply {
                name = driveName
                parents = listOf(reportsFolderId)
            }
            val content = com.google.api.client.http.ByteArrayContent(
                if (mimeType.startsWith("image/")) "image/jpeg" else mimeType,
                bytes
            )
            val uploadedFile = service.files().create(fileMetadata, content)
                .setFields("id, name, webViewLink, webContentLink")
                .execute()

            reports.put(JSONObject()
                .put("id", uploadedFile.id)
                .put("name", uploadedFile.name)
                .put("originalName", originalName)
                .put("mimeType", if (mimeType.startsWith("image/")) "image/jpeg" else mimeType)
                .put("uploadedAt", uploadedAt)
                .put("webViewLink", uploadedFile.webViewLink ?: "")
                .put("webContentLink", uploadedFile.webContentLink ?: "")
            )
        }

        reports
    }

    private fun readReportBytes(uri: Uri, mimeType: String): ByteArray {
        val originalBytes = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw Exception("Could not read selected report")

        if (!mimeType.startsWith("image/") || originalBytes.size <= 700 * 1024) {
            return originalBytes
        }

        return try {
            val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                ?: return originalBytes
            val maxSide = 1600f
            val scale = minOf(1f, maxSide / maxOf(bitmap.width, bitmap.height).toFloat())
            val outputBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxOf(1, (bitmap.width * scale).toInt()),
                    maxOf(1, (bitmap.height * scale).toInt()),
                    true
                )
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            val compressed = output.toByteArray()
            if (compressed.isNotEmpty() && compressed.size < originalBytes.size) compressed else originalBytes
        } catch (e: Exception) {
            originalBytes
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                name = it.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment ?: "Report"
    }

    private suspend fun deleteReportFromGoogleDrive(fileId: String) = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        if (fileId.isBlank()) throw Exception("Report file id is missing")
        service.files().delete(fileId).execute()
    }

    private fun buildReportFileName(patientId: String, originalName: String, uploadedAt: String): String {
        val safePatientId = sanitizeFileName(patientId.ifBlank { "Patient" })
        val safeOriginal = sanitizeFileName(originalName.ifBlank { "Report" })
        val dotIndex = safeOriginal.lastIndexOf('.')
        val baseName = if (dotIndex > 0) safeOriginal.substring(0, dotIndex) else safeOriginal
        val extension = if (dotIndex > 0 && dotIndex < safeOriginal.length - 1) safeOriginal.substring(dotIndex) else ""
        return "${safePatientId}_${baseName}_${uploadedAt}${extension}"
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(120)
            .ifBlank { "Report" }
    }

    private fun notifyDataReceived(snapshot: DriveDataSnapshot) {
        val payload = JSONObject()
            .put("data", snapshot.data ?: JSONObject.NULL)
            .put("revision", snapshot.revision ?: JSONObject.NULL)
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onDataReceived === 'function') onDataReceived(${JSONObject.quote(payload.toString())});",
                null
            )
        }
    }

    private fun notifyReportUploadComplete(success: Boolean, payloadJson: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onReportUploadComplete === 'function') onReportUploadComplete($success, ${JSONObject.quote(payloadJson)});",
                null
            )
        }
    }

    private fun notifyReportDeleteComplete(success: Boolean, payloadJson: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onReportDeleteComplete === 'function') onReportDeleteComplete($success, ${JSONObject.quote(payloadJson)});",
                null
            )
        }
    }

    private fun notifyPdfExportComplete(success: Boolean, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onPdfExportComplete === 'function') onPdfExportComplete($success, ${JSONObject.quote(message)});",
                null
            )
        }
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "javascript:if(typeof handleHardwareBack === 'function') { handleHardwareBack(); } else { AndroidStorage.exitApp(); }",
            null
        )
    }
}

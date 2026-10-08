package com.example.xapkinstaller

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_INSTALL_STATUS =
            "com.example.xapkinstaller.ACTION_INSTALL_STATUS"
        private const val MAX_FILES = 4000
        private const val MAX_UNPACK_BYTES = 12L * 1024 * 1024 * 1024
        private val PACKAGE_REGEX = Regex("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+")
    }

    private lateinit var btnChoose: Button
    private lateinit var progress: ProgressBar
    private lateinit var txtProgress: TextView
    private lateinit var txtDetail: TextView
    private lateinit var txtFile: TextView

    private var busy = false
    private var legacyPermissionWaiter: CompletableDeferred<Boolean>? = null
    private var allFilesWaiter: CompletableDeferred<Boolean>? = null
    private var unknownSourceWaiter: CompletableDeferred<Boolean>? = null

    private data class PreparedXapk(
        val directory: File,
        val packageName: String,
        val apks: List<File>,
        val obbs: List<File>
    )

    // SAF: chỉ cần quyền đọc URI do người dùng chọn, không cần quyền đọc toàn bộ bộ nhớ.
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && !busy) {
            txtFile.text = displayName(uri)
            processXapk(uri)
        }
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        legacyPermissionWaiter?.complete(granted)
        legacyPermissionWaiter = null
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Build.VERSION.SDK_INT >= 30 &&
            Environment.isExternalStorageManager()
        allFilesWaiter?.complete(granted)
        allFilesWaiter = null
    }

    private val unknownSourceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        unknownSourceWaiter?.complete(packageManager.canRequestPackageInstalls())
        unknownSourceWaiter = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnChoose = findViewById(R.id.btnChooseXapk)
        progress = findViewById(R.id.progressInstall)
        txtProgress = findViewById(R.id.txtProgress)
        txtDetail = findViewById(R.id.txtDetail)
        txtFile = findViewById(R.id.txtFile)

        btnChoose.setOnClickListener {
            if (!busy) filePicker.launch(arrayOf("*/*"))
        }

        // Hệ thống gửi trạng thái Session.commit vào Activity này.
        handleInstallerCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallerCallback(intent)
    }

    private fun processXapk(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true)
            var prepared: PreparedXapk? = null
            var committed = false
            try {
                showStage("Đang giải nén XAPK", "Đang đọc tệp ZIP…", null)
                val data = withContext(Dispatchers.IO) { extractAndInspect(uri) }
                prepared = data
                showStage(
                    "Đã giải nén",
                    "${data.apks.size} APK, ${data.obbs.size} OBB, gói ${data.packageName}",
                    100
                )

                if (data.obbs.isNotEmpty()) {
                    showStage("Đang sao chép OBB", "Kiểm tra quyền bộ nhớ…", null)
                    val hasPermission = ensureObbPermission()
                    val copied = if (hasPermission) {
                        try {
                            withContext(Dispatchers.IO) { copyObb(data) }
                            true
                        } catch (ex: Exception) {
                            showStage("OBB bị Android từ chối", ex.message ?: "Lỗi ghi OBB", 0)
                            false
                        }
                    } else {
                        showStage("Chưa có quyền OBB", "Không được cấp quyền truy cập bộ nhớ.", 0)
                        false
                    }
                    if (!copied) {
                        // Không bao giờ giả vờ rằng OBB đã cài. Chỉ cài APK khi người dùng đồng ý.
                        if (!confirmApkOnly()) {
                            showStage("Đã hủy", "OBB chưa được sao chép; không cài APK.", 0)
                            return@launch
                        }
                    }
                } else {
                    showStage("Không có OBB", "Bỏ qua bước sao chép OBB.", 100)
                }

                if (!ensureInstallPermission()) {
                    throw IOException("Chưa cho phép ứng dụng này cài APK từ nguồn khác.")
                }
                showStage("Đang khởi chạy cài đặt", "Đang chuyển các APK vào PackageInstaller…", 0)
                withContext(Dispatchers.IO) { commitInstall(data) }
                committed = true
                // Không ghi đè trạng thái thành công/thất bại nếu callback đến rất nhanh.
            } catch (ex: Exception) {
                showStage("Không thể xử lý XAPK", ex.message ?: ex.javaClass.simpleName, 0)
            } finally {
                // APK đã được chép vào session; xóa file tạm không ảnh hưởng đến bước xác nhận.
                withContext(NonCancellable + Dispatchers.IO) {
                    prepared?.directory?.deleteRecursively()
                }
                if (!committed) setBusy(false)
            }
        }
    }

    private fun extractAndInspect(uri: Uri): PreparedXapk {
        val dir = File(cacheDir, "xapk_${System.nanoTime()}")
        if (!dir.mkdirs()) throw IOException("Không thể tạo thư mục tạm")
        try {
            var fileCount = 0
            var bytesWritten = 0L
            val rootPath = dir.canonicalPath + File.separator

            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Không mở được tệp XAPK")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    fileCount++
                    if (fileCount > MAX_FILES) throw IOException("Gói ZIP có quá nhiều file")
                    val name = entry.name.replace('\\', '/')
                    val dest = File(dir, name)
                    // Chặn Zip Slip: ../../, đường dẫn tuyệt đối và các đường dẫn vượt ra ngoài cache.
                    if (!dest.canonicalPath.startsWith(rootPath)) {
                        throw IOException("Đường dẫn ZIP không an toàn: $name")
                    }
                    if (entry.isDirectory) {
                        if (!dest.mkdirs() && !dest.isDirectory) {
                            throw IOException("Không tạo được thư mục $name")
                        }
                    } else {
                        if (!dest.parentFile!!.mkdirs() && !dest.parentFile!!.isDirectory) {
                            throw IOException("Không tạo được thư mục chứa $name")
                        }
                        FileOutputStream(dest).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n == -1) break
                                bytesWritten += n
                                if (bytesWritten > MAX_UNPACK_BYTES) {
                                    throw IOException("Kích thước giải nén vượt giới hạn 12 GiB")
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
            val apks = allFiles.filter { it.extension.equals("apk", true) }
            val obbs = allFiles.filter { it.extension.equals("obb", true) }
            if (apks.isEmpty()) throw IOException("Không tìm thấy APK trong XAPK")
            if (obbs.map { it.name.lowercase() }.distinct().size != obbs.size) {
                throw IOException("Có các file OBB trùng tên")
            }

            val metadata = File(dir, "manifest.json").takeIf { it.isFile }
                ?.let { JSONObject(it.readText(Charsets.UTF_8)) }
            val manifestPackage = metadata?.optString("package_name")?.takeIf { it.isNotBlank() }
            val preferredApk = metadata?.optString("apk_file")?.takeIf { it.isNotBlank() }

            // Tìm base APK theo tên phổ biến; package name luôn lấy từ APK thật.
            val base = apks.firstOrNull { it.name.equals("base.apk", true) }
                ?: apks.firstOrNull {
                    it.name.equals(preferredApk, true) ||
                        it.relativeTo(dir).path.replace(File.separatorChar, '/')
                            .equals(preferredApk, true)
                }
                ?: apks.firstOrNull { it.name.equals("${manifestPackage}.apk", true) }
                ?: apks.firstOrNull { it.name.equals("app.apk", true) }
                ?: apks.firstOrNull { it.name.startsWith("base-", true) }
                ?: apks.singleOrNull()
                ?: throw IOException("Không xác định được base.apk trong bộ split APK")

            val baseInfo = readApkInfo(base)
                ?: throw IOException("Không đọc được package name từ base APK: ${base.name}")
            val packageName = baseInfo.packageName
            if (!PACKAGE_REGEX.matches(packageName)) {
                throw IOException("Package name không hợp lệ: $packageName")
            }
            if (manifestPackage != null && manifestPackage != packageName) {
                throw IOException("Package name trong manifest.json không khớp APK")
            }
            for (apk in apks) {
                // Một số Android không parse được split APK độc lập: hệ thống sẽ kiểm tra
                // tính hợp lệ của tập split sau khi commit.
                val info = readApkInfo(apk) ?: continue
                if (info.packageName != packageName) {
                    throw IOException("APK không cùng package: ${apk.name}")
                }
                if (apkVersion(info) != apkVersion(baseInfo)) {
                    throw IOException("APK khác versionCode: ${apk.name}")
                }
            }
            return PreparedXapk(dir, packageName, listOf(base) + apks.filterNot { it == base }, obbs)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun readApkInfo(apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath, PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }
    }

    private fun apkVersion(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private suspend fun ensureObbPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true
            showStage("Đang sao chép OBB", "Cấp All files access nếu muốn thử ghi OBB.", null)
            val waiter = CompletableDeferred<Boolean>()
            allFilesWaiter = waiter
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                try {
                    allFilesLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                return waiter.await()
            } finally {
                allFilesWaiter = null
            }
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED) return true
        val waiter = CompletableDeferred<Boolean>()
        legacyPermissionWaiter = waiter
        try {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return waiter.await()
        } finally {
            legacyPermissionWaiter = null
        }
    }

    private fun copyObb(data: PreparedXapk) {
        // Android 11+: dù có MANAGE_EXTERNAL_STORAGE, thiết bị vẫn có thể cấm ghi.
        @Suppress("DEPRECATION")
        val destination = File(Environment.getExternalStorageDirectory(),
            "Android/obb/${data.packageName}")
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Android chặn tạo $destination")
        }

        val total = data.obbs.sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var lastPercent = -1
        for (source in data.obbs) {
            val target = File(destination, source.name)
            val temp = File(destination, "${source.name}.installing")
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            done += n
                            val percent = ((done * 100.0) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                showStage("Đang sao chép OBB", "${source.name} – $percent%", percent)
                            }
                        }
                        output.fd.sync()
                    }
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        showStage("Đang sao chép OBB", "Đã sao chép ${data.obbs.size} file OBB", 100)
    }

    private suspend fun ensureInstallPermission(): Boolean {
        if (packageManager.canRequestPackageInstalls()) return true
        showStage("Xin quyền cài APK", "Bật “Cho phép từ nguồn này” trong Cài đặt.", null)
        val waiter = CompletableDeferred<Boolean>()
        unknownSourceWaiter = waiter
        try {
            unknownSourceLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return waiter.await()
        } finally {
            unknownSourceWaiter = null
        }
    }

    private fun commitInstall(data: PreparedXapk) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(data.packageName)
            setSize(data.apks.sumOf { it.length() })
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }

        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                val total = data.apks.sumOf { it.length() }.coerceAtLeast(1L)
                var done = 0L
                var lastPercent = -1
                data.apks.forEachIndexed { index, apk ->
                    val sessionName = if (index == 0) "base.apk" else "split_$index.apk"
                    session.openWrite(sessionName, 0L, apk.length()).use { output ->
                        FileInputStream(apk).use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                                done += n
                                val percent = ((done * 100.0) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    showStage("Đang khởi chạy cài đặt",
                                        "Chuyển APK: $percent%", percent)
                                }
                            }
                        }
                        session.fsync(output)
                    }
                }

                // PendingIntent MUTABLE và explicit Activity là bắt buộc để hệ thống
                // bổ sung status/Intent.EXTRA_INTENT vào callback khi target SDK mới.
                val callback = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getActivity(this, sessionId, callback, flags)
                    .intentSender
                session.commit(sender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(sessionId)
        }
    }

    private fun handleInstallerCallback(callback: Intent?) {
        if (callback?.action != ACTION_INSTALL_STATUS) return
        val status = callback.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                showStage("Đang khởi chạy cài đặt",
                    "Android yêu cầu bạn xác nhận cài đặt…", 100)
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    callback.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    try {
                        startActivity(confirmIntent)
                    } catch (ex: Exception) {
                        showStage("Không mở được màn hình xác nhận",
                            ex.message ?: "Lỗi hệ thống", 0)
                        setBusy(false)
                    }
                } else {
                    showStage("Lỗi cài đặt", "Thiếu Intent xác nhận từ Android.", 0)
                    setBusy(false)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                showStage("Cài đặt APK thành công", "PackageInstaller đã hoàn tất.", 100)
                setBusy(false)
            }
            Int.MIN_VALUE -> Unit
            else -> {
                val detail = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Mã lỗi PackageInstaller: $status"
                showStage("Cài đặt APK thất bại", detail, 0)
                setBusy(false)
            }
        }
    }

    private suspend fun confirmApkOnly(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            AlertDialog.Builder(this)
                .setTitle("Không sao chép được OBB")
                .setMessage(
                    "Android đang hạn chế ghi /Android/obb. Nếu tiếp tục, chỉ APK được cài " +
                    "và trò chơi có thể không chạy vì thiếu dữ liệu. " +
                    "Bạn vẫn muốn cài APK?"
                )
                .setPositiveButton("Chỉ cài APK") { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton("Hủy") { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }
                .show()
        }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: uri.toString()
            }
        return uri.lastPathSegment ?: "XAPK"
    }

    private fun setBusy(value: Boolean) {
        busy = value
        btnChoose.isEnabled = !value
    }

    // Dùng được cả trong Dispatchers.IO và Main.
    private fun showStage(stage: String, detail: String, percent: Int?) {
        runOnUiThread {
            txtProgress.text = stage
            txtDetail.text = detail
            progress.isIndeterminate = percent == null
            if (percent != null) progress.progress = percent.coerceIn(0, 100)
        }
    }
}

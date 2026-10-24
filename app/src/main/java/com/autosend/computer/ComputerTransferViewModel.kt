package com.autosend.computer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ComputerPhase { PREPARING, CONFIGURE, UPLOADING, FAILED, UNKNOWN, SUCCESS }

class ComputerTransferViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = ComputerSettingsStore(application)
    private val client = ComputerUploadClient(diagnosticLog = { ComputerTransferLog.record(application, it) })
    private var initialized = false
    private var uri: Uri? = null
    private var attempt: UploadAttempt? = null
    private var test: ComputerConnectionTest? = null
    private var job: Job? = null
    private var shareMode = false
    var foreground = true
    var phase by mutableStateOf(ComputerPhase.PREPARING); private set
    var file by mutableStateOf<ComputerFile?>(null); private set
    var sent by mutableStateOf(0L); private set
    var message by mutableStateOf(""); private set
    // Password and edit buffers live only in memory, never SavedStateHandle / rememberSaveable.
    var address by mutableStateOf(computerAddressForEditing(store.address)); private set
    var password by mutableStateOf(""); private set
    var hasPassword by mutableStateOf(store.hasPassword); private set
    var dirty by mutableStateOf(false); private set
    var testing by mutableStateOf(false); private set
    private var lastProgressAt = 0L
    private var metadataMs = 0L

    fun editAddress(value: String) { address = value; dirty = true; message = "" }
    fun editPassword(value: String) { password = value; dirty = true; message = "" }

    fun initialize(intent: Intent, isShare: Boolean) {
        if (initialized) return
        initialized = true
        shareMode = isShare
        if (!isShare) { phase = ComputerPhase.CONFIGURE; loadPasswordForEditing(); return }
        @Suppress("DEPRECATION")
        val stream = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        val clip = runCatching { intent.clipData }.getOrNull()
        if (intent.action != Intent.ACTION_SEND || (clip != null && clip.itemCount != 1)) {
            phase = ComputerPhase.FAILED; message = "每次只支持分享一个文件。"; return
        }
        uri = stream ?: clip?.getItemAt(0)?.uri
        if (uri?.scheme != "content" || uri?.authority.isNullOrBlank()) {
            phase = ComputerPhase.FAILED; message = "请从相册或文件管理器分享一个可读取的文件。"; return
        }
        val interrupted = saved.get<Boolean>("uploadAttempted") == true
        job = viewModelScope.launch {
            try {
                val inspecting = System.nanoTime()
                file = withContext(Dispatchers.IO) { inspect(uri!!) }
                metadataMs = elapsedMs(inspecting)
                if (interrupted) {
                    phase = ComputerPhase.UNKNOWN
                    message = "页面已恢复，上次传输结果未知，文件可能已送达。请先检查电脑；不会自动重新上传。"
                } else if (store.address.isBlank() || !store.hasPassword) {
                    phase = ComputerPhase.CONFIGURE
                    message = "先配置电脑，本次分享会保留在此页面，保存后继续上传。"
                } else startUpload()
            } catch (_: Exception) {
                phase = ComputerPhase.FAILED
                message = "文件无法读取、名称无效或超过 100,000,000 字节，请重新分享。"
            }
        }
    }

    private fun inspect(uri: Uri): ComputerFile {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size: Long? = null
        // Some providers can stream content but do not implement the metadata query.
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si).takeIf { it >= 0 }
                }
            }
        }
        val filename = name ?: "shared_file"
        require(filename.isNotBlank() && filename.length <= 255 && filename != "." && filename != ".." &&
            filename.none { it.isISOControl() || it == '/' || it == '\\' })
        require(size == null || size!! <= COMPUTER_FILE_LIMIT)
        return ComputerFile(filename, size, runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream")
    }

    private fun currentPassword() = password.ifEmpty { store.password().orEmpty() }

    private fun loadPasswordForEditing() {
        if (password.isNotEmpty() || !hasPassword) return
        runCatching { store.password() }
            .onSuccess { password = it.orEmpty() }
            .onFailure { message = "已保存的密码无法读取，请重新输入。" }
    }

    fun save() {
        if (testing || phase == ComputerPhase.UPLOADING) return
        try {
            val savedPassword = currentPassword()
            store.save(address, savedPassword)
            password = savedPassword; address = computerAddressForEditing(store.address); hasPassword = true; dirty = false
            message = "配置已保存。"
            if (shareMode && file != null) startUpload()
        } catch (_: Exception) {
            message = "保存失败：请填写完整 IP（可带端口）或 HTTPS 根地址及密码；旧密码无法解密时请重新输入。"
        }
    }

    fun configure() {
        phase = ComputerPhase.CONFIGURE
        message = "修改配置后保存，可继续发送本次文件。"
        loadPasswordForEditing()
    }

    fun testConnection() {
        if (testing) return
        try {
            ComputerUploadClient.serverUrl(address)
            test = client.connectionTest(address, currentPassword().also { require(it.isNotEmpty()) })
        } catch (_: Exception) { message = "请填写完整 IP（可带端口）或 HTTPS 根地址及密码。"; return }
        testing = true
        job = viewModelScope.launch {
            message = withContext(Dispatchers.IO) {
                test!!.execute()
            }
            testing = false; test = null
        }
    }

    fun startUpload() {
        if (phase == ComputerPhase.UPLOADING || testing) return
        if (!foreground) {
            phase = ComputerPhase.FAILED; message = "页面已离开前台，请返回后手动重试。"; return
        }
        val source = uri ?: return
        val info = file ?: return
        try {
            val readingSettings = System.nanoTime()
            val storedAddress = store.address
            val storedPassword = store.password().orEmpty().also { require(it.isNotEmpty()) }
            val settingsMs = elapsedMs(readingSettings)
            attempt = client.upload(storedAddress, storedPassword, info,
                { getApplication<Application>().contentResolver.openInputStream(source)
                    ?: throw java.io.IOException("Unreadable content") },
                { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 100 || bytes == info.size) {
                        lastProgressAt = now
                        viewModelScope.launch { sent = bytes }
                    }
                }, metadataMs, settingsMs)
        } catch (_: Exception) {
            phase = ComputerPhase.CONFIGURE; message = "配置无法读取，请重新设置地址和密码。"; return
        }
        saved["uploadAttempted"] = true
        phase = ComputerPhase.UPLOADING; sent = 0; message = "正在上传，请保持此页面在前台。"
        val current = attempt!!
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { current.execute() }
            sent = current.bytes.get()
            message = result.message
            phase = when { result.success -> ComputerPhase.SUCCESS; result.uncertain -> ComputerPhase.UNKNOWN
                else -> ComputerPhase.FAILED }
        }
    }

    /** Called on stop except configuration changes. No service and no background continuation. */
    fun cancelActive() {
        foreground = false
        test?.cancel()
        attempt?.takeIf { phase == ComputerPhase.UPLOADING }?.let { current ->
            current.cancel()
            viewModelScope.launch(Dispatchers.IO) { current.closeSource() }
        }
    }

    override fun onCleared() { cancelActive(); password = ""; super.onCleared() }
}

package com.autosend.computer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.autosend.ui.theme.AutoSendTheme
import kotlinx.coroutines.delay
import java.util.Locale

class ComputerShareActivity : ComputerTransferActivity() {
    override val shareMode = true
}

class ComputerSettingsActivity : ComputerTransferActivity() {
    override val shareMode = false
}

abstract class ComputerTransferActivity : ComponentActivity() {
    protected abstract val shareMode: Boolean
    private lateinit var model: ComputerTransferViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ComputerTransferViewModel::class.java]
        model.initialize(intent, shareMode)
        setContent { AutoSendTheme { ComputerTransferPage(model, shareMode, ::finish) } }
    }

    override fun onStart() { super.onStart(); model.foreground = true }
    override fun onStop() {
        if (!isChangingConfigurations) model.cancelActive()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComputerTransferPage(model: ComputerTransferViewModel, share: Boolean, finish: () -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(true) }
    var addressField by remember { mutableStateOf(TextFieldValue(model.address, TextRange(model.address.length))) }
    LaunchedEffect(model.address) {
        if (addressField.text != model.address) {
            addressField = TextFieldValue(model.address, TextRange(model.address.length))
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val leave = {
        if (model.phase == ComputerPhase.UPLOADING || model.dirty) confirmExit = true
        else { model.cancelActive(); finish() }
    }
    BackHandler { leave() }
    LaunchedEffect(model.phase, confirmExit) {
        if (model.phase == ComputerPhase.SUCCESS && !confirmExit) {
            delay(1000)
            Toast.makeText(context, "已发送到电脑", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("发送到电脑") }, navigationIcon = {
            IconButton(onClick = { leave() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            model.file?.let { file ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(file.size?.let { "$it 字节" } ?: "大小未知，读取时限制为 100,000,000 字节",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (model.phase == ComputerPhase.CONFIGURE) {
                Text(if (share) "配置后自动发送本次文件" else "电脑连接设置",
                    style = MaterialTheme.typography.titleLarge)
                Text("在电脑开启 Quicker HTTP 文件服务器，填写电脑 IP 和固定密码。文件保存到服务根目录。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(addressField, { value -> addressField = value; model.editAddress(value.text) },
                    label = { Text("电脑 IP 或 HTTPS 地址") },
                    placeholder = { Text("192.168.1.100") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = !model.testing, modifier = Modifier.fillMaxWidth())
                Text("前缀可以删除，默认端口 17831。其他端口填 IP:端口，也可粘贴完整 HTTPS 地址。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(model.password, model::editPassword,
                    label = { Text(if (model.hasPassword) "密码（留空保留已保存密码）" else "密码") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    singleLine = true, enabled = !model.testing, modifier = Modifier.fillMaxWidth())
                Text("用户名固定为 quicker。密码加密保存在本机，不随配置导出。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = model::testConnection, enabled = !model.testing) {
                        Text(if (model.testing) "测试中…" else "测试连接")
                    }
                    Button(onClick = model::save, enabled = !model.testing) {
                        Text(if (share && model.file != null) "保存并发送" else "保存")
                    }
                }
                Text("测试只检查服务响应和认证情况，不测试上传落盘。手机和电脑需能通过局域网互相访问。",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val log = ComputerTransferLog.read(context)
                    if (log.isBlank()) {
                        Toast.makeText(context, "暂无记录，请先分享一个文件或测试连接", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setText(AnnotatedString(log))
                        Toast.makeText(context, "最近上传日志已复制", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("复制最近上传日志") }
            }
            if (model.phase == ComputerPhase.PREPARING) CircularProgressIndicator()
            if (model.phase == ComputerPhase.UPLOADING) {
                Text("正在发送", style = MaterialTheme.typography.titleLarge)
                val size = model.file?.size
                if (size != null && size > 0) {
                    LinearProgressIndicator(progress = (model.sent.toFloat() / size).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(String.format(Locale.ROOT, "已读取 %.2f MB · 等待电脑确认后才算成功", model.sent / 1_000_000.0))
                OutlinedButton(onClick = { confirmExit = true }) { Text("取消发送") }
            }
            if (model.message.isNotBlank()) {
                Text(model.message, color = if (model.phase in listOf(ComputerPhase.FAILED, ComputerPhase.UNKNOWN))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (model.phase in listOf(ComputerPhase.FAILED, ComputerPhase.UNKNOWN)) {
                if (model.file != null) {
                    Button(onClick = model::startUpload) { Text("手动重试") }
                    TextButton(onClick = model::configure) { Text("修改电脑设置") }
                }
                OutlinedButton(onClick = { leave() }) { Text("返回") }
            }
        }
    }
    if (confirmExit) {
        AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("返回来源页面？") },
            text = { Text(if (model.phase == ComputerPhase.UPLOADING)
                "将取消传输。文件可能已经送达电脑，请先检查电脑再重试。"
                else if (model.dirty) "尚未保存的电脑设置将被放弃。" else model.message) },
            confirmButton = { TextButton(onClick = {
                val uploading = model.phase == ComputerPhase.UPLOADING
                model.cancelActive()
                if (uploading) Toast.makeText(context, "已取消；文件可能已送达，请检查电脑。", Toast.LENGTH_LONG).show()
                finish()
            }) { Text("返回") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("留在此页") } })
    }
}

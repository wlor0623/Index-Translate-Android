package com.index.translate.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.index.translate.AppViewModel
import com.index.translate.DownloadState
import com.index.translate.LocalModel
import com.index.translate.ModelRepository
import com.index.translate.ModelState
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(vm: AppViewModel) {
    val models by vm.models.models.collectAsState()
    val state by vm.models.state.collectAsState()
    val download by vm.models.download.collectAsState()
    val selectedName = vm.models.selectedName

    val scope = rememberCoroutineScope()
    var importProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalModel?>(null) }

    // SAF 文件选择(导入)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importError = null
            scope.launch {
                val result = vm.models.importFromUri(uri) { copied, total ->
                    importProgress = copied to total
                }
                importProgress = null
                result.onFailure { importError = it.message }
                result.onSuccess { vm.models.selectModel(it) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 本地模型 ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("本地模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { vm.models.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        if (models.isEmpty()) {
            item {
                Text(
                    "还没有模型。可在下方在线下载,或从文件导入(需 .gguf 格式,如 Index-Translate-2B.Q4_K_M.gguf)。\n也可用 adb 推送:\nadb push Index-Translate-2B.Q4_K_M.gguf /sdcard/Android/data/com.index.translate/files/models/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(models, key = { it.name }) { model ->
            val isSelected = model.name == selectedName
            val isReady = (state as? ModelState.Ready)?.name == model.name
            val isLoading = (state as? ModelState.Loading)?.name == model.name
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
                onClick = { vm.models.selectModel(model.name) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(formatBytes(model.sizeBytes))
                                when {
                                    isReady -> append(" · 使用中")
                                    isLoading -> append(" · 加载中…")
                                    isSelected -> append(" · 已选择")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { deleteTarget = model }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从文件导入 .gguf") }
        }

        item {
            AnimatedVisibility(visible = importProgress != null) {
                val p = importProgress
                Column {
                    if (p != null && p.second > 0) {
                        LinearProgressIndicator(
                            progress = { (p.first.toFloat() / p.second).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "导入中 ${formatBytes(p.first)} / ${formatBytes(p.second)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (p != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("导入中 ${formatBytes(p.first)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        importError?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        // ---- 在线下载 ----
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("在线下载(ModelScope,支持断点续传)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "Index-Translate-2B:B站开源、150 种语言互译;下载完成后自动出现在本地列表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ModelRepository.REMOTE_MODELS, key = { it.fileName }) { remote ->
            val downloading = download is DownloadState.Running &&
                    (download as DownloadState.Running).fileName == remote.fileName
            val isDone = models.any { it.name == remote.fileName }
            Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(remote.fileName.removeSuffix(".gguf").substringAfterLast('.'),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                remote.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                            when {
                                downloading -> TextButton(onClick = { vm.models.cancelDownload() }) { Text("取消") }
                                isDone -> Text("已下载", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                else -> TextButton(onClick = { vm.models.startDownload(remote) }) { Text("下载") }
                            }
                        }
                    }
                    if (downloading) {
                        val run = download as DownloadState.Running
                        val total = run.total.coerceAtLeast(1)
                        LinearProgressIndicator(
                            progress = { (run.downloaded.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(run.downloaded)} / ${formatBytes(total)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when (val d = download) {
            is DownloadState.Done -> item {
                Text("✓ ${d.fileName} 下载完成", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
            is DownloadState.Error -> item {
                Text("下载失败:${d.message}(重试会断点续传)", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            else -> {}
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型?") },
            text = { Text("${target.name}\n\n将释放 ${formatBytes(target.sizeBytes)} 存储空间,操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(target)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

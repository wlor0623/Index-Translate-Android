package com.index.translate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.index.translate.AppViewModel
import com.index.translate.ModelState

@Composable
fun TranslateScreen(
    vm: AppViewModel,
    onGoToModels: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val modelState by vm.models.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelStateCard(modelState, onGoToModels)

        // ---- 语言行 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            LanguageDropdown(
                selected = ui.sourceLang,
                isSource = true,
                onSelect = { code -> vm.update { it.copy(sourceLang = code) } },
            )
            IconButton(onClick = { vm.swapLanguages() }) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "交换语言",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            LanguageDropdown(
                selected = ui.targetLang,
                isSource = false,
                onSelect = { code -> vm.update { it.copy(targetLang = code) } },
            )
        }

        // ---- 输入 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            OutlinedTextField(
                value = ui.input,
                onValueChange = { text -> vm.update { it.copy(input = text) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入要翻译的文本…") },
                minLines = 4,
                maxLines = 10,
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("支持术语表、文体等约束")
                        Text("${ui.input.length} 字")
                    }
                },
                trailingIcon = {
                    if (ui.input.isNotEmpty()) {
                        Text(
                            "清空",
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { vm.update { it.copy(input = "", output = "") } }
                                .padding(4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }

        // ---- 约束折叠 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.update { it.copy(showConstraints = !it.showConstraints) }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("约束翻译(术语 / 文体 / 要求)", fontWeight = FontWeight.Medium)
                    Icon(
                        if (ui.showConstraints) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = ui.showConstraints) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField(
                            label = "术语表",
                            hint = "碳纤维:carbon fiber,王平仲:Wang Pingzhong",
                            value = ui.glossary,
                        ) { v -> vm.update { it.copy(glossary = v) } }
                        LabeledField(
                            label = "文体 / 领域",
                            hint = "如:科技新闻、商务邮件、游戏文本",
                            value = ui.genre,
                            singleLine = true,
                        ) { v -> vm.update { it.copy(genre = v) } }
                        LabeledField(
                            label = "硬性要求(每行一条)",
                            hint = "如:保留 JSON 格式",
                            value = ui.hard,
                        ) { v -> vm.update { it.copy(hard = v) } }
                        LabeledField(
                            label = "软性要求(每行一条)",
                            hint = "如:语气正式一些",
                            value = ui.soft,
                        ) { v -> vm.update { it.copy(soft = v) } }
                        LabeledField(
                            label = "通用约束指令",
                            hint = "与训练规范一致的附加指令",
                            value = ui.instruction,
                        ) { v -> vm.update { it.copy(instruction = v) } }

                        // 实际发送的 Prompt
                        var showPrompt by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPrompt = !showPrompt }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "查看实际发送的 Prompt",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Icon(
                                if (showPrompt) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(visible = showPrompt && ui.promptPreview != null) {
                            Text(
                                ui.promptPreview ?: "(点击翻译后生成)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ---- 翻译按钮 ----
        if (ui.generating) {
            OutlinedButton(
                onClick = { vm.stopGeneration() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止生成")
            }
        } else {
            Button(
                onClick = { vm.translate() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = ui.input.isNotBlank(),
            ) {
                Icon(Icons.Filled.Translate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("翻 译", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---- 输出 ----
        val hasOutput = ui.output.isNotBlank() || ui.generating || ui.error != null
        if (hasOutput) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (ui.error != null)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surface
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.error?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    if (ui.output.isNotBlank() || ui.generating) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("译文", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ui.generating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else if (ui.output.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(ui.output))
                                            copied = true
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                            contentDescription = "复制译文",
                                            tint = if (copied) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            ui.output,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ui.stats?.let { stats ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            StatChip("首字", "${stats.firstTokenMs} ms")
                            StatChip("总耗时", "%.1f s".format(stats.totalMs / 1000.0))
                            StatChip("tokens", "${stats.tokens}")
                            StatChip("速度", "%.1f tok/s".format(stats.tokPerSec))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabeledField(
    label: String,
    hint: String,
    value: String,
    singleLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModelStateCard(state: ModelState, onGoToModels: () -> Unit) {
    val (color, text, sub) = when (state) {
        is ModelState.Ready -> Triple(
            MaterialTheme.colorScheme.primary,
            "已加载:${state.name}",
            "ctx ${state.nCtx} · 加载耗时 ${state.loadMs / 1000.0}s",
        )
        is ModelState.Loading -> Triple(
            MaterialTheme.colorScheme.secondary,
            "正在加载 ${state.name} …",
            "首次加载约需 10-60 秒",
        )
        is ModelState.Failed -> Triple(
            MaterialTheme.colorScheme.error,
            state.message,
            state.name,
        )
        is ModelState.NoModel -> Triple(
            MaterialTheme.colorScheme.error,
            "尚未选择模型",
            "点击前往「模型」页下载或导入",
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onGoToModels),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state is ModelState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

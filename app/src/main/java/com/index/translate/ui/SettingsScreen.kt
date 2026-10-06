package com.index.translate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.index.translate.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var ctx by remember { mutableIntStateOf(vm.settings.ctxTokens) }
    var threads by remember { mutableIntStateOf(vm.settings.threads) }
    var temperature by remember { mutableFloatStateOf(vm.settings.temperature) }
    var maxTokens by remember { mutableIntStateOf(vm.settings.maxTokens) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("推理设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // ---- 上下文长度 ----
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上下文长度(tokens)", fontWeight = FontWeight.Medium)
                Text(
                    "越大越占内存。改后需点下方「重新加载模型」生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf(1024, 2048, 4096, 8192, 16384)) {
                        FilterChip(
                            selected = ctx == v,
                            onClick = {
                                ctx = v
                                vm.settings.ctxTokens = v
                            },
                            label = { Text("$v") },
                        )
                    }
                }
            }
        }

        // ---- 线程数 ----
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("推理线程数", fontWeight = FontWeight.Medium)
                    Text(
                        if (threads == 0) "自动(${vm.settings.autoThreads()})" else "$threads",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = threads.toFloat(),
                    onValueChange = {
                        threads = it.toInt()
                        vm.settings.threads = threads
                    },
                    valueRange = 0f..8f,
                    steps = 7,
                )
                Text(
                    "0 = 自动(默认 6 线程,大核优先);改后需重新加载模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 温度 ----
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("采样温度", fontWeight = FontWeight.Medium)
                    Text(
                        if (temperature <= 0f) "0(贪心,推荐)" else "%.1f".format(temperature),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = temperature,
                    onValueChange = {
                        temperature = (it * 10).toInt() / 10f
                        vm.settings.temperature = temperature
                    },
                    valueRange = 0f..1.5f,
                    steps = 14,
                )
                Text(
                    "与桌面版一致默认 0(贪心解码,翻译最稳定)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- max tokens ----
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("单次生成上限(tokens)", fontWeight = FontWeight.Medium)
                    Text("$maxTokens", color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = {
                        maxTokens = (it / 64).toInt() * 64
                        vm.settings.maxTokens = maxTokens
                    },
                    valueRange = 256f..4096f,
                )
            }
        }

        Button(
            onClick = { vm.models.reload() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重新加载模型(应用上下文/线程设置)") }

        HorizontalDivider()

        Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Index-Translate-Android:基于 B 站开源 Index-Translate-2B 模型与 llama.cpp 的完全离线翻译应用。\n" +
                "推理在本地完成,不联网、不上传任何数据(仅模型下载需要网络)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "模型:IndexTeam/Index-Translate-2B-GGUF(ModelScope)\n" +
                "推理引擎:llama.cpp b11439(arm64 NEON)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

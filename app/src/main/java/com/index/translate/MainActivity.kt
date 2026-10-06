package com.index.translate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.index.translate.ui.HistoryScreen
import com.index.translate.ui.IndexTranslateTheme
import com.index.translate.ui.ModelsScreen
import com.index.translate.ui.SettingsScreen
import com.index.translate.ui.TranslateScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IndexTranslateTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Translate("翻译", Icons.Filled.Translate),
    History("历史", Icons.Filled.History),
    Models("模型", Icons.Filled.Memory),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
private fun AppRoot() {
    val vm: AppViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.Translate.name) }
    val current = Tab.valueOf(tab)

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { tab = t.name },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Crossfade(
            targetState = current,
            modifier = Modifier.padding(padding),
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Translate -> TranslateScreen(vm, onGoToModels = { tab = Tab.Models.name })
                Tab.History -> HistoryScreen(vm, onOpen = { tab = Tab.Translate.name })
                Tab.Models -> ModelsScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

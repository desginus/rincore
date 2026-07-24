package me.rerere.rikkahub.ui.pages.desk

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeskScreen(modifier: Modifier = Modifier) {
    val vm: DeskVM = koinViewModel()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fp by vm.filePath.collectAsState()
    val fc by vm.fileContent.collectAsState()
    val fl by vm.fileLanguage.collectAsState()
    val msgs by vm.messages.collectAsState()
    val running by vm.isRunning.collectAsState()
    val tab = remember { mutableStateOf(DeskTab.CHAT) }

    if (landscape) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().width(320.dp)) {
                DeskBottomPanel(tab.value, { tab.value = it }, msgs, running,
                    { vm.sendMessage(it) }, { vm.stopAi() },
                    { p, _ -> vm.openFile(p) }, Modifier.fillMaxSize())
            }
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxHeight().weight(1f)) {
                CodeEditorPanel(fp, fc, fl, { vm.updateContent(it) }, Modifier.fillMaxSize())
            }
        }
    } else {
        var split by remember { mutableStateOf(0.58f) }
        Column(modifier = modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(split)) {
                CodeEditorPanel(fp, fc, fl, { vm.updateContent(it) }, Modifier.fillMaxSize())
            }
            HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(1f - split)) {
                DeskBottomPanel(tab.value, { tab.value = it }, msgs, running,
                    { vm.sendMessage(it) }, { vm.stopAi() },
                    { p, _ -> vm.openFile(p) }, Modifier.fillMaxSize())
            }
        }
    }
}

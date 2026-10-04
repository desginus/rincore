/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.videogen

/* ───【自研】VideoGenPage.kt — 视频生成页 (v4.8.100)
 * 抽屉「功能折叠」三件套之一 (翻译 / 图像 / 视频)。
 * 参数: 模型(VIDEO) / 提示词 / 时长 / 清晰度 / 画面比例 / 水印。
 * 生成中可取消; 结果本地播放 + 一键保存到工作区。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.render.VideoRenderView
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGenPage(vm: VideoGenVM = koinViewModel()) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val models by vm.videoModels.collectAsStateWithLifecycle()
    val selectedKey by vm.selectedKey.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val watermark by vm.watermark.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.savedToWorkspace.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_gen_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 模型 ──
            Text(stringResource(R.string.video_gen_model), style = MaterialTheme.typography.titleSmall)
            if (models.isEmpty()) {
                Card {
                    Text(
                        stringResource(R.string.video_gen_no_model),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                var expanded by remember { mutableStateOf(false) }
                val selected = models.firstOrNull { it.key == selectedKey } ?: models.first()
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    models.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                vm.selectModel(option.key)
                                expanded = false
                            },
                        )
                    }
                }
            }

            // ── 提示词 ──
            OutlinedTextField(
                value = prompt,
                onValueChange = vm::updatePrompt,
                label = { Text(stringResource(R.string.video_gen_prompt_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 时长 ──
            ParamChips(
                label = stringResource(R.string.video_gen_duration),
                options = listOf(
                    null to stringResource(R.string.video_gen_default),
                    5 to "5s",
                    10 to "10s",
                ),
                selected = duration,
                onSelect = vm::updateDuration,
            )
            // ── 清晰度 ──
            ParamChips(
                label = stringResource(R.string.video_gen_resolution),
                options = listOf(
                    null to stringResource(R.string.video_gen_default),
                    "720P" to "720P",
                    "1080P" to "1080P",
                ),
                selected = resolution,
                onSelect = vm::updateResolution,
            )
            // ── 画面比例 ──
            ParamChips(
                label = stringResource(R.string.video_gen_aspect),
                options = listOf(
                    null to stringResource(R.string.video_gen_default),
                    "16:9" to "16:9",
                    "9:16" to "9:16",
                    "1:1" to "1:1",
                ),
                selected = aspect,
                onSelect = vm::updateAspect,
            )

            // ── 水印 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.video_gen_watermark), modifier = Modifier.weight(1f))
                Switch(checked = watermark, onCheckedChange = vm::updateWatermark)
            }

            // ── 生成 / 取消 ──
            val phaseText = when (phase) {
                VideoGenVM.Phase.SUBMITTING, VideoGenVM.Phase.RUNNING ->
                    stringResource(R.string.video_gen_generating)
                VideoGenVM.Phase.QUEUED -> stringResource(R.string.video_gen_queued)
                VideoGenVM.Phase.IDLE -> null
            }
            Button(
                onClick = { if (isGenerating) vm.cancelGeneration() else vm.generate() },
                enabled = models.isNotEmpty() && (isGenerating || prompt.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (isGenerating) (phaseText ?: stringResource(R.string.video_gen_generating))
                    else stringResource(R.string.video_gen_generate)
                )
            }

            // ── 错误 ──
            error?.let { e ->
                Text(
                    text = if (e == "no_model") stringResource(R.string.video_gen_no_model)
                    else stringResource(R.string.video_gen_failed) + ": " + e,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── 结果 ──
            result?.let { file ->
                Text(stringResource(R.string.video_gen_result), style = MaterialTheme.typography.titleSmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    VideoRenderView(file)
                }
                OutlinedButton(onClick = vm::saveToWorkspace, enabled = !saved) {
                    Text(if (saved) stringResource(R.string.video_gen_saved) else stringResource(R.string.video_gen_save_workspace))
                }
            }
        }
    }
}

@Composable
private fun <T> ParamChips(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

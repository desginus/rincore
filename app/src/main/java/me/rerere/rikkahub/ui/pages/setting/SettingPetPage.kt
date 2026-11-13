package me.rerere.rikkahub.ui.pages.setting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.data.pet.PetPack
import me.rerere.rikkahub.data.pet.PetPrefs
import me.rerere.rikkahub.data.pet.PetRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.floating.PetSprite
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

/**
 * 宠物页：兼容 DSH-TUI 的 ZIP 宠物包（pack.json + 序列帧 PNG）。
 *
 * 内置「鲸娘」来自 DSH Go 的默认宠物包（用户指定的原版第二只宠物），
 * 按 pack.json + 序列帧 PNG 原样接入。
 */
@Composable
fun SettingPetPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { PetRepository(context) }
    var pets by remember { mutableStateOf(repo.loadPacks()) }
    var selectedId by remember { mutableStateOf(PetPrefs.selected(context)) }
    var pendingDelete by remember { mutableStateOf<PetPack?>(null) }
    var importing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            runCatching { repo.importZip(uri) }
                .onSuccess { pack ->
                    pets = repo.loadPacks()
                    selectedId = pack.id
                    Toast.makeText(context, "已导入并选中：${pack.name}", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "导入失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            importing = false
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("宠物") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("桌宠", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "悬浮球已改为宠物。兼容 DSH-TUI 的像素宠物包：ZIP 内放 pack.json + 序列帧 PNG，导入后即可在这里切换。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "内置「鲸娘」来自 DSH Go 的默认宠物包（原版第二只宠物），按 pack.json + 序列帧 PNG 原样接入；也可以导入其他同格式宠物包。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            items(pets, key = { it.id }) { pack ->
                PetRow(
                    pack = pack,
                    selected = pack.id == selectedId,
                    onSelect = {
                        PetPrefs.select(context, pack.id)
                        selectedId = pack.id
                    },
                    onDelete = if (pack.builtin) null else ({ pendingDelete = pack }),
                )
            }

            item("import") {
                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (importing) "正在导入…" else "导入宠物包（ZIP）",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除宠物包？") },
            text = { Text("会删除「${pack.name}」的本地文件；内置宠物不可删除。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteImported(pack)
                    pets = repo.loadPacks()
                    selectedId = PetPrefs.selected(context)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PetRow(
    pack: PetPack,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PetSprite(
                pack = pack,
                animationName = "idle",
                modifier = Modifier.size(72.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (pack.builtin) {
                        Text("内置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (selected) {
                        Icon(
                            HugeIcons.Tick02,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    listOf(pack.author, pack.license).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(pack.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            if (onDelete != null) {
                Icon(
                    HugeIcons.Delete02,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDelete),
                )
            }
        }
    }
}

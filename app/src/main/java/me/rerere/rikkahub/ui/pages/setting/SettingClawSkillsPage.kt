package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingClawSkillsPage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.openclaw.ClawSkill
import me.rerere.rikkahub.openclaw.ClawSkillManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingClawSkillsPage() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("OpenClaw Skills") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        }
    ) { innerPadding ->
        ClawSkillsContent(innerPadding)
    }
}

/** OpenClaw 技能内容 — 供独立页与「技能与生态」合并页复用 */
@Composable
fun ClawSkillsContent(innerPadding: PaddingValues = PaddingValues()) {
    val skills by ClawSkillManager.skills.collectAsStateWithLifecycle()
    val enabledNames by ClawSkillManager.enabledNames.collectAsStateWithLifecycle()
    val workspaceRoot by ClawSkillManager.workspaceRoot.collectAsStateWithLifecycle()

    if (skills.isEmpty()) {
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No OpenClaw skills found", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Place SKILL.md files in:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = workspaceRoot + "/skills/<name>/",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(8.dp),
            ) {
                item {
                    Text(
                        "${skills.size} skills, ${enabledNames.size} enabled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(skills) { skill ->
                    val isEnabled = skill.name in enabledNames
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (skill.emoji != null) {
                                        Text(skill.emoji, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                if (skill.requiresBins.isNotEmpty() || skill.requiresEnv.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        buildString {
                                            if (skill.requiresBins.isNotEmpty())
                                                append("bins: ${skill.requiresBins.joinToString(", ")}")
                                            if (skill.requiresEnv.isNotEmpty()) {
                                                if (isNotEmpty()) append("  ")
                                                append("env: ${skill.requiresEnv.size} vars")
                                            }
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { ClawSkillManager.setEnabled(skill.name, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

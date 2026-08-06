package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 技能与生态 — 合并页 (OpenClaw 技能 + 生态系统)
 * 原 SettingClawSkillsPage / SettingEcosystemPage 两个独立入口合并为一个。
 */
@Composable
fun SettingSkillsEcosystemPage() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeFlexibleTopAppBar(
                title = { Text("技能与生态") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("OpenClaw 技能") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("生态系统") },
                )
            }
            when (selectedTab) {
                0 -> ClawSkillsContent()
                1 -> EcosystemContent()
            }
        }
    }
}

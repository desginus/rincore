package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = settings.dynamicColor,
                        onCheckedChange = { vm.updateSettings { latest -> latest.copy(dynamicColor = it) } },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    switchItem(
                        checked = amoledDarkMode,
                        onCheckedChange = { amoledDarkMode = it },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                    )
                }
            }
            // v5.1.2（用户要求）：主对话页输入栏的两个键显隐
            item {
                CardGroup(
                    title = { Text("输入栏按钮") },
                ) {
                    switchItem(
                        checked = settings.showSendButton,
                        onCheckedChange = { vm.updateSettings { latest -> latest.copy(showSendButton = it) } },
                        supportingContent = { Text("关闭后输入栏不再显示发送键与终止键（语音模式与快捷键不受影响）") },
                        headlineContent = { Text("显示发送 / 终止键") },
                    )
                    switchItem(
                        checked = settings.showReasoningButton,
                        onCheckedChange = { vm.updateSettings { latest -> latest.copy(showReasoningButton = it) } },
                        supportingContent = { Text("关闭后输入栏不再显示思考程度键（可在助手设置里调整）") },
                        headlineContent = { Text("显示思考程度键") },
                    )
                }
            }
        }
    }
}

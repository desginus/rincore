package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionAllFiles
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionLocation
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(innerPadding = innerPadding, assistant = assistant, onUpdate = { vm.update(it) })
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(permission = Manifest.permission.READ_CALENDAR, displayName = { Text(stringResource(R.string.permission_calendar_read)) }, usage = { Text(stringResource(R.string.permission_calendar_read_desc)) }, required = true),
            PermissionInfo(permission = Manifest.permission.WRITE_CALENDAR, displayName = { Text(stringResource(R.string.permission_calendar_write)) }, usage = { Text(stringResource(R.string.permission_calendar_write_desc)) }, required = true),
        )
    )
    val locationPermissionState = rememberPermissionState(permissions = setOf(PermissionLocation))
    val allFilesPermissionState = rememberPermissionState(permissions = setOf(PermissionAllFiles))

    PermissionManager(permissionState = calendarPermissionState)
    PermissionManager(permissionState = locationPermissionState)
    PermissionManager(permissionState = allFilesPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        if (enabled && option == LocalToolOption.Location && !locationPermissionState.allPermissionsGranted) {
            locationPermissionState.requestPermissions()
            return
        }
        // Files 工具: ~/ 工作区无需权限，但建议授予 MANAGE_EXTERNAL_STORAGE 以访问 /sdcard/
        if (enabled && option == LocalToolOption.Files) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                toaster.show(
                    message = "文件管理工具已开启。~ 工作区路径无需额外权限；如需访问 /sdcard/ 路径，请在系统设置中授予「所有文件访问」权限。",
                    type = ToastType.Info
                )
            }
        }
        // Download 工具: MANAGE_EXTERNAL_STORAGE 让 DownloadManager 写公共下载目录
        if (enabled && option == LocalToolOption.Download) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                toaster.show(
                    message = "下载工具已开启。DownloadManager 自动下载到公共下载目录；如需 AI 写入 /sdcard/ 路径，请授予「所有文件访问」权限。",
                    type = ToastType.Info
                )
            }
        }
        val newLocalTools = if (enabled) assistant.localTools + option else assistant.localTools - option
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(innerPadding).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            // --- Core ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine), onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_time_info_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_time_info_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.TimeInfo), onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_clipboard_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Clipboard), onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_tts_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_tts_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Tts), onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_ask_user_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.AskUser), onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_screen_time_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.ScreenTime), onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_calendar_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_calendar_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Calendar), onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }) })
            // --- Automation ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.CronJobs), onCheckedChange = { toggleLocalTool(LocalToolOption.CronJobs, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_toast_notification_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_toast_notification_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.ToastAndNotification), onCheckedChange = { toggleLocalTool(LocalToolOption.ToastAndNotification, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_sub_agents_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_sub_agents_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.SubAgents), onCheckedChange = { toggleLocalTool(LocalToolOption.SubAgents, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_system_intents_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_system_intents_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.SystemIntents), onCheckedChange = { toggleLocalTool(LocalToolOption.SystemIntents, it) }) })
            // --- Device Sensing ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_location_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_location_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Location), onCheckedChange = { toggleLocalTool(LocalToolOption.Location, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_battery_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_battery_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Battery), onCheckedChange = { toggleLocalTool(LocalToolOption.Battery, it) }) })
            // --- Media Control ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_media_player_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_media_player_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.MediaPlayer), onCheckedChange = { toggleLocalTool(LocalToolOption.MediaPlayer, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_media_scanner_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_media_scanner_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.MediaScanner), onCheckedChange = { toggleLocalTool(LocalToolOption.MediaScanner, it) }) })
            // --- File Management ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_files_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_files_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Files), onCheckedChange = { toggleLocalTool(LocalToolOption.Files, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_download_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_download_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Download), onCheckedChange = { toggleLocalTool(LocalToolOption.Download, it) }) })
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_archive_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_archive_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.Archive), onCheckedChange = { toggleLocalTool(LocalToolOption.Archive, it) }) })
            // --- Cost Guard ---
            item(headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_cost_guards_title)) }, supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_cost_guards_desc)) }, trailingContent = { Switch(checked = assistant.localTools.contains(LocalToolOption.CostGuards), onCheckedChange = { toggleLocalTool(LocalToolOption.CostGuards, it) }) })
        }
    }
}

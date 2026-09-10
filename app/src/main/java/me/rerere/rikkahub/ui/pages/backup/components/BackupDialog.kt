package me.rerere.rikkahub.ui.pages.backup.components

/* ───【原版对齐】BackupDialog.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import kotlin.system.exitProcess

@Composable
fun BackupDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = { Text(stringResource(R.string.backup_page_restart_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    exitProcess(0)
                }
            ) {
                Text(stringResource(R.string.backup_page_restart_app))
            }
        },
    )
}

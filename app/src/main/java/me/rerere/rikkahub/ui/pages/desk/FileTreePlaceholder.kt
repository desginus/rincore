package me.rerere.rikkahub.ui.pages.desk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons

@Composable
fun FileTreePlaceholder(
    onFileSelect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val demoFiles = remember {
        listOf(
            "src/MainActivity.kt" to "package example\n\nclass MainActivity",
            "src/ViewModel.kt" to "package example\n\nclass ViewModel",
            "build.gradle.kts" to "plugins { id(\"com.android.application\") }",
            "README.md" to "# Project\nSample project.",
        )
    }

    LazyColumn(modifier = modifier.padding(8.dp)) {
        item {
            Text("工作区文件", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        items(demoFiles) { (path, content) ->
            Row(Modifier.clickable { onFileSelect(path, content) }
                .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(HugeIcons.File01, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(path, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

package me.rerere.rikkahub.ui.pages.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EDITOR_BG = Color(0xFF1E1E1E)
private val EDITOR_TEXT = Color(0xFFD4D4D4)
private val EDITOR_PLACEHOLDER = Color(0xFF6A6A6A)

@Composable
fun CodeEditorPanel(
    filePath: String?,
    content: String,
    language: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var localContent by remember { mutableStateOf(content) }

    LaunchedEffect(content) {
        if (localContent != content) localContent = content
    }

    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = filePath?.substringAfterLast('/') ?: "未打开文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = language.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(EDITOR_BG)) {
            BasicTextField(
                value = localContent,
                onValueChange = { v -> localContent = v; onContentChange(v) },
                textStyle = TextStyle(
                    color = EDITOR_TEXT, fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp, lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxSize().padding(8.dp),
                decorationBox = { inner ->
                    Box {
                        if (localContent.isEmpty())
                            Text("// 输入代码或让 AI 生成...", style = TextStyle(
                                color = EDITOR_PLACEHOLDER, fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                        inner()
                    }
                },
            )
        }
    }
}

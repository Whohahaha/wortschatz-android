package app.wortschatz.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wortschatz.BooksState
import app.wortschatz.ImportState
import app.wortschatz.StudyViewModel
import app.wortschatz.core.Entry
import app.wortschatz.core.imports.ColumnRole
import app.wortschatz.data.BookInfo
import app.wortschatz.data.BookKind
import app.wortschatz.core.BookProgress
import app.wortschatz.core.CatalogBook
import app.wortschatz.core.formatBytes

@Composable
fun BooksScreen(state: BooksState, vm: StudyViewModel, onPickFile: () -> Unit, onInstallVoice: () -> Unit) {
    var deleting by remember { mutableStateOf<BookInfo?>(null) }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("‹ 返回") }
                Text("词书", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Text("我的词书", style = MaterialTheme.typography.titleMedium)
            state.books.forEach { book ->
                BookCard(
                    book,
                    progress = state.progress[book.id],
                    selected = book.id == state.activeId,
                    update = state.update(book),
                    downloading = state.downloads[book.id],
                    onSelect = { vm.selectBook(book.id) },
                    onDelete = { deleting = book },
                    onUpdate = { state.update(book)?.let(vm::downloadBook) },
                    onCancel = { vm.cancelDownload(book.id) },
                )
            }
            Hint("切换词书不会丢进度：每本词书背过的词都单独记着，切回去接着背。没在学的词书，到期的复习会等你回来再做。")

            Text("下载词书", style = MaterialTheme.typography.titleMedium)
            when {
                state.catalogLoading && state.catalog == null ->
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.catalogError != null && state.catalog == null -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.catalogError.orEmpty())
                        OutlinedButton(onClick = { vm.loadCatalog() }) { Text("重试") }
                    }
                }
                state.catalog != null && state.available.isEmpty() -> Hint("可下载的词书都已经在手机上了。")
                else -> state.available.forEach { book ->
                    CatalogCard(book, downloading = state.downloads[book.id], onDownload = { vm.downloadBook(book) }, onCancel = { vm.cancelDownload(book.id) })
                }
            }

            Text("导入", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("导入词书", fontSize = 17.sp) }
            Hint(
                "支持 Anki 卡组（.apkg）、Excel 或 WPS 另存的 CSV / 制表符文本、Quizlet 导出的文本，以及本 App 的词书文件。" +
                    "也可以在其他 App 里把文件或一段单词表“分享”给本 App。"
            )

            Text("离线发音", style = MaterialTheme.typography.titleMedium)
            Hint(
                "内置词书的发音已经打包在 App 里，下载的词书连同发音一起下载。导入的词书用手机自带的德语语音：" +
                    "App 会在后台提前生成今天和明天要学的词的发音，学的时候不需要联网。"
            )
            if (state.germanVoice == false) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("手机还没有德语语音，导入的词书暂时没有发音。连上 Wi-Fi 下载一次德语语音包，之后就能离线使用。")
                        OutlinedButton(onClick = onInstallVoice) { Text("下载德语语音") }
                    }
                }
            }

            Hint("导入时补全冠词、复数和动词变位用的数据来自 Wiktionary（经 kaikki.org 整理），按 CC BY-SA 4.0 授权。")
        }
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除“${book.title}”？") },
            text = {
                Text(
                    if (book.kind == BookKind.DOWNLOADED) "词书和发音文件会从手机上删除，学习进度保留。以后重新下载，会接着之前的进度学。"
                    else "词书会从手机上删除。以后重新导入同一个文件，会当作新词书从头开始学。"
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteBook(book.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BookCard(
    book: BookInfo,
    progress: BookProgress?,
    selected: Boolean,
    update: CatalogBook?,
    downloading: Float?,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(book.title, fontWeight = FontWeight.SemiBold)
                    val kind = when (book.kind) {
                        BookKind.BUILT_IN -> "内置，自带发音"
                        BookKind.DOWNLOADED -> if (book.hasAudio) "已下载，带发音" else "已下载"
                        BookKind.IMPORTED -> "导入"
                    }
                    Text("${book.wordCount} 词 · $kind", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    progress?.takeIf { it.learned > 0 }?.let {
                        Text(
                            "已学 ${it.learned} / ${it.total}" + if (it.due > 0) " · ${it.due} 张待复习" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                when {
                    downloading != null -> TextButton(onClick = onCancel) { Text("取消") }
                    update != null -> TextButton(onClick = onUpdate) { Text("更新") }
                }
                if (!book.builtIn && downloading == null) TextButton(onClick = onDelete) { Text("删除") }
            }
            if (downloading != null) {
                LinearProgressIndicator(progress = { downloading }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else if (update != null) {
                Hint("有新版本，更新后进度不变", Modifier.padding(start = 48.dp))
            }
        }
    }
}

@Composable
private fun CatalogCard(book: CatalogBook, downloading: Float?, onDownload: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${book.words} 词 · ${formatBytes(book.bytes)}" + if (book.audio.isNotEmpty()) " · 含发音" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloading == null) Button(onClick = onDownload) { Text("下载") }
                else TextButton(onClick = onCancel) { Text("取消") }
            }
            if (book.description.isNotEmpty()) Hint(book.description)
            if (downloading != null) {
                LinearProgressIndicator(progress = { downloading }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun ImportScreen(state: ImportState, vm: StudyViewModel) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::openBooks) { Text("‹ 取消") }
                Text("导入词书", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("${state.fileName} · ${state.kindLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)

            val result = state.result
            if (result == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setImportTitle,
                label = { Text("词书名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!state.native) {
                Text("每一列是什么", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (state.canToggleHeader) {
                            SwitchRow("第一行是表头", state.hasHeader, vm::setImportHeader)
                        }
                        ColumnRole.entries.forEach { role ->
                            ColumnRow(role, state.mapping[role], state.headers) { vm.setImportColumn(role, it) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        SwitchRow("自动补全冠词、复数和动词变位", state.autoComplete && state.lexiconAvailable, vm::setImportAutoComplete, enabled = state.lexiconAvailable)
                        Hint(
                            if (state.lexiconAvailable) "只补词表里缺的；词表自己写了的以词表为准。"
                            else "这个版本没有带补全数据，只导入词表里写了的内容。",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            val summary = buildString {
                append("将导入 ${result.entries.size} 个词")
                if (result.completed > 0) append("，其中 ${result.completed} 个自动补全了语法")
                if (result.duplicates > 0) append("；跳过 ${result.duplicates} 个重复")
                if (result.skipped > 0) append("；跳过 ${result.skipped} 行没有德语或释义的")
                append("。")
            }
            Text(summary, fontWeight = FontWeight.SemiBold)
            if (!state.native && (ColumnRole.GERMAN !in state.mapping || ColumnRole.MEANING !in state.mapping)) {
                Text("请先指定“德语”和“释义”各在哪一列。", color = MaterialTheme.colorScheme.error)
            }

            if (result.entries.isNotEmpty()) {
                Text("预览", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.entries.take(8).forEach { PreviewRow(it) }
                    }
                }
            }

            Button(
                onClick = vm::confirmImport,
                enabled = !state.busy && result.entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("导入 ${result.entries.size} 个词", fontSize = 17.sp) }
        }
    }
}

@Composable
private fun PreviewRow(entry: Entry) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            entry.gender?.let { Text("${it.article} ", color = genderColor(it), fontWeight = FontWeight.Bold) }
            Text(entry.display.removePrefix(entry.gender?.article?.plus(" ") ?: ""), fontWeight = FontWeight.SemiBold)
            entry.pluralLabel?.let { Text("  · $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        entry.verbForms?.let {
            Text("${it.present3}, ${it.preterite}, ${it.participle}", style = MaterialTheme.typography.bodySmall)
        }
        Text(entry.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnRow(role: ColumnRole, column: Int?, headers: List<String>, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(role.label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(column?.let { headers.getOrNull(it) } ?: "不导入", maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("不导入") }, onClick = { open = false; onChange(null) })
                headers.forEachIndexed { i, h ->
                    DropdownMenuItem(text = { Text(h) }, onClick = { open = false; onChange(i) })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

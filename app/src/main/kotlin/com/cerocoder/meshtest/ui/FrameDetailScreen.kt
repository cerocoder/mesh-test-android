package com.cerocoder.meshtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.frame.DetailField
import com.cerocoder.meshtest.frame.FrameDecoder
import com.cerocoder.meshtest.frame.FrameRecord

/**
 * Содержимое одного кадра.
 *
 * Экран отдельный, а не окно поверх ленты: у `MeshPacket` двадцать два
 * собственных поля плюс разобранная нагрузка плюс дамп байтов — в окне это
 * пришлось бы резать. Побочно это выполняет требование «одновременно открыт
 * только один кадр»: ленты на экране нет, нажимать не по чему.
 */
@Composable
fun FrameDetailScreen(record: FrameRecord, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    var showDefaults by remember { mutableStateOf(false) }
    val decoder = remember { FrameDecoder() }
    val detail = remember(record, showDefaults) { decoder.decode(record, showDefaults) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("К ленте кадров")
        }

        Text(
            detail.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Switch(checked = showDefaults, onCheckedChange = { showDefaults = it })
            Text(
                "Показывать поля со значением по умолчанию",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(detail.common) { field -> FieldRow(field) }

            for (section in detail.sections) {
                item(key = section.title) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(section.fields) { field -> FieldRow(field) }
            }
        }
    }
}

@Composable
private fun FieldRow(field: DetailField) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            field.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            field.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.58f),
        )
    }
}

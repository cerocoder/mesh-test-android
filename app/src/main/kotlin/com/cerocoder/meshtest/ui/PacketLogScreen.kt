package com.cerocoder.meshtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.frame.FrameRecord
import com.cerocoder.meshtest.frame.frameSummary

@Composable
fun PacketLogScreen(
    packets: List<FrameRecord>,
    onSelect: (FrameRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()

    // Автопрокрутка только когда пользователь и так у конца ленты. Иначе поток кадров
    // вырывал бы экран из-под пальца: на сценарии с 200 нодами прокрутить вверх и
    // прочитать ранние кадры было бы невозможно.
    val atBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(packets.size) {
        if (packets.isNotEmpty() && atBottom) {
            listState.scrollToItem(packets.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("К списку устройств")
        }

        // Два числа, а не одно: размер ленты насыщается на 500, и после
        // насыщения перестаёт показывать, идёт ли приём вообще.
        Text(
            "Принято за подключение: ${packets.lastOrNull()?.seq ?: 0}   в ленте: ${packets.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
            items(packets, key = { it.seq }) { record ->
                Text(
                    frameSummary(record),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(record) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

package com.cerocoder.meshtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.frame.FrameRecord
import com.cerocoder.meshtest.frame.frameSummary

@Composable
fun PacketLogScreen(
    packets: List<FrameRecord>,
    listState: LazyListState,
    anchor: Long?,
    onAnchorChange: (Long?) -> Unit,
    onSelect: (FrameRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // Автопрокрутка только когда пользователь и так у конца ленты. Иначе поток кадров
    // вырывал бы экран из-под пальца: на сценарии с 200 нодами прокрутить вверх и
    // прочитать ранние кадры было бы невозможно.
    val atBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    // Пока якорь не восстановлен, автопрокрутка к новому кадру должна молчать.
    // Оба эффекта ниже стартуют в одной композиции: если кадр придёт как раз в
    // момент возврата с экрана кадра, без этой заслонки гонка между «прокрутить
    // к якорю» и «прокрутить к новому кадру» могла разрешиться в пользу конца
    // ленты — пользователь вместо сохранённого места увидел бы низ. Если
    // якоря нет (обычный вход в ленту, а не возврат), восстанавливать нечего,
    // и флаг стартует уже true — автопрокрутка работает без задержки, как и
    // раньше.
    var anchorRestored by remember { mutableStateOf(anchor == null) }

    LaunchedEffect(Unit) {
        val target = anchor
        if (target != null) {
            // Ищем по номеру кадра, а не по сохранённой позиции: пока экран
            // кадра был открыт, лента продолжала принимать и вытеснять, и
            // старый индекс указывал бы уже на другую строку. Не нашли —
            // кадр вытеснен целиком, показываем начало ленты.
            val index = packets.indexOfFirst { it.seq == target }
            listState.scrollToItem(if (index >= 0) index else 0)
            onAnchorChange(null)
        }
        anchorRestored = true
    }

    // Ключ — номер последнего кадра, а не размер ленты: размер упирается в
    // потолок ленты и перестаёт меняться, хотя кадры продолжают идти. На
    // размере автопрокрутка умирала ровно тогда, когда лента заполнялась.
    LaunchedEffect(packets.lastOrNull()?.seq) {
        if (anchorRestored && packets.isNotEmpty() && atBottom) {
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
                        .clickable {
                            // Якорь — первый видимый кадр, а не выбранный: после
                            // возврата лента должна показать ту же видимую область
                            // целиком, а не просто содержать где-то на экране
                            // строку, по которой кликнули.
                            onAnchorChange(packets.getOrNull(listState.firstVisibleItemIndex)?.seq)
                            onSelect(record)
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

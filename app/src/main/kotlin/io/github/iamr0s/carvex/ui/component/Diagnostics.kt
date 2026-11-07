package io.github.iamr0s.carvex.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/** 「会话诊断」：一眼看清分辨率协商结果和三路音频到底接没接上。
 *
 *  为什么要专门做一个面板：这两个问题（画面糊、声音分不开）在界面上都没有任何表征 ——
 *  画面糊是"看着不对"，声音混是"听着不对"，而唯一能判定的是几个数字：
 *  - 车机自报的显示尺寸、车机在 encode_format 里建议的尺寸、我们最终按多少编。
 *    三者一致才没有中间缩放；不一致就必然有一层放大，糊就是从那儿来的。
 *  - 每一路音频落在哪个 `type/purpose`。车机的音量是按媒体/导航/语音三路分的，
 *    三种不同的 type 才是真的分开；两路写进同一个 type，接收端只有一个上下文，就是混着走。
 *
 *  数据全部由会话层实测回填（`SessionState` 的 diag 字段），不解析日志文本 —— 日志会被刷掉。 */
@Composable
fun SessionDiagnostics(modifier: Modifier = Modifier) {
    val s by BridgeStore.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val live = s.id.isNotEmpty() && s.width > 0

    SectionLabel("会话诊断")
    Panel(modifier) {
        if (!live) {
            Text(
                "还没有建立过视频会话。连上车机之后，这里会显示车机分辨率协商结果和三路音频的连接状态。",
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            return@Panel
        }
        KeyValueRow(
            "车机自报显示",
            if (s.receiverWidth > 0) "${s.receiverWidth}×${s.receiverHeight}" else "未上报",
        )
        KeyValueRow(
            "CarWith 建议编码",
            if (s.suggestedWidth > 0) {
                "${s.suggestedWidth}×${s.suggestedHeight}" +
                    (if (s.aspectPercent >= 0) "（宽高比差 ${s.aspectPercent}%）" else "")
            } else {
                "未建议"
            },
        )
        KeyValueRow(
            "实际编码画布",
            "${s.width}×${s.height}" + (if (s.suggestionAccepted) "（采用建议）" else "（采用车机自报）"),
        )
        KeyValueRow("编码器", s.encoderName.ifBlank { "尚未选择" })
        KeyValueRow(
            "码率 / 帧率",
            if (s.targetBitrate > 0) {
                "%.2f Mbps · %d fps".format(s.targetBitrate / 1_000_000f, s.targetFps)
            } else {
                "尚未确定"
            },
        )
        KeyValueRow(
            "画面是否缩放",
            if (s.receiverWidth > 0 && s.receiverWidth != s.width) {
                "${s.receiverWidth}×${s.receiverHeight} → ${s.width}×${s.height}，中间有一次缩放"
            } else if (s.receiverWidth > 0) {
                "1:1，没有缩放"
            } else {
                "未知"
            },
        )

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "音频三路", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            )
            if (s.audioLanes.isEmpty()) {
                Text("音频流尚未建立", color = cs.onSurfaceVariant)
            } else {
                s.audioLanes.forEach { line -> AudioLaneLine(line) }
            }
            // 三种不同的 type 才可能对应车机的三个音量；这里把车机给的位置摊开来看。
            if (s.audioRoutes.isNotEmpty()) {
                Text(
                    "车机提供的音频路由（共 ${s.audioRoutes.size} 条）",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                s.audioRoutes.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 一行一路音频。没接上的用错误色，因为那正是要一眼看到的那个。 */
@Composable
private fun AudioLaneLine(line: String) {
    val cs = MaterialTheme.colorScheme
    val bad = "未建立" in line || "未完成" in line || "已断开" in line
    Text(
        line,
        color = if (bad) cs.error else Color.Unspecified,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

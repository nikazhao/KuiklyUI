/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.demo.pages.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.text.BasicText
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

/**
 * 微博发布器 @人 功能验证页（Compose DSL / AnnotatedString 验证版）
 *
 * 目标：验证两项核心能力
 *  1. @人 文本高亮（方案 A：显示层 BasicText + 真输入框 BasicTextField）
 *  2. 两段式删除（先选中 @人，再按一次才真正删除）
 *
 * 技术方案见根目录 MentionPublisher-Design.md。
 *
 * 当前进度：步骤4 —— 运行时已确认方案 C 不生效：Kuikly 当前未消费
 * `TextFieldValue.annotatedString` 上的 spanStyles；现切到方案 A 做最小双层验证。
 */
@Page("MentionPublisherDemo")
class MentionPublisherDemo : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent {
            MentionPublisherScreen()
        }
    }
}

/**
 * Mention 元数据。约束：editorValue.text.substring(start, end) == displayName
 */
private data class Mention(
    val userId: String,
    val displayName: String,   // 例如 "@张三"
    val start: Int,
    val end: Int,              // exclusive
)

private sealed class DeleteState {
    data object Normal : DeleteState()
    data class MentionSelected(val mention: Mention) : DeleteState()
}

private data class TextChange(
    val start: Int,
    val oldEnd: Int,
    val newEnd: Int,
) {
    val delta: Int get() = newEnd - oldEnd
}

/** @人 高亮色（微博蓝） */
private val MentionHighlightColor = Color(0xFF5B7FB5)
// 两层只用相同的字号；lineHeight 不显式设——BasicTextField 内部对 lineHeight 既挂
// HRLineHeightSpan 又调原生 setLineHeight，与 BasicText（只挂 span）算法不一致，
// 显式设 lineHeight 反而会让 5 行以上累积错位。让两边都用各自默认 fontMetrics 算行高。
private val EditorTextStyle = TextStyle(fontSize = 16.sp, color = Color.Black)
private val HiddenInputTextStyle = TextStyle(fontSize = 16.sp, color = Color.Transparent)

/**
 * 方案 A：显示层单独渲染带 SpanStyle 的 AnnotatedString，真输入框只负责键盘/光标/选区。
 * 这里只做最小验证：mention 区间合法且文本仍匹配 displayName 时才补样式。
 */
private fun buildHighlightedText(
    text: String,
    mentions: List<Mention>,
): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        mentions.forEach { mention ->
            if (
                mention.start >= 0 &&
                mention.end <= text.length &&
                mention.start < mention.end &&
                text.substring(mention.start, mention.end) == mention.displayName
            ) {
                addStyle(
                    style = SpanStyle(color = MentionHighlightColor),
                    start = mention.start,
                    end = mention.end,
                )
            }
        }
    }
}

private fun calculateTextChange(oldText: String, newText: String): TextChange {
    val maxPrefix = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) {
        prefix++
    }

    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    var suffix = 0
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }

    return TextChange(
        start = prefix,
        oldEnd = oldText.length - suffix,
        newEnd = newText.length - suffix,
    )
}

private fun reconcileMentionsAfterTextChange(
    oldText: String,
    newText: String,
    mentions: List<Mention>,
): List<Mention> {
    if (oldText == newText) {
        return mentions
    }
    val change = calculateTextChange(oldText, newText)
    return mentions.mapNotNull { mention ->
        when {
            mention.end <= change.start -> mention
            mention.start >= change.oldEnd -> mention.copy(
                start = mention.start + change.delta,
                end = mention.end + change.delta,
            )
            else -> null
        }
    }.filter { mention ->
        mention.start >= 0 &&
            mention.end <= newText.length &&
            mention.start < mention.end &&
            newText.substring(mention.start, mention.end) == mention.displayName
    }
}

private fun removeMentionAndShift(
    mentions: List<Mention>,
    mentionToRemove: Mention,
): List<Mention> {
    val shift = mentionToRemove.end - mentionToRemove.start
    return mentions.mapNotNull { mention ->
        when {
            mention == mentionToRemove -> null
            mention.start >= mentionToRemove.end -> mention.copy(
                start = mention.start - shift,
                end = mention.end - shift,
            )
            else -> mention
        }
    }
}

private fun removeMentionText(text: String, mention: Mention): String {
    return text.removeRange(mention.start, mention.end)
}

private fun findMentionEndingAt(mentions: List<Mention>, cursor: Int): Mention? {
    return mentions.lastOrNull { it.end == cursor }
}

/**
 * 光标是否落在某个 mention 的区间内部或尾部（用于拦截 mention 内部的删除操作）。
 */
private fun findMentionContainingOrEndingAt(mentions: List<Mention>, cursor: Int): Mention? {
    return mentions.lastOrNull { cursor > it.start && cursor <= it.end }
}

private fun findMentionByRange(mentions: List<Mention>, selection: TextRange): Mention? {
    return mentions.lastOrNull { it.start == selection.min && it.end == selection.max }
}

private fun deleteStateLabel(deleteState: DeleteState): String {
    return when (deleteState) {
        DeleteState.Normal -> "Normal"
        is DeleteState.MentionSelected -> {
            "MentionSelected(${deleteState.mention.displayName},[${deleteState.mention.start},${deleteState.mention.end}])"
        }
    }
}

@Composable
private fun MentionPublisherScreen() {
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var mentions by remember { mutableStateOf(listOf<Mention>()) }
    var deleteState by remember { mutableStateOf<DeleteState>(DeleteState.Normal) }

    fun syncDeleteStateWithSelection(selection: TextRange, currentMentions: List<Mention>) {
        deleteState = findMentionByRange(currentMentions, selection)?.let {
            DeleteState.MentionSelected(it)
        } ?: DeleteState.Normal
    }

    /**
     * 在当前光标处插入一个 Mention：插入 "@人 "（带尾空格），后移其后的 Mention，
     * 并把光标移到插入内容之后（空格后）。
     */
    fun insertMention(userId: String, displayName: String) {
        val cursor = editorValue.selection.end.coerceIn(0, editorValue.text.length)
        val insertText = "$displayName "   // "@张三 "
        val mentionLen = displayName.length   // 仅 @人 长度，不含尾空格
        val newText = editorValue.text.substring(0, cursor) +
            insertText +
            editorValue.text.substring(cursor)

        // 新 Mention：start=cursor, end=cursor+mentionLen
        val newMention = Mention(userId, displayName, cursor, cursor + mentionLen)
        // 其后（start >= cursor）的 Mention 整体后移 insertText.length
        val shift = insertText.length
        val updatedMentions = mentions
            .filter { it.end <= cursor || it.start >= cursor }
            // 仅保留不与插入点重叠的 mention（命中内部已在 onValueChange 降级，这里兜底）
            .map {
                if (it.start >= cursor) it.copy(start = it.start + shift, end = it.end + shift)
                else it
            } + newMention

        mentions = updatedMentions
        editorValue = TextFieldValue(
            text = newText,
            selection = TextRange(cursor + insertText.length),
        )
        deleteState = DeleteState.Normal
    }

    fun handleValueChange(newValue: TextFieldValue) {
        val oldValue = editorValue
        val oldMentions = mentions
        val selectedMention = (deleteState as? DeleteState.MentionSelected)?.mention
        val hitMention = if (oldValue.selection.collapsed) {
            findMentionContainingOrEndingAt(oldMentions, oldValue.selection.start)
        } else {
            null
        }

        val isDeleteAction = newValue.text.length < oldValue.text.length
        val isSingleCharDelete = newValue.text.length == oldValue.text.length - 1
        val inComposition = oldValue.composition != null || newValue.composition != null

        if (
            !inComposition &&
            oldValue.selection.collapsed &&
            isSingleCharDelete &&
            hitMention != null
        ) {
            editorValue = oldValue.copy(selection = TextRange(hitMention.start, hitMention.end))
            deleteState = DeleteState.MentionSelected(hitMention)
            return
        }

        if (
            !inComposition &&
            selectedMention != null &&
            oldValue.selection.min == selectedMention.start &&
            oldValue.selection.max == selectedMention.end &&
            isDeleteAction
        ) {
            val expectedTextAfterMentionRemoval = removeMentionText(oldValue.text, selectedMention)
            val isConfirmedMentionDeletion =
                newValue.text == expectedTextAfterMentionRemoval &&
                    newValue.selection.collapsed &&
                    newValue.selection.start == selectedMention.start

            if (isConfirmedMentionDeletion) {
                val updatedMentions = removeMentionAndShift(oldMentions, selectedMention)
                mentions = updatedMentions
                editorValue = newValue
                syncDeleteStateWithSelection(newValue.selection, updatedMentions)
            } else {
                editorValue = oldValue
                deleteState = DeleteState.MentionSelected(selectedMention)
            }
            return
        }

        val updatedMentions = reconcileMentionsAfterTextChange(
            oldText = oldValue.text,
            newText = newValue.text,
            mentions = oldMentions,
        )
        mentions = updatedMentions
        editorValue = newValue
        syncDeleteStateWithSelection(newValue.selection, updatedMentions)
    }

    val highlightedText = remember(editorValue.text, mentions) {
        buildHighlightedText(
            text = editorValue.text,
            mentions = mentions,
        )
    }
    val mentionEndingAtCursor = if (editorValue.selection.collapsed) {
        findMentionContainingOrEndingAt(mentions, editorValue.selection.start)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "发布器验证 (@人)",
            fontSize = 20.sp,
        )

        Spacer(Modifier.height(12.dp))

        // 双层输入框：显示层负责高亮，真实输入层负责键盘/光标。
        // 高度从固定 120dp 改为 heightIn(120..240)：避免 5 行以上文本溢出框外。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .background(Color(0xFFF2F2F2))
                .padding(8.dp),
        ) {
            BasicText(
                text = highlightedText,
                modifier = Modifier.fillMaxSize(),
                style = EditorTextStyle,
            )
            BasicTextField(
                value = editorValue,
                onValueChange = ::handleValueChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = HiddenInputTextStyle,
                cursorBrush = SolidColor(Color.Black),
            )
        }

        Spacer(Modifier.height(12.dp))

        // 插入按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "插入@张三",
                modifier = Modifier
                    .clickable { insertMention("u_zhangsan", "@张三") }
                    .background(Color(0xFFE6F0FF))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                text = "插入@李四",
                modifier = Modifier
                    .clickable { insertMention("u_lisi", "@李四") }
                    .background(Color(0xFFE6F0FF))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // 调试区
        Text("调试信息", color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text("text = \"${editorValue.text}\"")
        Text("selection = [${editorValue.selection.start}, ${editorValue.selection.end}]")
        Text("mentions = ${mentions.joinToString { "(${it.displayName},[${it.start},${it.end}])" }}")
        Text("hitMentionEnd = ${mentionEndingAtCursor?.let { "${it.displayName}[${it.start},${it.end}]" } ?: "none"}")
        Text("deleteState = ${deleteStateLabel(deleteState)}")
    }
}

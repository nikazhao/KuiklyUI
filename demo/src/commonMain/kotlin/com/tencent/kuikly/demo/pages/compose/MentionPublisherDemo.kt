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
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.input.OffsetMapping
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.input.TransformedText
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

/**
 * 微博发布器 @人 功能验证页（Compose DSL / AnnotatedString 验证版）
 *
 * 目标：验证两项核心能力
 *  1. @人 文本高亮（VisualTransformation + AnnotatedString）
 *  2. 两段式删除（先选中 @人，再按一次才真正删除）
 *
 * 技术方案见根目录 MentionPublisher-Design.md。
 *
 * 当前进度：步骤3 —— @人高亮（VisualTransformation + AnnotatedString + SpanStyle）已实现，
 * 两段式删除待后续步骤。
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

/** @人 高亮色（微博蓝） */
private val MentionHighlightColor = Color(0xFF5B7FB5)

@Composable
private fun MentionPublisherScreen() {
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var mentions by remember { mutableStateOf(listOf<Mention>()) }

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

        editorValue = TextFieldValue(
            text = newText,
            selection = TextRange(cursor + insertText.length),
        )
        mentions = updatedMentions
    }

    // @人 高亮：VisualTransformation 只改显示层样式，不碰真实文本。
    // mentions 变化时重建，保证高亮跟随。
    val mentionHighlight = remember(mentions) {
        VisualTransformation { text ->
            val annotated = buildAnnotatedString {
                append(text.text)
                mentions.forEach { m ->
                    addStyle(
                        style = SpanStyle(color = MentionHighlightColor),
                        start = m.start,
                        end = m.end,
                    )
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
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

        // 输入框
        BasicTextField(
            value = editorValue,
            onValueChange = { editorValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFFF2F2F2)),
            visualTransformation = mentionHighlight,
        )

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
        Text("deleteState = Normal (待实现)")
    }
}

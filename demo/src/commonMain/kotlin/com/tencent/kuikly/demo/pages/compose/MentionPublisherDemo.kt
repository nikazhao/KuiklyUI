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
 * 微博发布器 @人 功能验证页（Compose DSL / 路线 F：单层 BasicTextField + 原生 ForegroundColorSpan）
 *
 * 目标：对齐官方 Kotlin Compose WeiboMentionDemo ——
 *  1. @人 文本高亮：用 BasicTextField(value = tfv.copy(annotatedString = displayText))，
 *     displayText 是带蓝色 SpanStyle 的 AnnotatedString；底层原生 EditText 由
 *     KRTextFieldView 打 ForegroundColorSpan 渲染（框架桥接，见 CoreTextField/KRTextFieldView）。
 *  2. 两段式删除：先选中整段 @人，再按一次才真正删除。
 *  3. @ 候选下拉：光标前出现 @query 时弹候选，选中插入 @昵称 （带尾空格）。
 *
 * 数据模型对齐官方：mentions 每次 onValueChange 用 scanMentions 正则重扫，下标自动正确不漂移。
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

/** @人 高亮色（微博蓝） */
private val MentionHighlightColor = Color(0xFF5B7FB5)

/** 已知候选名单：name -> userId */
private val KNOWN_MENTIONS = listOf(
    "张三" to "u_zhangsan",
    "李四" to "u_lisi",
    "王五" to "u_wangwu",
    "赵六" to "u_zhaoliu",
)

/**
 * Mention 元数据。约束：text.substring(start, end) == displayName
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

/**
 * 正则重扫：在 text 中找出所有已知 @昵称 的出现位置，生成 Mention 列表。
 * 对齐官方思路——每次文本变化后重扫，下标自动正确，无需手动前后移。
 */
private fun scanMentions(text: String): List<Mention> {
    val result = mutableListOf<Mention>()
    for ((name, userId) in KNOWN_MENTIONS) {
        val token = "@$name"
        var from = 0
        while (true) {
            val pos = text.indexOf(token, from)
            if (pos < 0) break
            result.add(Mention(userId, token, pos, pos + token.length))
            from = pos + token.length
        }
    }
    result.sortBy { it.start }
    return result
}

/** 光标是否落在某个 mention 区间内部或尾部（用于拦截 mention 内部的删除）。 */
private fun findMentionContainingOrEndingAt(mentions: List<Mention>, cursor: Int): Mention? {
    return mentions.lastOrNull { cursor > it.start && cursor <= it.end }
}

private fun findMentionByRange(mentions: List<Mention>, selection: TextRange): Mention? {
    return mentions.lastOrNull { it.start == selection.min && it.end == selection.max }
}

/**
 * 构造带高亮的展示文本：纯文本 + 对每个 mention 区间加蓝色 SpanStyle。
 * 仅在区间合法且文本仍匹配 displayName 时才加样式。
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

/**
 * 检测光标前是否有 @ 触发：向回找 @，中间不能有空格；@ 前必须是文本起点或空格；
 * 且 @ 不能落在某个已有 mention 内部（避免对已完成的 @人 重复弹候选）。
 * 返回 @ 的下标，或 null。
 */
private fun detectMentionTrigger(
    text: String,
    cursor: Int,
    mentions: List<Mention>,
): Int? {
    if (cursor <= 0) return null
    var i = cursor - 1
    while (i >= 0 && text[i] != '@' && !text[i].isWhitespace()) {
        i--
    }
    if (i < 0 || text[i] != '@') return null
    // @ 落在已有 mention 内部则不触发（避免对已完成的 @人 重复弹候选）
    if (mentions.any { it.start <= i && i < it.end }) return null
    return i
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
    // editorValue 始终保存“纯文本态”TextFieldValue（来自原生回传 / 插入构造）；
    // displayText 只在传给 BasicTextField 的 value= 处通过 copy(annotatedString=) 注入。
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var mentions by remember { mutableStateOf(listOf<Mention>()) }
    var deleteState by remember { mutableStateOf<DeleteState>(DeleteState.Normal) }
    // 诊断：每次 onValueChange 记录 first-delete 检查的子条件，便于排查拦截为何未触发
    var firstDeleteTrace by remember { mutableStateOf("n/a") }

    fun handleValueChange(newValue: TextFieldValue) {
        val oldValue = editorValue
        val oldMentions = mentions
        val selectedMention = (deleteState as? DeleteState.MentionSelected)?.mention
        val inComposition = oldValue.composition != null || newValue.composition != null
        val isDeleteAction = newValue.text.length < oldValue.text.length
        val isSingleCharDelete = newValue.text.length == oldValue.text.length - 1

        // 中文输入法组合态：不做 mention 删除判定，直接透传 + 重扫
        if (inComposition) {
            editorValue = newValue
            mentions = scanMentions(newValue.text)
            deleteState = DeleteState.Normal
            return
        }

        // 第一次删 mention：光标态 + 删除动作 + 待删字符（cursor-1）落在某个 mention 文本区间 [start,end) 内 → 改为选中整段，不删
        val collapsed = oldValue.selection.collapsed
        val cursorStart = oldValue.selection.start
        if (collapsed && isDeleteAction && cursorStart > 0) {
            val aboutToDeletePos = cursorStart - 1
            val hit = oldMentions.lastOrNull { it.start <= aboutToDeletePos && aboutToDeletePos < it.end }
            firstDeleteTrace = "collapsed=$collapsed isDel=$isDeleteAction start=$cursorStart aboutDel=$aboutToDeletePos hit=${hit?.displayName ?: "null"}"
            if (hit != null) {
                editorValue = oldValue.copy(selection = TextRange(hit.start, hit.end))
                deleteState = DeleteState.MentionSelected(hit)
                return
            }
        } else {
            firstDeleteTrace = "collapsed=$collapsed isDel=$isDeleteAction start=$cursorStart (skip: 条件不满足)"
        }

        // 第二次删 mention：选中态覆盖整段 + 确认删除 → 接受结果 + 重扫
        if (
            selectedMention != null &&
            oldValue.selection.min == selectedMention.start &&
            oldValue.selection.max == selectedMention.end &&
            isDeleteAction
        ) {
            editorValue = newValue
            mentions = scanMentions(newValue.text)
            deleteState = DeleteState.Normal
            return
        }

        // 普通编辑：接受 + 重扫；若新选区恰好覆盖某 mention 则同步选中态
        editorValue = newValue
        mentions = scanMentions(newValue.text)
        deleteState = findMentionByRange(mentions, newValue.selection)?.let {
            DeleteState.MentionSelected(it)
        } ?: DeleteState.Normal
    }

    /** 候选下拉选中：用 @昵称 （带尾空格）替换光标前的 @query。 */
    fun insertMentionAt(atPos: Int, name: String, userId: String) {
        val cursor = editorValue.selection.end
        val token = "@$name "
        val newText = editorValue.text.substring(0, atPos) + token + editorValue.text.substring(cursor)
        val newCursor = atPos + token.length
        editorValue = TextFieldValue(text = newText, selection = TextRange(newCursor))
        mentions = scanMentions(newText)
        deleteState = DeleteState.Normal
    }

    /** 插入按钮：在当前光标处插入 @昵称 （带尾空格）。 */
    fun insertMention(name: String, userId: String) {
        val cursor = editorValue.selection.end.coerceIn(0, editorValue.text.length)
        val token = "@$name "
        val newText = editorValue.text.substring(0, cursor) + token + editorValue.text.substring(cursor)
        val newCursor = cursor + token.length
        editorValue = TextFieldValue(text = newText, selection = TextRange(newCursor))
        mentions = scanMentions(newText)
        deleteState = DeleteState.Normal
    }

    val displayText = remember(editorValue.text, mentions) {
        buildHighlightedText(editorValue.text, mentions)
    }
    val triggerPos = if (editorValue.selection.collapsed) {
        detectMentionTrigger(editorValue.text, editorValue.selection.start, mentions)
    } else {
        null
    }
    val query = triggerPos?.let { editorValue.text.substring(it + 1, editorValue.selection.start) }
    val candidates = query?.let { q ->
        KNOWN_MENTIONS.filter { it.first.startsWith(q) }
    } ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "发布器验证 (@人 · 单层原生span)",
            fontSize = 20.sp,
        )

        Spacer(Modifier.height(12.dp))

        // 单层输入框：高亮由原生 ForegroundColorSpan 渲染（框架桥接）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .background(Color(0xFFF2F2F2))
                .padding(8.dp),
        ) {
            BasicTextField(
                value = editorValue.copy(annotatedString = displayText),
                onValueChange = ::handleValueChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                cursorBrush = SolidColor(Color.Black),
            )
        }

        // @ 候选下拉
        if (triggerPos != null && candidates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("候选：", color = Color.Gray)
                candidates.forEach { (name, uid) ->
                    Text(
                        text = "@$name",
                        modifier = Modifier
                            .clickable { insertMentionAt(triggerPos, name, uid) }
                            .background(Color(0xFFE6F0FF))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MentionHighlightColor,
                    )
                }
            }
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
                    .clickable { insertMention("张三", "u_zhangsan") }
                    .background(Color(0xFFE6F0FF))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                text = "插入@李四",
                modifier = Modifier
                    .clickable { insertMention("李四", "u_lisi") }
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
        Text("trigger = ${triggerPos?.let { "@$it(q=\"$query\")" } ?: "none"}")
        Text("deleteState = ${deleteStateLabel(deleteState)}")
        Text("firstDelete = $firstDeleteTrace")
    }
}

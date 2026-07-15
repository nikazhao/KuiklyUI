package com.tencent.kuikly.demo.pages.compose

import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.drawBehind
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.PathEffect
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

/**
 * 官方 Jetpack Compose 5 场景虚线验证的 Kuikly 移植版。
 * 官方原工程：/Users/zhaozining/CodeBuddy/20260615095947/DashedLineVerify/MainActivity.kt
 *
 * 对齐情况（对照 docs/DevGuide/kuikly-compose-drawBehind-pathEffect-Design.md）：
 * - 场景1 / 3 / 5：官方代码原样移植（仅 import androidx→com.tencent.kuikly），走 drawBehind+pathEffect 通道，1:1 对齐。
 * - 场景2 / 4：官方写法依赖 TextLayoutResult.getBoundingBox / getLineBottom / lineCount，
 *   这几个 API 在 Kuikly 的 TextLayoutResult.kt:421-531 仍是注释、MultiParagraph 是空壳（lineCount=0），
 *   官方代码粘进来编译不过 → 本页标注“未对齐”，待实现行度量 API 后再补。
 */
@Page("DashedUnderlineDemo")
class DashedUnderlineDemo : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                item {
                    Text(
                        "Jetpack Compose 虚线验证（Kuikly 移植版）",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // ==================== 场景1：Text + drawBehind 整行虚线 ====================
                item {
                    Text("场景1: Text + drawBehind 整行虚线（官方 1:1 对齐）", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    DashedUnderline_Text()
                    Spacer(Modifier.height(24.dp))
                }

                // ==================== 场景2：纯 Text + drawBehind 只画局部虚线 ====================
                item {
                    Text("场景2: 纯 Text 局部虚线（官方 1:1 未对齐 · 阻塞）", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    DashedUnderline_Text_Span()
                    Spacer(Modifier.height(24.dp))
                }

                // ==================== 场景3：实线下划线对照（TextDecoration.Underline） ====================
                item {
                    Text("场景3: 实线下划线对照（TextDecoration.Underline，已对齐）", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这是整行实线下划线（Underline）",
                        textDecoration = TextDecoration.Underline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildAnnotatedString {
                            append("普通文字，")
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                                append("这里是 Span 实线下划线")
                            }
                            append("。")
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // ==================== 场景4：多行折行文本逐行虚线 ====================
                item {
                    Text("场景4: 多行折行文本逐行虚线（官方 1:1 未对齐 · 阻塞）", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    DashedUnderline_Text_MultiLine()
                    Spacer(Modifier.height(24.dp))
                }

                // ==================== 场景5：不同线宽 / 疏密间隔的虚线 ====================
                item {
                    Text("场景5: 不同线宽 / 疏密间隔的虚线对比（官方 1:1 对齐）", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    DashedUnderline_Text_Pattern(8.dp, 4.dp, 1.dp, Color.Red, "细虚线 8-4")
                    Spacer(Modifier.height(8.dp))
                    DashedUnderline_Text_Pattern(12.dp, 6.dp, 2.dp, Color.Blue, "中虚线 12-6")
                    Spacer(Modifier.height(8.dp))
                    DashedUnderline_Text_Pattern(16.dp, 8.dp, 3.dp, Color.Green, "粗虚线 16-8")
                }
            }
        }
    }
}

/**
 * 场景1：Text 组件 + drawBehind 直接画虚线（官方写法原样移植，仅换 import）。
 */
@Composable
fun DashedUnderline_Text() {
    Text(
        text = "这段文字下方有红色虚线（Text + drawBehind）",
        modifier = Modifier.drawBehind {
            drawLine(
                color = Color.Red,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(8f, 4f),
                    phase = 0f
                )
            )
        }
    )
}

/**
 * 场景2：纯 Text + drawBehind 只在局部画虚线。
 *
 * 官方写法（Kuikly 编译不过 —— TextLayoutResult.getBoundingBox 未实现）：
 * ```
 * var spanRect by remember { mutableStateOf<Rect?>(null) }
 * Text(
 *     text = buildAnnotatedString { append(full.substring(0, spanStart)); append(...) },
 *     onTextLayout = { result ->
 *         val start = result.getBoundingBox(spanStart)      // ← Kuikly 注释态
 *         val end = result.getBoundingBox(spanEnd - 1)      // ← Kuikly 注释态
 *         spanRect = Rect(start.left, start.top, end.right, start.bottom)
 *     },
 *     modifier = Modifier.drawBehind { spanRect?.let { drawLine(... pathEffect=...) } }
 * )
 * ```
 * 阻塞根因：compose/.../ui/text/TextLayoutResult.kt:421-531 的 getBoundingBox/getLineBottom/
 * lineCount 全是注释，MultiParagraph 是空壳。要 1:1 对齐需先实现行度量 API（三端原生文本层桥接）。
 * 本页先用红字标出“预期加线范围”，不伪造虚线。
 */
@Composable
fun DashedUnderline_Text_Span() {
    Text("⚠️ Kuikly 未实现 TextLayoutResult.getBoundingBox，局部虚线暂不能 1:1 对齐官方。")
    Spacer(Modifier.height(4.dp))
    Text(
        buildAnnotatedString {
            append("这是一段示例文字，")
            withStyle(SpanStyle(color = Color.Red)) {
                append("纯 Text")
            }
            append(" 方案只在局部画虚线（红字 = 官方预期加虚线的 span 范围）")
        }
    )
}

/**
 * 场景4：多行折行文本逐行画虚线。
 *
 * 官方写法（Kuikly 编译不过 —— lineCount=0、getLineBottom 未实现）：
 * ```
 * var lineBottoms by remember { mutableStateOf<List<Float>>(emptyList()) }
 * Text(text = "...", onTextLayout = { result ->
 *     lineBottoms = (0 until result.lineCount).map { result.getLineBottom(it) }  // ← Kuikly 注释态
 * }, modifier = Modifier.drawBehind { lineBottoms.forEach { drawLine(... pathEffect=...) } })
 * ```
 * 阻塞根因同场景2。本页先展示文本本身，不伪造虚线。
 */
@Composable
fun DashedUnderline_Text_MultiLine() {
    Text("⚠️ Kuikly 未实现 TextLayoutResult.getLineBottom/lineCount，多行逐行虚线暂不能 1:1 对齐官方。")
    Spacer(Modifier.height(4.dp))
    Text("这是一段会换行的长文本，用来验证多行文本时虚线下划线是否每行都正确画出，而不是只在最底部画一条横线。")
}

/**
 * 场景5：自定义虚线形态（官方写法原样移植，仅换 import）。传入不同线段长/间隔/线宽验证 pathEffect 可调。
 */
@Composable
fun DashedUnderline_Text_Pattern(dash: Dp, gap: Dp, stroke: Dp, color: Color, caption: String) {
    Text(caption, fontSize = 12.sp, color = Color.Gray)
    Text(
        text = "这段文字下方是 $caption 的虚线",
        modifier = Modifier.drawBehind {
            drawLine(
                color = color,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = stroke.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dash.toPx(), gap.toPx()),
                    phase = 0f
                )
            )
        }
    )
}

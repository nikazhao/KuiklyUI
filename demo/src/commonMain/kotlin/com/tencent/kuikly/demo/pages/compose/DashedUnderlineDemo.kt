package com.tencent.kuikly.demo.pages.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.extension.textPostProcessor
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.drawBehind
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.PathEffect
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

@Page("DashedUnderlineDemo")
class DashedUnderlineDemo : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).background(Color.White)
            ) {
                // ============================================
                // 场景1：实线下划线（验证 Kuikly 文本基线能力）
                // ============================================
                Text("场景1: 实线下划线 (TextDecoration.Underline)")
                Spacer(Modifier.height(4.dp))
                Text(
                    "这是实线下划线（Underline）",
                    textDecoration = TextDecoration.Underline
                )

                Spacer(Modifier.height(24.dp))

                // ============================================
                // 场景2：AnnotatedString + SpanStyle 实下划线
                // 仅给文本中的一小段加下划线（Span 级别），其余文字不加。
                // ============================================
                Text("场景2: AnnotatedString + SpanStyle 下划线（验证 Span 链路）")
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        append("这是一段普通文字，")
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                            append("这里是实线下划线")
                        }
                        append("。")
                    }
                )

                Spacer(Modifier.height(24.dp))

                // ============================================
                // 场景3：官方 1:1 写法 — Text + drawBehind + PathEffect.dashPathEffect
                // 说明：从官方 demo 逐字复制，只换 import（androidx.compose.* → com.tencent.kuikly.compose.*），
                // 业务代码一行不动。Phase 1+2 合并验证：PathEffect 通路 + bg Canvas 注入/路由全打通。
                // 期望：Text 下方出现红色虚线。
                // ============================================
                Text("场景3: 官方 1:1 写法 — Text + drawBehind + PathEffect.dashPathEffect（红色虚线）")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "这是 Text + drawBehind + PathEffect.dashPathEffect 官方 1:1 写法（从官方 demo 逐字复制，仅换 import，业务代码一行不动）。多行文本下方也应能画完整虚线，验证多行 frame 同步。",
                    modifier = Modifier.drawBehind {
                        drawLine(
                            color = Color.Red,
                            start = Offset(0f, size.height - 2.dp.toPx()),
                            end = Offset(size.width, size.height - 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(8f, 4f),
                                phase = 0f
                            )
                        )
                    }
                )

                Spacer(Modifier.height(24.dp))

                // ============================================
                // 场景4：文本虚线下划线（跨端统一演示）
                // 说明：Kuikly 的 Text 装饰没有虚线，但 Compose 提供
                // Modifier.textPostProcessor("dashed")。三端共用一个入口组件
                // DashedUnderlineText，由各自原生适配器实现：
                //   - Android：走原生 KRTextPostProcessorAdapter 的 "dashed" 分支，
                //     由自定义 DashedUnderlineSpan 在文字 baseline 下方画出贴合文字宽度的虚线；
                //   - iOS：走原生 KuiklyRenderComponentExpandHandler 的 "dashed" 分支，用
                //     NSAttributedString 的 NSUnderlineStyleSingle | NSUnderlinePatternDash
                //     给整段文本加虚线下划线（原生富文本自带虚线样式）；
                //   - OHOS：走 core-render-ohos 新增的 kDashedUnderline span（方案 C），
                //     framework 在基线处用 OH_Drawing 手画一段段短线，实现真·文本虚线
                //     （跟字形、换行逐行各一条）。
                // 这是目前三端都能在 Kuikly Compose 里得到"真正文本虚线"的官方路径。
                // ============================================
                Text("场景4: textPostProcessor 原生虚线下划线（Android & iOS 走原生适配器，OHOS 走 kDashedUnderline span 基线手画）")
                Spacer(Modifier.height(4.dp))
                DashedUnderlineText("这是一条真正贴合文字宽度的虚线下划线")

                Spacer(Modifier.height(24.dp))

                // ============================================
                // 场景5：Spike — Canvas + drawLine + PathEffect.dashPathEffect
                // 验证 Phase 1：drawLine 的 pathEffect 参数在 Canvas（CanvasView）上
                // 能否画出虚线。这是 drawBehind+PathEffect 通用通道的第一道验证。
                // 注意：用 Canvas composable 而非 Box，因为 drawBehind 只在
                // CanvasView 上生效（DrawModifier.kt:123 闸门），Box 不是 CanvasView。
                // 出口标准：下方红色虚线肉眼可见。
                // ============================================
                Text("场景5: Spike — Canvas + drawLine + PathEffect.dashPathEffect（红色虚线）")
                Spacer(Modifier.height(4.dp))
                Canvas(
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                ) {
                    drawLine(
                        color = Color.Red,
                        start = Offset(0f, size.height - 2.dp.toPx()),
                        end = Offset(size.width, size.height - 2.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            intervals = floatArrayOf(8f, 4f),
                            phase = 0f
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ============================================
                // 场景6：回归 — Canvas + drawLine + pathEffect=null（实线）
                // 验证 pathEffect=null 时 drawLine 行为与改造前一致（画实线）。
                // ============================================
                Text("场景6: 回归 — Canvas + drawLine + pathEffect=null（蓝色实线）")
                Spacer(Modifier.height(4.dp))
                Canvas(
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                ) {
                    drawLine(
                        color = Color.Blue,
                        start = Offset(0f, size.height - 2.dp.toPx()),
                        end = Offset(size.width, size.height - 2.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ============================================
                // 场景7：LazyColumn 复用验证（验收 E1/E2）
                // 30 条 Text + drawBehind 虚线在 LazyColumn 里复用。滚动时验证：
                //   E1 上一条的虚线不残留到下一条（bgCanvasView 跟随 detach 清理）
                //   E2 无 View 泄漏（onDetach 移除 bgCanvasView）
                // ============================================
                Text("场景7: LazyColumn 复用 — 滚动时虚线不残留/不泄漏（E1/E2）")
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                ) {
                    items(30) { index ->
                        Text(
                            "第 $index 条 LazyColumn 复用 Text + drawBehind 虚线",
                            modifier = Modifier.drawBehind {
                                drawLine(
                                    color = Color.Blue,
                                    start = Offset(0f, size.height - 1.dp.toPx()),
                                    end = Offset(size.width, size.height - 1.dp.toPx()),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        intervals = floatArrayOf(6f, 3f),
                                        phase = 0f
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文本虚线下划线组件（跨端统一入口）：
 * 三端统一走原生 [Modifier.textPostProcessor]("dashed") 适配器，由原生画出贴合文字的虚线。
 *
 * 各端实现：
 *   - Android：DashedUnderlineSpan 在 baseline 下手画虚线
 *   - iOS：NSAttributedString NSUnderlinePatternDash 原生虚线样式
 *   - OHOS：core-render-ohos kDashedUnderline span → OH_Drawing 基线手画虚线段（方案 C）
 */
@Composable
private fun DashedUnderlineText(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(text = text, modifier = Modifier.textPostProcessor("dashed"))
}

# [实现总结] 文本虚线 drawBehind-pathEffect 对齐

> 本总结对照技术方案 `docs/DevGuide/kuikly-compose-drawBehind-pathEffect-Design.md`，记录 Kuikly Compose 在「文本虚线」上对齐官方 Jetpack Compose 的实际进度。
> **关键结论：官方 5 个验证场景已在 Android 端全部 1:1 对齐（场景 1/3/5 为 drawBehind 整行/多形态虚线，场景 2/4 通过 native StaticLayout 行度量桥接解锁）。iOS/OHOS 三端对拍、性能数据、回归单测尚未做。**

## 精简版

- 成果（一句话）：在 Kuikly Compose 落地了 `Modifier.drawBehind { drawLine(pathEffect = PathEffect.dashPathEffect(...)) }` 框架级通道 + 官方 5 场景全部 1:1 对齐（含通过 native 行度量桥接解锁的局部虚线/多行逐行虚线），Android 模拟器已验证渲染。
- 用的方案（核心）：`PathEffect`（dash 特效）→ `Paint.pathEffect` → `DrawScope.drawLine` 签名恢复 → `KuiklyCanvas` 直连 `CanvasContext.setLineDash`；并让 `DrawBackgroundModifier` 对非 CanvasView 宿主（如 Text/RichTextView）惰性注入背景 CanvasView，使 `drawBehind` 能叠在文本下方出虚线。
- 改动文件：drawBehind 通道 6 个（`PathEffect.kt` 新增、`Paint.kt`、`DrawScope.kt`、`KuiklyCanvas.kt`、`DrawModifier.kt`、`DashedUnderlineDemo.kt`）+ 行度量桥接 4 个（`KRRichTextView.kt` native、`MultiParagraph.kt`、`TextLayoutResult.kt`、`TextStringRichNode.kt`）。drawBehind 通道批次已提交并推送到个人 fork；行度量桥接批次为本次提交（commitID 见文末）。
- 单测与验证结果：暂无自动化单测；Android 端 `:androidApp:assembleDebug` 编译通过、模拟器（`emulator-5556`）截图确认官方 5 场景全部渲染（场景 1/3/5 整行/多形态虚线、场景 2 局部虚线、场景 4 多行逐行虚线，见第七/八节）。

---

## 一、项目链接

- 仓库：KuiklyUI（内网 `git.woa.com/Tencent-TDS/KuiklyUI` / 外网 `github.com/Tencent-TDS/KuiklyUI`）
- 分支：`feat/kuikly-compose-drawBehind-pathEffect`
- 基线 commit：`7b9a824d docs: drawBehind+PathEffect 技术方案与验收标准`
- 提交情况：drawBehind 通道批次已提交并推送到个人 fork（`github.com/nikazhao/KuiklyUI`，分支同名 `feat/kuikly-compose-drawBehind-pathEffect`）；行度量桥接批次为本次提交（commitID 见文末「附：提交记录」）
- 技术方案：`docs/DevGuide/kuikly-compose-drawBehind-pathEffect-Design.md`
- Demo 入口（页面名）：`DashedUnderlineDemo`（`demo/.../pages/compose/DashedUnderlineDemo.kt`）
- 官方对照工程：`/Users/zhaozining/CodeBuddy/20260615095947/DashedLineVerify/MainActivity.kt`（Jetpack Compose 原版 5 场景）
- 验证截图（本地，未入库）：`/tmp/dashed-official-port-android.png`（07-15 19:28，场景 1/3/5）、`/tmp/dashed-5scene-aligned-android.png`（07-15 20:30，行度量桥接后 5 场景全景）、`/tmp/dashed-scene2-zoom3.png`（场景 2 局部虚线放大）——均截于 `emulator-5556`

---

## 二、要解决的问题（背景）

- 用户在 Kuikly Compose 中需要像官方 Jetpack Compose 一样，用 `Text(Modifier.drawBehind { drawLine(pathEffect = PathEffect.dashPathEffect(...)) })` 画文本虚线下划线，并要求**在 API 形态与渲染效果上 1:1 对齐官方**，而非走 Kuikly 私有的 `textPostProcessor` 捷径。
- 直接动因：之前 `drawBehind` 仅在 `CanvasView` 宿主上生效，挂在 `Text`（RichTextView）上画不出东西；且 `PathEffect` / `Paint.pathEffect` 等官方 API 在 Kuikly 端缺失，无法用官方写法表达虚线。

---

## 三、技术方案与选型原因

### 3.1 框架级虚线通道（drawBehind + pathEffect）

#### 做法

1. 新增 `PathEffect`：`sealed interface PathEffect`，提供 `companion.dashPathEffect(intervals, phase)` 工厂，内部 `data class DashPathEffect` 携带 `intervals: FloatArray` 与 `phase`，并重写数组安全的 `equals/hashCode`。
2. `Paint` 增加 `var pathEffect: PathEffect?` 字段（默认 `null` = 实线）。
3. 恢复 `DrawScope.drawLine(..., pathEffect: PathEffect? = null)` 与 `drawPoints(..., pathEffect: PathEffect? = null)` 官方签名。
4. `KuiklyCanvas.drawLine` 消费 `paint.pathEffect`：当为 `DashPathEffect` 时，将 `intervals` 除以 `densityValue` 换算为 dp/pt 后透传 `CanvasContext.setLineDash(...)`；为 `null` 时显式 `setLineDash(emptyList())` 复位，避免上一帧残留。
5. `DrawBackgroundModifier`（即 `drawBehind` 的底层节点）改造为**非 CanvasView 宿主也注入背景 CanvasView**：`draw()` 中若宿主不是 `CanvasView`，惰性创建绝对定位的 `CanvasView` 叠在宿主下方，每次 draw 手动 `setFrame` 同步位置/尺寸，并用 `CanvasDrawScope` 把 `onDraw` 跑进该背景 CanvasView；`onDetach` 时移除，防泄漏。

#### 设计考量

- 通道与官方语义一致：`pathEffect=null` 画实线、`dashPathEffect` 画虚线，API 形态与 Jetpack Compose 一致，便于「只换 import」移植官方代码。
- 非 CanvasView 注入背景 CanvasView 是最小侵入方案：不动 `Text`/`TextStringRichNode`，不依赖 z-order，仅对 `drawBehind` 这一类需求生效；CanvasView 宿主仍走原路径，互不影响。
- 4dp 底部 padding 视觉处理：背景 CanvasView 比 Text 高 4dp，`onDraw` 中 `size.height` 落在文字区域之下，使虚线出现在文本下方、不穿字。

#### 为什么不选别的做法

- **不选用纯 `textPostProcessor("dashed")` 原生捷径**：那是 Kuikly 私有能力，无法用官方 `drawBehind + pathEffect` 写法表达，且局部 span 级虚线在 Android 单段富文本里尚不稳定，不符合「1:1 对齐官方」的硬性诉求。
- **不选用改 `TextStringRichNode` 内置虚线**：耦合进文本节点，破坏 `drawBehind` 的通用性，且仍无法覆盖官方 `getBoundingBox` 局部定位能力。

### 3.2 官方 5 场景移植验证

#### 做法

把官方 `DashedLineVerify/MainActivity.kt` 的 5 个场景原样（仅 `androidx.compose.*` → `com.tencent.kuikly.compose.*` 换 import）移植进 `DashedUnderlineDemo.kt`：

- 场景 1：`Text + drawBehind` 整行虚线。
- 场景 2：纯 Text + `drawBehind` 只画**局部**虚线（依赖 `onTextLayout` + `getBoundingBox`）。
- 场景 3：实线下划线对照（走 `TextDecoration.Underline`，非本通道）。
- 场景 4：多行折行文本**逐行**虚线（依赖 `lineCount` + `getLineBottom`）。
- 场景 5：不同线宽 / 疏密间隔的虚线（`dashPathEffect` 参数化）。

#### 设计考量

- 场景 1/3/5 的官方代码在 Kuikly 可编译可运行，是「对齐」的直接证据。
- 场景 2/4 依赖 `TextLayoutResult` 的行度量几何 API（`getBoundingBox` / `lineCount` / `getLineBottom`），最初因这些 API 未实现而阻塞；已通过下节 3.3 的 native 行度量桥接补齐，官方写法可直接编译运行，Android 端已验证渲染。

#### 为什么不选别的做法

- 不为 2/4 场景用其它 Kuikly 私有 API「凑出」虚线效果来假装对齐；而是补齐真实的行度量几何 API（见 3.3），让官方 `onTextLayout + getBoundingBox` / `lineCount + getLineBottom` 写法在 Kuikly 端真正跑通。

### 3.3 行度量几何 API 的 native 桥接（解锁场景 2/4）

#### 做法

1. **复用既有 native→commonMain 同步通道**（非新建）：Kuikly 文本由原生 `KRRichTextView` 渲染，其 `call(methodName, params): Any?` + commonMain 侧 `textView.shadow?.callMethod(name, paramString): String?` 已被 `spanRect` 等占位符能力使用，返回空格分隔字符串再解析。
2. native `KRRichTextView.kt`：`call()` 新增两个纯增量分支——`lineMetrics`（返 `"N top0 bottom0 top1 bottom1 ..."`，dp）与 `getBoundingBox`（offset → `"left top right bottom"`，dp），配 2 个私有 helper（`getLineMetrics` / `getCharBoundingBox`），均从 measure 后就绪的 `textDrawer.textLayout`（`StaticLayout`）读取，**不碰现有 measure/draw/setProp**。
3. commonMain `MultiParagraph.kt`：增加 `lineCount` / `lineTops` / `lineBottoms: FloatArray` / `getBoundingBoxFn`，实现 `getLineTop` / `getLineBottom` / `getBoundingBox`。
4. commonMain `TextLayoutResult.kt`：在 `lineCount` 后新增 `getLineTop` / `getLineBottom` / `getBoundingBox` 三个 getter（委托 `multiParagraph`），不动既有注释块。
5. `TextStringRichNode.genTextLayoutResult`：measure 之后调 `lineMetrics` 解析回填行数据、接 `getBoundingBoxFn`；native 返 dp × `pageDensity` → px，与 `DrawScope` 坐标系一致，虚线才落在正确位置。

#### 设计考量

- 复用 `callMethod` 通道 = 零新增事件通道、纯增量改动，对现有文本渲染零回归。
- 单位约定统一：native 侧镜像 `kuiklyRenderContext.toDpI` 返 dp，commonMain 侧统一 × `pageDensity` 还原 px。

#### 已知限制

- `getBoundingBox` 目前对 LTR 单行 span 正确；跨行 span 用 `getLineRight` 兜底，RTL bidi 未完整覆盖（demo 场景不涉及）。
- 仅 Android 端落地；iOS（TextKit `NSLayoutManager`）/ OHOS（ArkUI Text）需按同一模式补等价方法（见第九节下阶段计划）。

---

## 四、实现的功能

- [x] `PathEffect.dashPathEffect(intervals, phase)` 虚线特效 API（与官方语义一致）。
- [x] `Paint.pathEffect` 字段，打通 `drawLine` 虚线入参。
- [x] `DrawScope.drawLine` / `drawPoints` 恢复 `pathEffect` 参数（官方签名对齐）。
- [x] `KuiklyCanvas.drawLine` 将 `pathEffect` 落到原生 `setLineDash`，`null` 显式复位实线。
- [x] `drawBehind` 支持非 CanvasView 宿主（Text/RichTextView），注入背景 CanvasView 出虚线。
- [x] 官方 5 场景移植 demo，全部 1:1 对齐官方，Android 模拟器已验证（场景 2 局部虚线贴在 span [9,11) 下方、场景 4 多行折行文本逐行虚线）。
- [x] `TextLayoutResult` 行度量几何 API 桥接（`lineCount` / `getLineTop` / `getLineBottom` / `getBoundingBox`），通过 native `KRRichTextView.call lineMetrics/getBoundingBox` 从 Android `StaticLayout` 回填。

---

## 五、改动的代码文件

> 本节仅列出**本总结范围内的 drawBehind + pathEffect 对齐通道**相关文件。同分支工作树还存在一组**独立的「原生 span 级虚线」改动**（`TextDecoration.DashedUnderline` / 新增 `KRDashedUnderlineSpan.kt` / `KRRichTextBuilder` 的 `textPostProcessor` 扩展 / `KuiklyTextExtension.kt` / `TextStringRichNode.kt` / 三端 RichText 相关改动），属于先前的「路线 C」私有捷径，**与本次 drawBehind 对齐无关、不在本总结范围**，请勿与下表混淆。

### 5.1 `compose/.../ui/graphics/PathEffect.kt`（新增）

- 新增 `sealed interface PathEffect`：`companion.dashPathEffect(intervals: FloatArray, phase = 0f)`（`PathEffect.kt:11`）。
- 新增 `internal data class DashPathEffect(intervals, phase)`（`:20`），重写基于 `FloatArray.contentEquals/contentHashCode` 的 `equals/hashCode`（`:25-37`），供 Modifier 复用 / 相等比较。

### 5.2 `compose/.../ui/graphics/Paint.kt`（修改）

- `Paint` 增加 `var pathEffect: PathEffect?` 字段：`Paint.kt:57`（接口声明）、`:133`（实现属性）。文档注释明确「当前仅 `PathEffect.dashPathEffect` 受支持」。

### 5.3 `compose/.../ui/graphics/drawscope/DrawScope.kt`（修改）

- 恢复官方签名：`fun drawLine(..., pathEffect: PathEffect? = null)`（`:382`、`:409` 两处重载）；`fun drawPoints(..., pathEffect: PathEffect? = null)`（`:852`、`:880`）。原有 `Stroke.pathEffect` 仍处注释态（`:939-988`，留待后续）。

### 5.4 `compose/.../ui/KuiklyCanvas.kt`（修改）

- `drawLine(p1, p2, paint)`（`:158`）：取 `paint.pathEffect`，若为 `DashPathEffect` 则 `setLineDash(effect.intervals.map { it / densityValue })`（`:162-163`）；否则 `setLineDash(emptyList())` 复位（`:165`），避免跨帧残留。

### 5.5 `compose/.../ui/draw/DrawModifier.kt`（修改）

- `DrawBackgroundModifier.draw(view)`（`:138`）：`view is CanvasView` 走原路径；否则（非 CanvasView 宿主）调用 `ensureBackgroundCanvasView(view)` + `drawIntoBackgroundCanvasView(view, size)`（`:146-151`）。
- `ensureBackgroundCanvasView(hostView)`（`:163`）：惰性创建绝对定位 `CanvasView` 加到宿主父容器 `addChild(..., absolutePosition, 0)` + `insertDomSubView(bg, 0)`，位置取 `hostView.renderView.currentFrame`。
- `drawIntoBackgroundCanvasView(hostView, scopeSize)`（`:190`）：每次 draw 手动 `bgRender.setFrame(...)`，尺寸用 `scopeSize`（含多行）+ 4dp 底部 padding，`bgDrawScope.draw(...)` 将 `onDraw` 跑进背景 CanvasView。
- `onDetach()`（`:226`）：移除背景 CanvasView 并置空，防泄漏。

### 5.6 `demo/.../pages/compose/DashedUnderlineDemo.kt`（重写）

- 重写为官方 5 场景移植版（`LazyColumn` 承载 5 个 item），全部走官方原样写法（仅换 import）：
  - `DashedUnderline_Text()`（`:128`）：场景 1，整行 `drawBehind + dashPathEffect(8,4)` 红色虚线。
  - `SpanDashedText(...)`（`:152`）/ `DashedUnderline_Text_Span()`（`:208`）：场景 2，`onTextLayout` 用 `result.getBoundingBox(offset)` 定位 span [9,11) 包围盒，`drawBehind` 只在该截下方画虚线。
  - 场景 3（`:84`）：`TextDecoration.Underline` 整行 + `SpanStyle` 局部实线对照。
  - `DashedUnderline_Text_MultiLine()`（`:223`）：场景 4，`onTextLayout` 遍历 `result.lineCount` + `result.getLineBottom(i)` 逐行画虚线。
  - `DashedUnderline_Text_Pattern(...)`（`:249`）：场景 5，细/中/粗三组 `dashPathEffect` 虚线。
- 踩坑修正：`@Composable` 注解走真 `androidx.compose.runtime.Composable`（非 `com.tencent.kuikly.compose.runtime`），首次移植因 import 错误编译失败，已修正。

### 5.7 行度量桥接相关文件（本次提交，解锁场景 2/4）

- `core-render-android/.../KRRichTextView.kt`（native，修改）：`call()` 新增 `lineMetrics` / `getBoundingBox` 两分支 + `getLineMetrics` / `getCharBoundingBox` 两私有 helper + 两方法名常量；从 `StaticLayout` 读行度量，返 dp。**不碰现有 measure/draw/setProp**。
- `compose/.../ui/text/MultiParagraph.kt`（修改）：新增 `lineCount` / `lineTops` / `lineBottoms` / `getBoundingBoxFn` 及 `getLineTop` / `getLineBottom` / `getBoundingBox`。
- `compose/.../ui/text/TextLayoutResult.kt`（修改）：`lineCount` 后新增 `getLineTop` / `getLineBottom` / `getBoundingBox` 三 getter（委托 `multiParagraph`），不动既有注释块。
- `compose/.../foundation/text/modifiers/TextStringRichNode.kt`（修改）：`genTextLayoutResult` 调 `lineMetrics` 回填行数据 + 构造 `getBoundingBoxFn`，dp × `pageDensity` → px。

---

## 六、单元测试

- **暂无自动化单测**：本阶段聚焦框架通道打通与端到端渲染验证，尚未编写 `PathEffect`/`DrawModifier`/`KuiklyCanvas` 单测（对应设计文档验收类的 G 类回归测试未做）。
- 后续需在 `compose` 模块补：dash 参数换算、null pathEffect 复位、背景 CanvasView 注入/复用/onDetach 移除等用例。

---

## 七、验证结果

- 编译：`:androidApp:assembleDebug` 通过（修正 `@Composable` import 后），`DashedUnderlineDemo` 可进包。
- 安装 / 运行：装 `androidApp-debug.apk` 到 `emulator-5556`，`am start` 拉起 `DashedUnderlineDemo` 页面，前台确认在 demo。
- 手动验证（截图 `/tmp/dashed-official-port-android.png` 场景 1/3/5、`/tmp/dashed-5scene-aligned-android.png` 桥接后 5 场景全景、`/tmp/dashed-scene2-zoom3.png` 场景 2 放大）：
  - 场景 1 ✅：红色 8-4 虚线画在 Text 下方（4dp 底部 padding 视觉生效，不穿字）。
  - 场景 2 ✅：红色虚线贴在 span [9,11)（"纯 "）正下方，左沿贴 "纯" 起始、右沿到空格——`getBoundingBox` 端到端跑通。
  - 场景 3 ✅：整行实线 + Span 局部实线正常。
  - 场景 4 ✅：多行折行文本每行下沿各一条品红虚线（非只在最底一条）——`lineCount` + `getLineBottom` 端到端跑通。
  - 场景 5 ✅：细红(8-4) / 中蓝(12-6) / 粗绿(16-8) 三组虚线全部画出。
- 运行时日志：`logcat` 未见 `drawBehind`/背景 CanvasView/行度量桥接相关 FATAL / 报错。
- 未覆盖：iOS / OHOS 真机或模拟器对拍（设计文档 F 类三端验收未做）；性能数据（C 类）、非法参数兜底（D 类）、生命周期滚动复用（E 类）均未采集。

---

## 八、附录：与参考实现的差异对照

| 场景 / 能力 | 是否纯 Compose 通道 | 是否与官方对齐 | 差异与原因 |
|------|-------------------|---------------|-----------|
| 场景 1：整行 `drawBehind` 虚线 | 是（drawBehind + pathEffect） | **是（Android 已验证）** | Kuikly 用背景 CanvasView 注入实现，视觉等价；iOS/OHOS 待对拍 |
| 场景 3：实线下划线对照 | 是（走 TextDecoration，非本通道） | 是 | 实线/删除线本就是已对齐能力 |
| 场景 5：多形态虚线（参数化） | 是 | **是（Android 已验证）** | `dashPathEffect(intervals, phase)` 直接对齐官方 API |
| 场景 2：纯 Text 局部虚线 | 官方依赖 `onTextLayout + getBoundingBox` | **是（Android 已验证）** | 通过 `KRRichTextView.call getBoundingBox` 桥接 `StaticLayout` 行度量；LTR 单行 span 正确，跨行/RTL span 为已知限制 |
| 场景 4：多行逐行虚线 | 官方依赖 `lineCount + getLineBottom` | **是（Android 已验证）** | 通过 `KRRichTextView.call lineMetrics` 桥接 `StaticLayout.lineCount/getLineTop/getLineBottom` |
| `pathEffect=null` 画实线 | 是 | 是 | `KuiklyCanvas` 显式 `setLineDash(emptyList())` 复位 |
| 官方代码「只换 import 能跑」 | — | 场景 1/3/5 满足；场景 2/4 不满足 | 差异完全来自 `TextLayoutResult` 几何 API 未实现，与渲染通道无关 |

---

## 九、当前进度与下阶段计划

### 9.1 当前进度（截至 2026-07-15）

| 模块 | 进度 | 说明 |
|------|------|------|
| 框架通道（PathEffect / Paint / DrawScope / KuiklyCanvas / DrawModifier） | ✅ 代码落地 | 已就绪，可承载 `drawBehind + pathEffect` |
| 场景 1 / 3 / 5（整行、实线、多形态虚线） | ✅ Android 端对齐 | 模拟器截图已验证 |
| 场景 2 / 4（局部、多行逐行虚线） | ✅ Android 端对齐 | 通过 native `lineMetrics`/`getBoundingBox` 桥接 `StaticLayout` 行度量，模拟器截图已验证 |
| iOS / OHOS 三端对拍 | ⏳ 未做 | 仅 Android 代码路径在，未真机验证 |
| 性能 / 兜底 / 生命周期 / 回归单测 | ⏳ 未做 | 对应设计文档 C/D/E/G 类验收全空 |

**一句话进度**：drawBehind + pathEffect 框架通道 + native 行度量桥接已打通，官方 5 场景在 Android 端**全部 1:1 对齐**；剩余未做的是 iOS/OHOS 对拍、性能数据、生命周期/兜底/回归单测。

### 9.2 下阶段计划

1. **iOS / OHOS 三端行度量桥接（F 类）**：参照 Android `StaticLayout` 桥接模式，在 iOS 的 `KRRichTextView.m`（TextKit `NSLayoutManager`）/ OHOS `KRRichTextShadow.cpp`（ArkUI Text）暴露等价的 `lineMetrics` / `getBoundingBox` 方法，使 iOS/OHOS 端场景 2/4 同样 1:1 对齐。
2. **三端对拍（iOS / OHOS）**：在 iOS 模拟器 / OHOS 真机或模拟器跑通 `DashedUnderlineDemo`，与 Android 端截图比对。
3. **性能数据（C 类）**：Perfetto 跑首屏耗时、LazyColumn FPS、APK 增量，用数据佐证方案（导师强调「用数据支撑」）。
4. **兜底与生命周期（D / E 类）**：LazyColumn 100 条滚动验证复用不串位 / detach 不泄漏；传空 / 0 / 超大 dash 等非法参数验证。
5. **回归与单测（G 类）**：确认 `textPostProcessor` 旧路径、实线、`AnnotatedString` 局部实线未被破坏；补 `compose` 模块单测并跑 `:compose:testDebugUnitTest` 全绿。
6. **提交与评审**：将本阶段改动（drawBehind 通道 + 行度量桥接 + 5/5 场景对齐）整理 commit（带 commitID 便于溯源），评审签字后归档。

---

> 文档状态：阶段性总结。Android 端官方 5 场景 1:1 对齐已达成；iOS/OHOS 对拍、性能、兜底、回归单测待后续阶段补齐。

---

## 附：提交记录

- 本次提交（行度量 native 桥接 + 场景 2/4 对齐 + demo 改回官方原样 + 本文档修订）：为分支 `feat/kuikly-compose-drawBehind-pathEffect` 的 HEAD，提交标题 `feat(compose): 行度量 API native 桥接，官方 5 场景 Android 端全对齐`（具体 commitID 以推送到 fork 后的分支为准，可在 fork 分支历史查看）
- 此前 drawBehind + pathEffect 框架通道批次：已提交并推送至个人 fork（`github.com/nikazhao/KuiklyUI`，分支 `feat/kuikly-compose-drawBehind-pathEffect`），推送前 HEAD 为 `891d6b41`。
- 以上均**未推送**官方仓库（Tencent-TDS）。

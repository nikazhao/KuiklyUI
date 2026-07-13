# @人 高亮落地计划（AnnotatedString → 原生 Span 桥接）

> 配套文档：`MentionPublisher-Design.md`（§6.2 方案 F）
> 目标：在 `BasicTextField`（Compose DSL 输入框）内，用 `AnnotatedString` + `SpanStyle(color=Blue)` 把 `@人` 标蓝，底层由原生 `EditText`（`KRTextFieldView`）用 `ForegroundColorSpan` 渲染。
> 状态：**计划阶段**，未动手。本期范围先 Android。

---

## 0. 背景与已坐实的代码事实

导师需求：短期先做"跟微博一样 @人"——① 输入框内 @人 高亮；② 删除 @人 先选中、第二次才真删（两段式删除已知做通）。高亮方向导师点名用 **Compose DSL 的 `AnnotatedString`**。

两条路的代码核查结论（均已读源码坐实）：

| 路线 | 结论 | 证据 |
|------|------|------|
| `VisualTransformation` | ❌ 死路 | `BasicTextField.kt:251,411` 接参数但 `CoreTextField` 调用处（`:283-311`）**未下传**；`CoreTextField.kt:156-178` 无此参数；`visualText`/`OffsetMapping`/`TextDelegate` 整条管线被注释（`:322-334,:360,:727-855`） |
| `AnnotatedString` 原生桥接 | ✅ 活路 | `BasicText.kt:156` 接收 `AnnotatedString`；`TextStringRichNode.kt:410` 调 `applyAnnotatedString`；`KuiklyTextExtension.kt:307-414` 遍历 `spanStyles` 生成 `TextSpan`，`applySpanStyle`（`:431-462`）把 `SpanStyle.color` 转原生蓝；原生 `KRRichTextBuilder.kt:196` 已有 `ForegroundColorSpan(spanProps.color)`；`KRTextFieldView.applyEmojiSpans()`（`:943-969`）是"按区间给 Editable 打 Span"的现成模板 |

**缺口**：`BasicTextField`/`CoreTextField` 全文件搜 `AnnotatedString` = **0 命中**，即"AnnotatedString → 输入框原生 Span"这截桥接不存在，需补。

---

## 1. 落地步骤（Phase 0-7）

### Phase 0（可选·推荐）— 纯安卓小验证
- 建一个**不装 Kuikly** 的普通 Android 工程，用原生 `EditText` + `ForegroundColorSpan` 验证：
  1. 输入 `@张三` → 变蓝（单层高亮）；
  2. 长文本多行下光标跟手不偏（原生 EditText 天生管理光标）；
  3. 退格第一次选中整个 `@张三`、第二次才删（两段式删除）。
- 目的：先独立证明"单层原生高亮"稳定，再回框架接管道。预计 1-2 小时，零风险。
- 价值：验证"核心逻辑"可搬回 Kuikly；代码本身不能原样搬（Kuikly 有自己 `values` 通道与 `KRRichTextBuilder`）。

### Phase 1 — Compose API 层暴露高亮入口
- `demo/.../compose/MentionPublisherDemo.kt`：用 `buildAnnotatedString { withStyle(SpanStyle(Color.Blue)) { append("@张三") } }` 构造高亮文本。
- `BasicTextField.kt:251,283` 附近：新增参数（如 `annotatedText: AnnotatedString?` 或 `mentionRanges: List<TextRange>`），把 mention 的 `(start, end, color)` 收集好。

### Phase 2 — 把 span 透传到原生
- `CoreTextField.kt:156-178` 函数签名承接该参数。
- 在渲染 `AutoHeightTextAreaView`（`CoreTextField.kt:149` 的 `as? AutoHeightTextAreaView`）处，把 mention 区间与颜色传到 `KRTextFieldView`。

### Phase 3 — 原生渲染（核心，照现有模板写）
- `KRTextFieldView.kt` 仿 `applyEmojiSpans()`（`:943-969`）新增 `applyMentionSpans(mentionRanges: List<Range>, color: Int)`：清旧 mention span → 按区间 `setSpan(ForegroundColorSpan(color), start, end, ...)`。
- 倾向走此路（直接 `ForegroundColorSpan`），最贴近 `EditText` 可编辑态。
- 备选：走 `setValues()` → `KRRichTextBuilder` JSON 富文本（`KRTextFieldView.kt:582-591` 已有 `setValues` 调 `build()`，`:196` 产 `ForegroundColorSpan`）。Phase 3 定走哪条。

### Phase 4 — 编辑保活
- 每次 `onTextChanged` / `setText` 后重跑 `applyMentionSpans`，保证输入、删除后高亮还在。
- 关键：`setText` 会重置 `selection`，必须 `setSelection` 恢复光标（参考 `KRTextFieldView.kt:324,590` 已有恢复写法）。

### Phase 5 — 接回两段式删除
- 确认"第一次退格选中整个 @人（高亮/选中态）、第二次真删"逻辑，与新 span 渲染不冲突。
- 选中态表现：可用同一 `ForegroundColorSpan` 换底色，或叠加背景 span。

### Phase 6 — Demo 跑通
- `MentionPublisherDemo.kt`：`@人` 高亮（蓝）+ 两段式删除，双双可演示。
- 收口后删除方案 A（双控件叠加）的单层结构，改为单层 `BasicTextField` + 原生 span 高亮。

### Phase 7 — 编译验证
- `./gradlew :compose:compileDebugKotlinAndroid`
- `./gradlew :androidApp:assembleDebug`

---

## 2. 风险（Risks）

- **性能**：每次文本变化全量重打 span，长文本可能卡 → 用"仅当 mention 区间变化才重绘"去重。
- **光标时序**：`setText` 重置选区，若恢复时机不对光标会跳 → 严格照 `:324/:590` 恢复写法。
- **两条原生富文本模型取舍**：`setValues`(JSON) vs `applyEmojiSpans`(直接 Span) 二选一，Phase 3 定。
- **跨端缺口**：iOS/OHOS 当前无对应 span 通道，本期可能仅 Android 能演示高亮。

---

## 3. 与现有方案的关系

- 方案 A（双控件叠加）：临时演示态，已踩 gravity / includeFontPadding / lineHeight 三处光标偏移，脆弱，**由本计划取代**。
- 方案 C（`TextFieldValue.annotatedString`）：已验证失败（渲染层不消费 `spanStyles`）。
- 方案 B（推动框架恢复 `visualTransformation` 管线）：成本最高、依赖框架排期；本计划是更轻的"在现有原生 span 能力上补桥接"，不依赖复活被注释的 Compose 自绘管线。

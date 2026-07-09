# 微博发布器 @人功能 — 技术方案（Compose DSL / AnnotatedString 验证版）

> 目标：在独立验证页跑通两个核心能力 —— ① @人文字高亮；② 两段式删除（先选中 @人，再按一次才真正删除）。

---

## 1. 需求范围与验收标准

### 1.1 本版目标
在独立验证页验证以下两项能力：
1. 输入框中的 @人 文本以高亮色显示，与普通文本区分。
2. 光标位于 @人 尾部按删除键：第一次仅选中整段 @人；第二次按删除键才真正删除整段 @人。

### 1.2 验收标准
| 能力 | 验收点 |
|------|--------|
| @人高亮 | `@张三` 这类 Mention 在输入框中展示为高亮色，普通文本保持默认色 |
| 两段式删除 | ① 光标紧邻 @人尾部按删除键 → 仅选中整段 @人；② 再按一次删除键 → 整段 @人 被删除；③ 非 @人 位置删除行为不变 |
| 插入 @人 | 提供「插入@张三」「插入@李四」按钮，可在当前光标处插入 Mention，并立即具备高亮和两段式删除能力 |

### 1.3 明确不做（本版）
- 输入 `@` 后弹出搜索 / 选人面板
- 话题 `#`、表情、图片等扩展富文本能力
- 跨平台收口；本版先验证 Android 可行性
- 发布、网络请求、数据持久化

---

## 2. 技术选型与数据模型

### 2.1 组件选型
本验证页改为 **Compose DSL** 实现，不再使用 `InputSpan` / `TextArea`。

具体选型：
- **页面容器**：`ComposeContainer`
- **输入组件**：`BasicTextField(value = TextFieldValue, ...)`
- **高亮渲染**：`VisualTransformation` 返回 `TransformedText(AnnotatedString, OffsetMapping.Identity)`
- **编辑态管理**：`TextFieldValue`

这样拆分的原因：
- `AnnotatedString` 适合做**显示层高亮**。
- `TextFieldValue` 自带 `text + selection + composition`，适合做**删除、选区、光标**控制。
- 当前仓库的 `material3.TextField` 只有 `String` 版；要做两段式删除，必须拿到 selection / composition，所以应直接用 `BasicTextField(TextFieldValue)`。

### 2.2 输入状态
```kotlin
var editorValue by mutableStateOf(TextFieldValue(""))
```
说明：
- `editorValue.text` 是原始文本。
- `editorValue.selection` 用来表达当前光标位置或选区。
- `editorValue.composition` 用来识别中文输入法组合态，避免误判删除行为。

### 2.3 Mention 元数据
```kotlin
data class Mention(
    val userId: String,
    val displayName: String,   // 例如 "@张三"
    val start: Int,
    val end: Int               // exclusive
)
```
约束：
- `editorValue.text.substring(start, end) == displayName`
- `end - start == displayName.length`

### 2.4 最小维护规则
为避免验证页过度设计，只定义最小规则：
- **按钮插入 Mention**：在光标位置插入 `@张三 `，并把该位置之后的 Mention 整体后移。
- **整段删除 Mention**：删除对应 Mention 文本，并把其后的 Mention 整体前移。
- **手动编辑命中 Mention 内部**：如果用户把光标移进某个 Mention 内部手改文字，该 Mention 立即降级为普通文本，从 `mentions` 中移除。

这三条已经足够支撑本次验证目标。

---

## 3. @人高亮方案（AnnotatedString）

### 3.1 关键思路
底层真实文本始终是普通字符串，**高亮只发生在显示层**：
- 原始值：`editorValue.text`
- 展示值：通过 `VisualTransformation.filter()` 把原始文本转换成带 `SpanStyle` 的 `AnnotatedString`

也就是说：
- `Mention` 的业务语义存在于 `mentions`
- `AnnotatedString` 只负责显示高亮，不承担业务状态

### 3.2 为什么要用 VisualTransformation
因为输入框是可编辑的：
- 如果直接把整个输入内容当成 `Text(AnnotatedString)` 渲染，就失去编辑能力。
- `BasicTextField + VisualTransformation` 才是可编辑文本场景下的正确做法。

而本场景的高亮**不改字符内容，只改样式**，因此偏移映射可以直接使用 `OffsetMapping.Identity`。

### 3.3 伪代码
```kotlin
val mentionVisualTransformation = VisualTransformation { text ->
    val annotated = buildAnnotatedString {
        append(text.text)
        mentions.forEach { mention ->
            addStyle(
                style = SpanStyle(color = MentionColor),
                start = mention.start,
                end = mention.end,
            )
        }
    }
    TransformedText(
        text = annotated,
        offsetMapping = OffsetMapping.Identity,
    )
}
```

### 3.4 输入框写法
```kotlin
BasicTextField(
    value = editorValue,
    onValueChange = { newValue -> handleValueChange(newValue) },
    visualTransformation = mentionVisualTransformation,
)
```

### 3.5 高亮更新规则
每次 `onValueChange` 后：
1. 更新 `editorValue`
2. 清理失效 Mention（`sanitizeMentions()`）
3. 触发重组
4. `VisualTransformation` 重新生成 `AnnotatedString`

所以不需要额外“手动回刷高亮”机制，Compose 重组会自动完成显示刷新。

---

## 4. 两段式删除方案

### 4.1 状态定义
```kotlin
sealed class DeleteState {
    object Normal : DeleteState()
    data class MentionSelected(val mention: Mention) : DeleteState()
}

var deleteState by mutableStateOf<DeleteState>(DeleteState.Normal)
```

### 4.2 核心策略
两段式删除不靠底层拦截键盘事件，而是靠 **比较旧值和新值，再决定是否回滚并改选区**。

处理流程：
1. 用户第一次在 Mention 尾部按删除键。
2. `BasicTextField` 先把字符删掉，并把新的 `TextFieldValue` 通过 `onValueChange` 回调上来。
3. 业务层识别到这是“命中 Mention 尾部的第一次退格”。
4. 立刻把 `editorValue` 回滚到旧文本，同时把 `selection` 改成整段 Mention 的范围。
5. 用户第二次再按删除键时，系统删除的是整段选区；业务层再同步删掉 Mention 元数据即可。

### 4.3 第一次删除的判定条件
满足以下条件，视为“第一次删 Mention”：
- `oldValue.selection.collapsed == true`（删除前是光标态）
- `newValue.text.length == oldValue.text.length - 1`
- `oldValue.composition == null && newValue.composition == null`（跳过输入法组合态）
- 删除前光标位置 `oldValue.selection.start` 恰好等于某个 Mention 的 `end`

### 4.4 第二次删除的判定条件
满足以下条件，视为“第二次删 Mention”：
- 当前 `deleteState` 是 `MentionSelected`
- 删除前 `oldValue.selection` 正好覆盖整段 Mention
- `newValue.text.length < oldValue.text.length`

### 4.5 伪代码
```kotlin
fun handleValueChange(newValue: TextFieldValue) {
    val oldValue = editorValue

    // 组合态不做 Mention 删除判定，直接透传
    if (oldValue.composition != null || newValue.composition != null) {
        editorValue = newValue
        deleteState = DeleteState.Normal
        return
    }

    val selectedMention = (deleteState as? DeleteState.MentionSelected)?.mention
    val hitMention = mentionEndingAt(oldValue.selection.start)

    // 第一次删 Mention：回滚文本 + 选中 Mention
    if (
        oldValue.selection.collapsed &&
        newValue.text.length == oldValue.text.length - 1 &&
        hitMention != null
    ) {
        editorValue = oldValue.copy(
            selection = TextRange(hitMention.start, hitMention.end)
        )
        deleteState = DeleteState.MentionSelected(hitMention)
        return
    }

    // 第二次删 Mention：接受删除结果，并清理 metadata
    if (
        selectedMention != null &&
        oldValue.selection.start == selectedMention.start &&
        oldValue.selection.end == selectedMention.end &&
        newValue.text.length < oldValue.text.length
    ) {
        editorValue = newValue
        mentions = removeMentionAndShift(mentions, selectedMention)
        deleteState = DeleteState.Normal
        return
    }

    // 普通编辑
    editorValue = newValue
    mentions = sanitizeMentions(newValue.text, mentions)
    deleteState = DeleteState.Normal
}
```

### 4.6 为什么这样合理
这个方案的优点是：
- 不依赖底层特殊删除事件，完全走 Compose 常规 `onValueChange`
- 选中 Mention 直接通过 `TextFieldValue.selection` 表达，语义最清晰
- 高亮与删除共用同一份 Mention 元数据，状态统一

---

## 5. 页面结构、文件与验证重点

### 5.1 文件规划
新增 1 个 Compose 页面文件：

```text
demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/compose/MentionPublisherDemo.kt
```

页面结构：
- `@Page("MentionPublisherDemo")`
- 继承 `ComposeContainer`
- 在 `willInit()` 中 `setContent { ... }`

打开方式：继续走现有路由页输入 `MentionPublisherDemo` 跳转，不改 `RouterPage`、`AppTabPage` 等已有导航代码。

### 5.2 页面 UI 草图
```text
┌─────────────────────────────────┐
│  发布器验证 (@人)                │
├─────────────────────────────────┤
│ [ BasicTextField 多行输入框 ]    │
│   输入文本，@人 高亮显示          │
│                                 │
├─────────────────────────────────┤
│ [插入@张三] [插入@李四]          │
├─────────────────────────────────┤
│ 调试信息：                       │
│  text = "..."                   │
│  selection = [start, end]       │
│  mentions = [ {userId, range} ] │
│  deleteState = Normal/Selected  │
└─────────────────────────────────┘
```

### 5.3 本版只验证三件事
1. `VisualTransformation + AnnotatedString` 是否能稳定完成 Mention 高亮。
2. `TextFieldValue.selection` 是否能稳定完成“第一次删时选中 Mention”。
3. 中文输入法组合态下，删除判定是否需要额外保护；若有干扰，本版先直接跳过组合态，不在验证页继续深挖。

---

## 6. 已知风险与方案修正（2026-07-09 真机验证发现）

### 6.1 阻塞：Kuikly 当前 `BasicTextField` 不渲染 `VisualTransformation`

**现象**：步骤3 真机验证时，Mention 数据层完全正确（`mentions` 列表偏移、`TextFieldValue.text` 均符合预期），但输入框内 `@人` 文本**没有任何高亮**，颜色与普通文本一致。

**根因（已对源码核实）**：
- `compose/.../foundation/text/BasicTextField.kt:251` 接收 `visualTransformation` 参数，但函数体内**从不调用**（grep 仅命中签名与文档注释，无实际传递）。
- `compose/.../foundation/text/CoreTextField.kt` 中 `visualText` / `TransformedText` / `OffsetMapping` 相关逻辑**全部被注释**（line 325、728、830、850），`CoreTextField` 也不接收 `visualTransformation` 参数。
- Kuikly 自定义的 `OutputTransformation` / `TextPostProcessorOutputTransformation`（`compose/.../foundation/text/input/`）接受的是 `TextFieldBuffer` 而非 `AnnotatedString`，且把渲染委托给平台 `textPostProcessor`，**不暴露标准 Compose 的 `SpanStyle` 染色路径**。

**结论**：本方案 §3 写的「`BasicTextField(TextFieldValue)` + `VisualTransformation` 返回带 `SpanStyle` 的 `AnnotatedString`」在**当前 Kuikly 实现下不可行**——参数被接住但被丢弃。

### 6.2 后续修正方向（备选，待定）

| 方案 | 描述 | 评估 |
|------|------|------|
| **A. 双控件** | 上层 `BasicText` 渲染带高亮的展示版（`buildAnnotatedString` + `SpanStyle`），下层隐藏 `BasicTextField` 接收输入，自己桥接点击定位与光标同步 | 高亮能亮；需自实现点击→光标定位、光标同步，工程量约 1.5h+ |
| **B. 推动框架补齐** | 向 Kuikly 框架侧提 issue / PR，让 `CoreTextField` 真正消费 `visualTransformation`（恢复被注释的 `visualText` 路径） | 最干净，但依赖框架排期，非验证页可控 |
| **C. 用 `AnnotatedString` 直接当 `value`** | 把 `TextFieldValue.annotatedString` 直接设为带 `SpanStyle` 的版本（而非通过 VisualTransformation） | 待验证 Kuikly 是否在渲染 `annotatedString` 时消费 `spanStyles`；§3.1 原本假设底层是普通 String，此方案需改数据模型 |

**当前决定**：步骤3 暂不收口，代码留在分支 `feat/mention-publisher-demo`（visualTransformation 已写好但未生效）。明日先验证方案 C（成本最低），不行再走方案 A。

### 6.3 已完成且不受影响的部分

- **步骤1（骨架）**：`@Page` + `ComposeContainer` + `BasicTextField(TextFieldValue)` + 调试区 —— 编译通过、真机渲染正常。
- **步骤2（Mention 数据模型 + 插入按钮）**：`Mention` data class + `insertMention`（光标处插 `@人 ` + 后移后续 mention + 光标移到空格后）—— 真机三场景全过（空文本插入 / 末尾追加 / 中间插入后移）。
- **步骤4（两段式删除）**：依赖 `TextFieldValue.selection` 与 `onValueChange` 比对，**不依赖 VisualTransformation**，因此 §4 方案不受本风险影响，可独立推进。

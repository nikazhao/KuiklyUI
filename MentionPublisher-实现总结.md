# MentionPublisher（@人发布器）实现总结

## 一、要解决的问题

微博发布器里 @人 有两条硬性体验要求：

1. 输入框里 @人的名字要蓝色高亮；
2. 删除 @人 时，第一次按删除键只把整段 @人 选中，第二次才真正删掉（两段式删除），避免一次退格把整段 @人 误删。

此外还要兼容中文输入法：用户用拼音组词（比如打 "zhangsan" 还没上屏成 "张三"）时按退格，应该交给输入法去删拼音字母，而不是误把组词里的字符当成 @人 边界去选中。

## 二、技术方案与选型原因

下面逐个说明**采用了什么方案**，以及**为什么选它、为什么不选别的做法**。

### 方案 1：高亮用「桥接 AnnotatedString → 原生 ForegroundColorSpan」

**做法**：使用的是 Compose DSL 的 `AnnotatedString`（来自 `androidx.compose.ui.text.AnnotatedString`，Kuikly 的 `BasicTextField` 已支持）。Demo 里按官方标准写法 `BasicTextField(value = editorValue.copy(annotatedString = displayText), onValueChange = ...)`，把 @人 的蓝色 `SpanStyle` 放进 `AnnotatedString`；框架再把它桥接到底层原生 `EditText` 的 `ForegroundColorSpan` 渲染成蓝色。这里 `BasicTextField` 是真正消费 `AnnotatedString` 上的样式区间（spanStyles），而不是自己另起一层叠放。

**为什么选它**：
- 这是与官方 Kotlin Compose `WeiboMentionDemo` 一致的写法——官方示例只需要换一个 import 就能直接复用，符合"UI 效果一样、使用代码也一样"的目标；
- 高亮信息集中在 `AnnotatedString` 一处维护，不另起一套样式体系，后续接官方 demo 时改动最小。

**为什么不选别的做法**：
- 曾考虑过「上层 `BasicText` 高亮 + 下层透明 `BasicTextField` 输入」的双层叠放方案。它能跑，但等于绕开了官方写法、自己造了一层渲染，既偏离官方示例，也多维护一套叠放与对齐逻辑，长期是个负担，因此放弃。

### 方案 2：两段式删除在「原生 EditText 层拦截退格」

**做法**：在原生 `EditText` 的事件入口拦截退格键，而不是在 Compose 的 `onValueChange` 里判断：
- 第一次退格：若光标紧挨 @人 尾部，把选区扩到整个 @人 区间（只选中、不删字），并记录待删区间；
- 第二次退格：选区已覆盖 @人，真正删除该区间。

两条退格路径都覆盖：硬件键盘走 `onKeyDown(KEYCODE_DEL)`，软键盘走 `InputConnection.deleteSurroundingText`。

**为什么选它（核心决策点）**：
- 退格在 `onValueChange` 里是**事后通知**：键盘已经把字删了、选区改了，框架才把"新文本 + 新选区"回调给你。你拿到时删除已经发生，没法反悔成"这次先不删、改选中"；
- 两段式删除的本质是"**第一次按退格时，把删除拦下来、改成选中整段**"，这是个事前拦截动作，`onValueChange` 天然做不了；
- 原生 `onKeyDown` 是**事件入口**，你 `return true` 就表示"这个键我处理了，别往下传"，等于把删除动作吞掉，由自己决定下一步（先选中 / 再删除），时机完全可控；
- 实际还有一层问题：我们给原生 `EditText` 打了 `ForegroundColorSpan` 做高亮，带样式的文本在输入法/框架回调里，新旧文本与选区变化的顺序不稳定——在 `onValueChange` 里反复比对新旧文本与选区，试过多轮都偶发错乱。与其去"猜"回调给了什么，不如直接在原生层用**我们自己维护的 @人 区间数据**（`mentionSpansData`）把选区设到边界，干净可控。

### 方案 3：组合态守卫用「检测 SPAN_COMPOSING」

**做法**：用一个 `isInComposition` 判断——若 `editableText` 上存在输入法打上的 `SPAN_COMPOSING` 标记（表示拼音组词还没上屏成汉字），退格直接交给输入法处理，跳过两段式删除逻辑。

**为什么选它**：
- 这是 Android 检测"输入法正在组词"的标准做法，语义明确、零副作用；
- 对普通英文/数字输入和其他功能完全没影响（只有真正在组词时才让路）；
- 这个守卫**必须和方案 2 配套**：中文输入法组字时按退格是在改拼音、不是删汉字，如果不拦下来交给输入法，原生拦截退格会撞上输入法，导致拼音上屏异常。所以组合态下 `return false` 让路，是方案 2 能正确工作的前提。

### 方案 4：插入交互只保留「候选下拉」

**做法**：移除了原先"直接插入 @人"的按钮，`@人` 只通过输入框下方的候选下拉选择插入，候选名单为 张三 / 李四 / 王五 / Tom。

**为什么选它**：
- 直接插入按钮是调试期的临时入口，真实场景里 @人 应该是从候选里选，保留它只是冗余 UI；
- 只留候选下拉让交互路径单一、便于演示和验证两段式删除，也对齐微博的实际体验。

## 三、实现的功能

- @人在输入框内蓝色高亮；
- 两段式删除（先选中整段、再按一次删除）；
- 中文输入法组合态下退格不误触选中逻辑；
- 候选下拉选择 @人 插入。

## 四、改动的代码文件

### 1. `core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/KRTextFieldView.kt`（原生输入框，核心逻辑）
- 新增 `isInComposition`：检测 `editableText` 上的 `SPAN_COMPOSING` 标记，判断输入法是否正在组词；
- 新增 `interceptMentionBackspace()`：在 `onKeyDown(KEYCODE_DEL)` 中调用，第一步把光标旁的 @人 整段选中（仅选中、不删字），并把待删区间存入 `pendingMentionDelete`；
- 新增 `deleteMentionSelection()`：第二步真正删除——优先用当前选中区，选区被折叠时回退到 `pendingMentionDelete` 状态机；
- 新增 `setMentionSpans(json)` / `parseMentionSpans(json)`：接收并解析 @人 区间（`[start, end, color]`），供两段式删除定位边界；
- 新增 `MentionInputConnection`（继承 `InputConnectionWrapper`）：拦截软键盘的 `deleteSurroundingText`，与硬件键盘走同一套两段式逻辑；
- 清理调试日志：删除 `MentionBS` 标签的 `Log.d` 输出。

### 2. `demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/compose/MentionPublisherDemo.kt`（演示页）
- 移除"直接插入 @人"按钮及其点击逻辑，仅保留 @ 候选下拉这一种插入方式；
- 候选下拉选中后通过 `insertMentionAt` 把 `@昵称 ` 插入光标处；
- 候选名单 `赵六` 改为 `Tom`；
- 高亮：`buildAnnotatedString { addStyle(蓝色 SpanStyle) }` + `scanMentions` 正则重扫 @区间，每次输入刷新高亮。

### 3. `core-render-android/build.2.1.21.gradle.kts`（构建配置）
- 新增单元测试依赖：`testImplementation` 引入 `junit` 与 `robolectric`；
- 新增 `testOptions.unitTests.isIncludeAndroidResources = true`，让 Robolectric 在 JVM 内运行。

### 4. `core-render-android/src/test/java/com/tencent/kuikly/core/render/android/expand/component/KRTextFieldViewMentionTest.kt`（新增，单元测试）
- Robolectric 单测，覆盖：`parseMentionSpans`（正常 / 畸形 JSON）、`interceptMentionBackspace`（无数据 / 命中选中）、`deleteMentionSelection`（路径 A 当前选区 / 路径 B pending 回退）、`onKeyDown` 的 `isInComposition` 守卫（组合态让路 / 非组合态选中），共 11 个用例。

## 五、验证

- 新增 Robolectric 单元测试，覆盖两段式删除（JSON 解析、第一步选整段、第二步真删）与组合态守卫，共 11 个用例全部通过；
- demo 模块编译通过。

## 附：与官方 WeiboMentionDemo 的差异对照

官方 demo 是纯 Kotlin Compose（Android/iOS 原生 Compose UI）实现；我们的 MentionPublisher 跑在 Kuikly 的 Compose DSL 上，而 Kuikly 的 `BasicTextField` 底层桥接的是原生 `EditText`。这一架构差异导致下面几项与官方"实现位置"不同，但"用户看到的体验"保持对齐。

| 方案 | 是否纯 Compose | 是否与官方对齐 | 差异与原因 |
|------|--------------|--------------|-----------|
| 高亮（AnnotatedString） | 是，Compose 层写 AnnotatedString | 对齐（使用层） | 都用 `BasicTextField(value = tfv.copy(annotatedString = displayText))` + `buildAnnotatedString { addStyle(蓝) }`。差异只在渲染底层：官方由 Compose 自己渲染；Kuikly 通过框架桥接把 span 打到原生 EditText 的 ForegroundColorSpan。这是 Kuikly 框架的渲染机制，最终高亮效果一致。 |
| 两段式删除 | 否，落在原生 EditText 层（onKeyDown + InputConnection 拦截） | 效果对齐，实现层不对齐 | 官方在 `onValueChange` 里通过"不应用新文本"拦截（注释明确"不依赖 onKeyEvent"）；我们在原生层 `onKeyDown` 拦截。原因：Kuikly 底层是原生 EditText，桥接层对带 span 文本的 selectionChange / textInputStateChange 回调形状不可控，照搬官方 onValueChange 方案会偶发错乱，所以下沉到原生事件入口做拦截（事前拦下退格、改为选中整段）。 |
| 组合态守卫（SPAN_COMPOSING） | 否，原生 EditText 层 | 官方没有这一项 | 官方是纯 Compose，组合态由框架自身处理，无需显式守卫。Kuikly 桥接原生 EditText 后，中文组词时退格会撞上两段式拦截，必须显式用 SPAN_COMPOSING 让路给输入法。这是 Kuikly 桥接架构下才需要的补充。 |
| 候选下拉 | 是，Compose 层（LazyColumn / DropdownMenuItem） | 对齐 | 都用正则扫描 mentions + 下拉选 @昵称 插入 `@昵称 `，交互一致。 |

# MentionPublisher（@人发布器）实现总结

> 本文档面向导师，说明本轮在 Kuikly Compose 输入框里实现类微博 @人 功能的方案、改动与验证。

## 一、要解决的问题

微博发布器里 @人 有两条硬性体验要求：

1. 输入框里 @人的名字要蓝色高亮；
2. 删除 @人 时，第一次按删除键只把整段 @人 选中，第二次才真正删掉（两段式删除），避免一次退格把整段 @人 误删。

此外还要兼容中文输入法：用户用拼音组词（比如打 "zhangsan" 还没上屏成 "张三"）时按退格，应该交给输入法去删拼音字母，而不是误把组词里的字符当成 @人 边界去选中。

## 二、采用的方案

**1. 高亮：桥接 AnnotatedString → 原生 ForegroundColorSpan**

让 Kuikly 的 BasicTextField 真正消费 AnnotatedString 上标注的样式区间（spanStyles），把 @人 区间桥接到原生 EditText 的 ForegroundColorSpan，从而显示蓝色。这样写法与官方 Kotlin Compose 示例一致，官方示例换一个 import 就能直接复用。

**2. 两段式删除：原生层拦截退格**

在原生 EditText 层拦截退格事件，而不是在 Compose 的 onValueChange 里判断（带样式的 EditText 退格回调形状不可控，之前多次尝试都不稳定）：

- 第一次退格：若光标紧挨 @人 尾部，把选区扩到整个 @人 区间，并记录待删区间；
- 第二次退格：选区已覆盖 @人，真正删除该区间。

两条退格路径都覆盖：硬件键盘走 `onKeyDown(KEYCODE_DEL)`，软键盘走 `InputConnection.deleteSurroundingText`。

**3. 组合态守卫：检测 SPAN_COMPOSING**

用一个 `isInComposition` 判断：若 `editableText` 上存在输入法打上的 `SPAN_COMPOSING` 标记（表示拼音组词未上屏），退格直接交给输入法处理，跳过两段式删除逻辑。这是 Android 检测组合态的标准做法，对普通英文/数字输入和其他功能零影响。

**4. 插入交互：只保留候选下拉**

移除了原先"直接插入 @人"的按钮，@人 只通过输入框下方的候选下拉选择插入，候选名单为 张三 / 李四 / 王五 / Tom。

## 三、实现的功能

- @人在输入框内蓝色高亮；
- 两段式删除（先选中整段、再按一次删除）；
- 中文输入法组合态下退格不误触选中逻辑；
- 候选下拉选择 @人 插入。

## 四、改动的代码文件

| 文件 | 改动内容 |
|------|---------|
| `core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/KRTextFieldView.kt` | 新增 `isInComposition` 组合态守卫（`onKeyDown` 与 `deleteSurroundingText` 两处）；实现两段式删除的拦截与执行逻辑；清理调试日志 |
| `demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/compose/MentionPublisherDemo.kt` | 删除"直接插入"按钮及 `insertMention` 函数，仅保留候选下拉插入；候选名单赵六改为 Tom；高亮与候选交互 |
| `core-render-android/build.2.1.21.gradle.kts` | 新增单元测试依赖（junit、robolectric）与 testOptions 配置 |
| `core-render-android/src/test/java/com/tencent/kuikly/core/render/android/expand/component/KRTextFieldViewMentionTest.kt`（新增） | Robolectric 单元测试，覆盖两段式删除与组合态守卫 |

## 五、验证

- 新增 Robolectric 单元测试，覆盖两段式删除（JSON 解析、第一步选整段、第二步真删）与组合态守卫，共 11 个用例全部通过；
- demo 模块编译通过。

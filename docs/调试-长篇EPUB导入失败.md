# 长篇 EPUB 导入失败

## 现象与诊断

0.1.5 导入用户提供的《牧神记》时显示“书籍正文或位置索引过大，暂不支持导入”。原文件没有加入仓库。通过本地 Robolectric、真实 Readium 解析器和 BookRepository 复现，不操作设备。

测得输入文件 9,883,453 字节，解压后约 22.5 MB。Readium 提取 1,836 个正文资源、143,781 个文本源段、182,937 句、5,877,327 个正文 UTF-16 字符，最长文本段 255 字符。

旧逻辑在第 92,386 句失败：累计正文 2,943,000 字符，累计句子 locator JSON 32,000,118 字符，首先超过 3,200 万位置索引字符预算。原文件并未超过 100 MB 限制，正文也没有超过旧 800 万字符限制。完整书籍另超过旧 10 万句限制，因此只放宽位置预算仍无法解决。

## 假设与实验

预测：若是应用索引预算误拒，而非 EPUB 损坏，Readium 应能继续遍历完整正文；删除导入流程的落盘步骤、只计数时，应读完所有章节。实际只读计数成功，而真实导入在旧 SentenceIndexer 限制处失败。

旧分句调用 `Locator.Text.substring()`，为每句构造该段完整前文和后文，并重复保存资源、选择器和 JSON 字段。一个只有 8,000 字符的原创段落 `“甲。” × 4000`，完整定位文本累计便达到 3,200 万字符，再加 JSON 字段即触发同一错误。整段所有句子还会在预算检查前被一次性构造，短句密集长段落可能先耗尽内存。

旧代码上的回归基线：原文件导入、原创 8,000 字符重复段和原创 100,001 句长篇，三项全部在同一容量检查失败；原文件只读诊断通过。

## 修复设计

- 每个 Readium 原始文本段保存一个共享源定位，每句保存 UTF-16 起止范围。原文、完整 before/after、资源与元素选择器保持不变；需要导航或高亮时才还原当前句 locator。
- 新索引格式使用共享源表，继续读取旧版格式；书架元数据版本不变，已有书籍无需重新导入。
- 阅读位置匹配直接使用共享源和范围构造有限的规范化上下文，避免打开书后再次为所有句子展开整段定位。
- 支持最多 50 万句、2000 万正文字符；按共享源计定位预算。原文件、资源、解压内容和单段限制继续保留。
- 索引读写统一限制为 128 MiB；写入超限时原子回滚，不留下导入成功却无法读取的文件。

## 本地回归

原创样本由测试流式生成，不包含用户书籍正文。覆盖超过旧句数与正文长度上限的长篇、重复句完整定位、65,536 字符短句密集段、旧索引兼容、损坏范围与索引写入回滚。测试入口位于 `app/src/test/java/com/freereading/data/`。

原文件在 `-Xmx256m` 的独立本地测试进程中通过只读计数和真实导入／重新读取两项检查，均无失败、错误或跳过。重新读取后句数为 182,937，最后正文资源序号为 1,835；索引文件为 100,062,610 字节，低于 128 MiB。首句、第 92,386 句、第 100,001 句和末句的完整 locator JSON 摘要均与从原文件直接解析的基线一致。这验证定位数据保持一致，不代表本轮已做设备高亮或 TTS 播放测试。

原文件临时诊断用例验证后移出仓库，避免依赖个人文件路径或复制用户书籍。保留的原创回归可运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.freereading.data.BookRepositoryCapacityTest' --tests 'com.freereading.data.SpeechIndexStoreTest' --tests 'com.freereading.data.BookRepositoryCacheTest' --tests 'com.freereading.reader.SpeechSentenceAnchorsTest' --no-daemon --max-workers=2
```

全量本地验证：23 个测试类、197 条测试通过，无失败、错误或跳过；Debug APK 构建成功，Lint 无错误（仍有既有警告与提示）。修复 APK 为 0.1.6、versionCode 7。未运行 ADB、模拟器或设备测试。

## 发布前审查

容量扩大后，原先每句保留约 320 字符匹配上下文的方案仍有整章内存风险。可合法构造同一资源内 15 个 65,536 字符段落，共 491,520 句；256 MB JVM 堆下建立整章匹配锚点的回归先复现 `OutOfMemoryError`。改为句子范围与共享上下文提供器，只在匹配时生成所需窗口，提供器最多缓存当前一个源段的边界表。

整段内容含 200 个相同短句时，原匹配器还会因为中间句前后上下文更丰富，返回第 81 句而非段首；本地回归已复现 `expected: 0, actual: 80`。整段匹配应按目标文本内的最早句子位置评分，并只比较必要的邻接窗口，避免扫描所有重复位置和复制整段字符串。

补修后，同一大章可在 256 MB 堆中构造锚点并正确匹配局部选词和完整 65,536 字符段落，段首回归返回预期索引；原有 Unicode 空白、emoji、重复句局部选词等定位回归也保持通过。三类定位测试共 21 条、无失败、错误或跳过：

```bash
./gradlew -PunitTestMaxHeapSize=256m :app:testDebugUnitTest --tests 'com.freereading.reader.SpeechSentenceAnchorsCapacityTest' --tests 'com.freereading.reader.SpeechSentenceAnchorsTest' --tests 'com.freereading.reader.SentenceLocatorMatcherTest' --no-daemon --max-workers=2
```

## 方法入口

本次使用了“先写预测，再用失败输入和成功对照检验”的方法：[Testing a Hypothesis](https://www.debuggingbook.org/html/Intro_Debugging.html#Testing-a-Hypothesis)。哪项观察真正区分了“EPUB 损坏”和“应用预算误拒”？

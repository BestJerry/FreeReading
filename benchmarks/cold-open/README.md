# 冷打开阶段测量

这里保留本次使用的临时性能探针，默认不参与应用单测，避免每次回归生成、解析约 100 MB 索引。正常测试中的 `BookRepositoryReadingBootstrapTest`、`ReadiumReadingOpenTest` 和阅读器生命周期用例验证加载顺序、资源读取次数与进度行为，不使用易波动的毫秒阈值。

需要复测时，将两个 `.kt` 文件临时复制到对应测试包：

```bash
cp benchmarks/cold-open/ColdOpenRepositoryDiagnosticTest.kt app/src/test/java/com/freereading/data/
cp benchmarks/cold-open/ReadiumColdOpenDiagnosticTest.kt app/src/test/java/com/freereading/epub/

./gradlew :app:testDebugUnitTest \
  --tests 'com.freereading.data.ColdOpenRepositoryDiagnosticTest' \
  --tests 'com.freereading.epub.ReadiumColdOpenDiagnosticTest' \
  --no-daemon --max-workers=2
```

使用 JDK 17。`ReadiumColdOpenDiagnosticTest` 默认生成原创长书；可为该命令设置 `FREEREADING_PERF_EPUB=/path/to/local-book.epub`，只读已有本地 EPUB。测试仅输出耗时和计数，不输出正文。Repository 探针始终使用原创样本。

测量后移除刚复制的两个临时测试副本，保留这里的源文件。结果位于 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 的 `system-out`，标记为 `COLD_OPEN_REPOSITORY_DIAGNOSTIC` / `FREEREADING_COLD_OPEN`。

这些是冷 Repository、同一 JVM 内的阶段测量，操作系统缓存未受控；不能作为手机冷启动或 WebView 首帧的实测值。已运行数据和输入范围见[性能记录](../../docs/性能-冷启动打开书籍.md)。

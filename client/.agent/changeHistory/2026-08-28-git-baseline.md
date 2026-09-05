# Git 基线建立记录

- 日期：2026-08-28
- 目标：在后续 Bug 修复前建立可随时回退的客户端基线
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 基线提交：`32536b5e84d131bea658d7223c0edcaa9dabc7e1`

## 纳入内容

- 首次提交共纳入 395 个文件，包含 Maven 配置、JavaFX 客户端源码、图标、字体、音效和设置文件。
- 初始提交文本统计：新增 24124 行，删除 0 行；音频、图标等二进制资源由 Git 单独记录。
- 精确文件清单可通过 `git show --name-status 32536b5` 查看。

## 本次新增或修改

- `.gitignore`：补充忽略 IDE 状态、构建目录、运行日志、JVM 崩溃日志、源码 ZIP 备份、代码扫描报告和超大本地视频。
- `.agent/changeHistory/2026-08-28-git-baseline.md`：记录本次 Git 初始化和回退信息。

## 排除内容

- `target/`、安装包和打包 JAR
- `.idea/` 本地 IDE 状态
- `src/main/java/CodeScanOutput*.txt` 生成扫描报告
- `src/main/java/**/*.zip` 源码备份
- `src/main/resources/sounds/电脑不太行，吸力不够强.mp4`，约 96 MB，未被 Java 源码引用

## 验证

- Git 初始提交成功，分支为 `main`。
- 暂存清单未包含超过 20 MB 的意外文件。
- 服务端与客户端此前已在工作区副本中完成 Maven 编译验证。
- 当前没有配置远程仓库，因此本次提交只保存在本机。

## 回退

- 查看基线：`git show 32536b5`
- 后续改动需要回退时，优先使用 `git restore` 或创建回退分支；不要在有未保存工作时执行破坏性重置。

## 提交状态

- 基线源码与正式资源已提交。
- 本记录将在后续文档提交中纳入仓库。

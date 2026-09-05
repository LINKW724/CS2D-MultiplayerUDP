# Git 基线建立记录

- 日期：2026-08-28
- 目标：在后续 Bug 修复前建立可随时回退的服务端基线
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 基线提交：`df4de49b1d13f0c77e76beeb3f47210ee1dc7c05`

## 纳入内容

- 首次提交共纳入 103 个文件，包含 Maven 配置、服务端 Java 源码、地图、Web 控制页、设置文件及辅助脚本。
- 初始提交文本统计：新增 582383 行，删除 0 行；二进制文件由 Git 单独记录。
- 精确文件清单可通过 `git show --name-status df4de49` 查看。

## 本次新增或修改

- `.gitignore`：补充忽略 IDE 状态、构建目录、运行日志、JVM 崩溃日志、源码 ZIP 备份、生成的 JavaDoc、代码扫描报告和测试可执行文件。
- `.agent/changeHistory/2026-08-28-git-baseline.md`：记录本次 Git 初始化和回退信息。

## 排除内容

- `target/` 构建产物
- `.idea/`、`.vscode/` 本地 IDE 状态
- `error.log`、`hs_err_pid*.log` 运行和崩溃日志
- `javaDoc/` 生成文档
- `src/main/java/CodeScanOutput*.txt`、日期命名扫描输出
- `src/main/java/**/*.zip` 源码备份
- `src/main/java/cs2d/test/*.exe` 测试可执行文件

## 验证

- Git 初始提交成功，分支为 `main`。
- 暂存区检查未发现超过 20 MB 的意外文件。
- 既有源码包含历史尾随空格；为保证基线忠实，未进行格式化。
- 当前没有配置远程仓库，因此本次提交只保存在本机。

## 回退

- 查看基线：`git show df4de49`
- 后续改动需要回退时，优先使用 `git restore` 或创建回退分支；不要在有未保存工作时执行破坏性重置。

## 提交状态

- 基线源码已提交。
- 本记录将在后续文档提交中纳入仓库。

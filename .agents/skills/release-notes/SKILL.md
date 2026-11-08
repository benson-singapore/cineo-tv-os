---
name: release-notes
description: 从 Git tag 与提交历史审计或生成版本更新日志（release notes）。当用户说“检查更新日志”“添加更新日志”“写版本日志”“生成 release notes”“补 vX.Y.Z 的日志”时使用；默认输出 docs/update/vX.Y.Z.md，供 GitHub Release 直接引用。只读取证、不编造内容、不自动改版本号、不自动打 tag。
---

# Release Notes

这个 skill 只负责一件事：**让更新日志有据可查**。

它从 Git tag、commit 历史和 `git diff --stat` 中取证，生成或审计
`docs/update/<tag>.md`。版本号确认、提交、打 tag、触发 CI 和发布由项目自己的
`<project>-release` skill 负责。本项目对应的是 `cineo-tv-release`。

## 核心原则

1. **证据驱动**：功能、修复、变更只能来自 `git-release-context.sh` 的输出。
   历史里没有的内容不得编造。
2. **只读优先**：Check 模式绝对不写文件；Generate 模式只写目标日志文件。
3. **文件名等于 tag 名**：`v1.2.3` 对应 `docs/update/v1.2.3.md`，不要写成
   `1.2.3.md`、`v1.2.3-release.md` 或放进子目录。
4. **默认中文**：除非用户明确要求其他语言。
5. **先日志后构建**：日志应在打 tag 之前提交。CI 直接读取仓库里的这份文件
   作为 Release 正文，避免日志与产物不一致。
6. **不提前宣称成功**：在 GitHub Actions 实际完成前，不得写“构建已通过”
   “发布成功”等结论；构建/发布章节只描述流水线的预期产出。

## 取证脚本

路径（相对仓库根目录）：

```bash
.agents/skills/release-notes/scripts/git-release-context.sh
```

脚本是**只读**的：不创建、不修改、不暂存、不提交、不打 tag。

### 已存在的 tag

```bash
.agents/skills/release-notes/scripts/git-release-context.sh --tag v1.2.3
```

### 尚未打 tag 的计划发布

```bash
.agents/skills/release-notes/scripts/git-release-context.sh \
  --target-tag v1.2.3 \
  --from-tag  "$(git tag --merged HEAD --sort=-v:refname | head -n 1)"
```

仓库里完全没有 tag 时，省略 `--from-tag`，脚本会按**首发**处理：

```bash
.agents/skills/release-notes/scripts/git-release-context.sh --target-tag v1.0.0
```

### 输出字段

| 字段 | 含义 |
| --- | --- |
| `TAG` | 目标 tag 名 |
| `TAG_EXISTS` | tag 是否已存在：`true` / `false` |
| `TARGET_REF` | 取证目标：已存在 tag 用 `refs/tags/<tag>`，计划发布用 `HEAD` |
| `TAG_COMMIT` | 目标 commit |
| `TAG_DATE` | 目标 commit 的 ISO 时间 |
| `TAG_SUBJECT` | 目标 commit 标题 |
| `PREVIOUS_TAG` | 上一个 tag；首发时为空 |
| `RANGE` | 本次日志覆盖的 commit 范围 |
| `INITIAL_RELEASE` | 是否首发：`true` / `false` |
| `HEAD_COMMIT` / `HEAD_DATE` / `HEAD_SUBJECT` | 当前 HEAD 信息 |
| `BRANCH` | 当前分支 |
| `DIRTY` | 工作区是否有未提交改动 |
| `[COMMITS]` | 每行 `<短 hash> <ISO 日期> <标题>` |
| `[STAT]` | 该范围的 `git diff --stat` |

## 模式一：Check（只审计，不修改）

触发示例：“检查更新日志”“看看哪些版本缺日志”。

执行：

```bash
git tag --sort=-v:refname
ls -1 docs/update/ 2>/dev/null || true
```

逐项报告：

- 每个 tag 是否存在同名笔记文件；
- 文件名是否**精确等于** tag 名（大小写、前缀 `v` 都要一致）；
- 最新 tag 是否缺少笔记；
- 是否存在没有对应 tag 的孤立笔记。

输出建议清单后，询问用户是否补齐。**未经用户确认，不写任何文件。**

## 模式二：Generate（生成或更新某个 tag 的笔记）

1. 运行取证脚本，保留完整输出作为写作依据。
2. 读取已有文件（如果存在），在原有内容上更新，不要无故删除用户写过的段落。
3. 按下面的模板写 `docs/update/<tag>.md`。
4. 用 `git diff -- docs/update/<tag>.md` 自查，确认没有编造内容。
5. 报告写入路径和取证范围即可；不要自动 commit、tag 或 push。

### 分类规则

- `feat(...)` → 新功能
- `fix(...)` → 修复
- `refactor(...)` / `perf(...)` / `style(...)` → 变更与优化
- `docs(...)` / `chore(...)` / `build(...)` / `ci(...)` → 构建、文档或维护
- 无法归类的 commit 放进“其他变更”，不要强行包装成功能
- 同一主题的多个 commit 可以合并成一条，但必须能追溯到 `[COMMITS]` 里的 hash

### 笔记模板

```markdown
# vX.Y.Z

## 版本

- 版本号：vX.Y.Z
- 发布时间：YYYY-MM-DD
- 上一版本：vA.B.C（首发写“首次发布”）
- 覆盖范围：`vA.B.C..vX.Y.Z`（首发写全部历史）

## 概述

用 1–3 句话说明这个版本主要解决什么、用户能感知到什么。

## 新功能

- ...

## 变更与优化

- ...

## 修复

- ...

## 构建与发布说明

- 产物：项目发布流水线生成的安装包（具体文件名由发布 skill / workflow 决定）
- 本日志在打 tag 前提交，GitHub Actions 直接读取本文件作为 Release 正文
- Actions 尚未完成时，不要在这里写“构建已通过”

## 来源范围

- 范围：`vA.B.C..vX.Y.Z`
- 提交：
  - `abc1234` feat(scope): ...
  - `def5678` fix(scope): ...
```

首发的“来源范围”可以写成：

```markdown
- 范围：`vX.Y.Z` 可达的全部历史
- 初始版本，无上一 tag
```

## 与其他 skill 的配合

发布流程由项目编排 skill 负责，例如本项目的 `cineo-tv-release`：

1. 编排 skill 先确认版本号；
2. 调用本 skill 的取证脚本；
3. 写 `docs/update/vX.Y.Z.md`；
4. 同步版本文件并提交；
5. 打 tag、push、触发 GitHub Actions；
6. Actions 将同一份日志作为 Release 正文。

分工边界：**release-notes 决定“写什么”，`<project>-release` 决定“何时发、怎么发、发完怎么验证”。**

## 红线

- 不编造历史中没有的功能、修复、性能提升或测试结果。
- 不把“计划做”写成“已完成”。
- 不在 Actions 运行结束前写“构建成功”。
- 不擅自修改版本号、提交、打 tag、push。
- 不删改其他版本的日志来凑本次范围。

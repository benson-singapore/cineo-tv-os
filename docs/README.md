<div align="center">
  <img src="../assets/branding/cineo_mark.png" width="72" alt="Cineo Logo" />
  <h1>Cineo TV OS 开发文档</h1>
  <p>Android TV 客户端 · MVP 路线 · 手机互联与同步设计</p>
  <p><a href="../README.md">返回项目首页</a></p>
</div>

## 当前状态

本仓库是 Cineo Flutter 对应的 Android TV 客户端，使用 Kotlin、Compose for TV 与 Media3 构建。现有源码包含导航、首页、分类、搜索、详情、选集、播放、视频源管理、收藏、观看历史与 TMDB 元数据等实现。

**当前处于持续开发与验证阶段。** 核心功能已有代码，不再是“尚未创建工程”的状态；全流程遥控器体验、播放稳定性和目标当贝设备兼容性仍需按验收标准验证。手机绑定、手机输入与云端同步尚未实现。

原开发计划保留了初始阶段划分和未勾选任务，不能直接作为实时完成度。源码存在、功能可运行和验收通过是不同状态；任务应在核对实现与验证记录后更新。

## 文档导航

| 文档 | 用途与状态 |
| --- | --- |
| [项目 README](../README.md) | 品牌、功能概览、Flutter 版关系、构建安装与工程结构 |
| [DEVELOPMENT_PLAN.md](./DEVELOPMENT_PLAN.md) | MVP 范围、阶段任务、依赖及验收标准；初始清单待结合源码复核 |
| [DEVICE_CONNECT_DESIGN.md](./DEVICE_CONNECT_DESIGN.md) | 统一绑定、手机输入、云端同步、配额与实施顺序；待评审、未实现 |
| [SYNC_DESIGN.md](./SYNC_DESIGN.md) | 同步数据模型、冲突规则与任务拆分；原始草案，结合整合提案阅读 |
| [DECISIONS.md](./DECISIONS.md) | 设备、API、身份、存储与同步等待确认事项 |

## 建议阅读顺序

1. 从项目首页了解产品定位、现有能力和运行要求。
2. 结合开发计划与源码，确认本次开发涉及的任务和验收条件。
3. 开发跨端能力前，先阅读设备互联整合方案，再查看同步草案中的数据细节。
4. 对尚无定论的依赖查看决策记录；提案中的建议值不视为已确认决策。

## 当前技术基线

- 单 Android `app` 模块，现有应用 ID 为 `com.nova.tv`。
- Kotlin + Compose for TV；Media3 / ExoPlayer 提供播放能力。
- MacCMS 兼容 API 与可选 TMDB 元数据增强。
- 当前本地数据使用 `SQLiteOpenHelper`；Room / DataStore 属于原计划中的方案，迁移需先确认。
- 当前 `minSdk = 36`、`compileSdk = 37`、`targetSdk = 37`，目标设备覆盖范围尚待核实。

## 更新约定

1. 任务使用 `[ ]` 未开始、`[/]` 进行中、`[x]` 已完成、`[-]` 暂缓；完成状态需要对应实现和验证依据。
2. 遇到外部依赖时，在任务项中说明原因，并链接到 `DECISIONS.md` 的对应事项。
3. 阶段完成前逐项核对验收条件，分别记录模拟器和真机结果。
4. 需求或技术方案发生变化时，更新计划与决策，明确哪些是现状、哪些是提案。
5. 对外名称统一使用 **Cineo TV OS**；历史文档中的 Nova TV 为原工作名称，现有包名与工程标识不随文档改名自动迁移。

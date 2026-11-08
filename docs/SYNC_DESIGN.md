# 多端同步方案

**状态：** 方案草案，尚未实现

**整合评审入口：** [DEVICE_CONNECT_DESIGN.md](./DEVICE_CONNECT_DESIGN.md) 补充统一设备绑定、手机实时输入及费用控制，并列出本草案需要修订的配对凭证、同步频率、删除重建等事项。两份文档均待评审，实施前需统一协议。
**范围：** TV 端（本仓库，`com.nova.tv`）与手机端（`/Users/benson/Documents/app/cineo-flutter`，Android/iOS）
**对应计划：** `DEVELOPMENT_PLAN.md` 中 P1「跨设备历史与收藏同步」、P2「手机二维码配置」（见 D-07）
**相关决策：** [DECISIONS.md](./DECISIONS.md) D-04 至 D-09

## 1. 目标与非目标

**目标**

- 手机端观看后，电视端点击“同步”即可看到最新观看记录、收藏、站点和设置。
- 电视端观看后，手机端同样能同步。
- 配对完成后，两端不需要同时在线。
- 同步服务不可用时，两端的观看、收藏、站点管理照常工作。

**非目标（首版）**

- 实时推送。首版在启动、回到前台和手动点击时同步。
- 同步下载任务、缓存、媒体文件和 App 锁 PIN。
- 账号体系和多用户。
- 自动合并不同站点上的同一部剧（见 D-09）。

## 2. 两端现状

| 项 | TV 端 | 手机端 |
|---|---|---|
| 本地库 | `cineo_tv.db` v1，手写 `SQLiteOpenHelper`（`CineoDatabase.kt`） | `cineo_local_media.db` v14，sqflite（`local_media_repository.dart`） |
| 观看记录 | `watch_history`，每部剧一行，上限 50 条。记录 JSON 含线路名、集标题、集 URL | `progress`，每集一行，主键 `mediaId:episodeId`，无上限，有 `is_hidden` |
| 收藏 | `favorite_videos`，保存整个视频 JSON | `favorites`，仅 `media_id` 与 `created_at`；标题和海报在 `media_snapshots` |
| 站点 | `video_sources`，无时间戳，保存时整表重写 | `sources`，无时间戳，硬删除 |
| 视频 ID | `<source.id>:<vod_id>` | 同左 |
| 进度保存 | 播放中每 5 秒，退出时不额外保存 | 每 10 秒，关闭时保存 |
| TMDB token | SharedPreferences，Keystore 加密 | `flutter_secure_storage` |
| 设置 | `app_settings` 键值表 | SharedPreferences |

**对设计有影响的差异**

1. 两端视频 ID 格式相同，但 `source.id` 由各端本地生成。同一个站点在两端的 ID 可能不同，因此不能直接用它做跨端键。
2. 观看记录粒度不同。TV 每部剧只保留最后一集，手机每集一行。
3. TV 的观看记录缺少线路序号和集序号（`WatchRecord` 只有 `lineName`、`episode.title`、`episode.url`）。手机端靠 `episodeId` 中的 `groupIndex` 与 `episodeIndex` 定位集数。跨端续播前必须补齐。
4. 集 URL 是解析后的绝对地址，可能过期（见 D-02 的 URL 过期规则）。不能把它当作跨端匹配键，也不应同步。
5. 两端都是硬删除，没有墓碑。手机端的“清空历史”是整表 `DELETE`。
6. 两端都没有同步、二维码和 HTTP 服务代码。TV 端只依赖 `HttpURLConnection`。

## 3. 方案对比

| | 局域网直连 | 云端同步（推荐） | 用户自带 WebDAV |
|---|---|---|---|
| 两端需同时在线 | 是 | 否 | 否 |
| 手机刷完、电视随时同步 | 否，手机必须开着 | 是 | 是 |
| 增量同步 | 可以 | 可以 | 弱，只能整文件读写 |
| 新增依赖 | TV 端内嵌 HTTP 服务、mDNS，iOS 需本地网络权限 | 一个同步服务，两端同步客户端 | 两端各需 WebDAV 客户端 |
| 运维 | 无 | 需要托管 | 用户自备 |

**结论：以云端同步为主。** 局域网直连无法满足“手机看完，电视随时同步”的目标，首版不做。传输层以接口抽象，以后可以加 WebDAV 适配器或局域网直连。

## 4. 总体架构

- **本地优先。** 两端 SQLite 仍是唯一数据源，界面只读本地库。
- **outbox。** 本地每次写入同时写入 `sync_outbox`。同一键只保留最新一条。
- **同步引擎。** 批量加密推送 outbox，拉取远端增量并合并进本地库。
- **服务端。** 只存密文和元数据，负责分配 `seq` 与分发增量，不解析内容。
- **两个版本号。** `updated_at`（设备毫秒时间）用于冲突判定；`seq`（服务端递增序号）用于拉取游标。

```
手机端 / TV 端 ── 写本地库 ──▶ sync_outbox
                                   │ 合并同键、加密
                                   ▼
                          POST /push ──▶ 服务端（密文 + seq）
                                   │
合并进本地库 ◀── GET /pull?since=seq ◀┘
```

## 5. 配对流程

1. TV 端点击“添加手机”。TV 生成 `space_id`（128 位随机数）和 `sync_key`（256 位随机数）。
2. TV 显示二维码，内容为 `novatv://pair?v=1&server=<地址>&space=<space_id>&key=<base64url(sync_key)>`，5 分钟后失效。
3. 手机扫码，将配置存入安全存储（Flutter 端用 `flutter_secure_storage`）。
4. 手机首次同步时上传本地全量快照，TV 拉取后合并。
5. 任一已配对设备都能生成二维码，让新设备加入同一空间。

**注意事项**

- 二维码内含密钥，只在屏幕上显示，过期即失效。
- 首版没有账号，`space_id` 与 `sync_key` 就是凭证。`sync_key` 丢失后，同步空间无法恢复，需要“重置同步空间”，所有设备重新配对。
- 首版不做“移除单个设备并轮换密钥”，因为轮换需要其余设备在线时接收新密钥。这项放到后续阶段。

## 6. 数据模型与同步格式

### 6.1 服务端保存的信封（明文元数据）

| 字段 | 说明 |
|---|---|
| `space_id` | 同步空间 |
| `entity` | `history` / `favorite` / `source` / `setting` |
| `key` | 实体主键，见 6.2 |
| `seq` | 服务端分配的递增序号 |
| `updated_at` | 写入设备的毫秒时间，用于 LWW |
| `device_id` | 写入设备，LWW 同时间时作为平局依据 |
| `deleted` | 是否为墓碑 |
| `ciphertext`、`nonce` | 载荷加密后的内容与 GCM 随机数 |
| `schema_version` | 载荷结构版本 |

### 6.2 键规则

| 实体 | 键 | 说明 |
|---|---|---|
| `history` | `{sourceKey}:{vodId}:{lineName}:{episodeIndex}` | 按集存储。TV 端需补 `episodeIndex`（见第 10 节） |
| `favorite` | `{sourceKey}:{vodId}` | 按视频 |
| `source` | `{sourceKey}` | 按站点 |
| `setting` | `{namespace}.{key}` | 见 6.4 |

`sourceKey` 为站点 API 地址规范化后的哈希，例如 `sha1(小写 host + 路径，去掉末尾斜杠和默认端口)`。这样两端即使本地 `source.id` 不同，也能对上同一个站点。具体规范化规则在 SYNC-02 中用两端的真实站点数据验证。

### 6.3 载荷字段

- **history：** `sourceKey`、`sourceName`、`vodId`、标题、海报、分类、年份、`lineName`、`lineIndex`、`episodeIndex`、`episodeTitle`、`positionMs`、`durationMs`、`updatedAt`。
  **不包含集 URL。** 接收端用 `sourceKey` 与 `vodId` 重新请求站点详情，取得当前可播放地址。
- **favorite：** `sourceKey`、`sourceName`、`vodId`、标题、海报、分类、年份、简介、评分、`addedAt`。
- **source：** 名称、API 地址、启用状态、成人标记。
  设备本地字段（延迟、最近错误、检查时间）不同步。
- **setting：** 只同步白名单键。

### 6.4 设置命名空间

- `tv.*`、`app.*` 分别属于两端，默认不互通。例如 TV 的菜单顺序与手机端的底部导航互不影响。
- 共享键（如成人内容显示开关）需要在白名单中逐个映射。
- TMDB token 为可选，见 D-06。

### 6.5 墓碑与保留期

- 删除写入 `deleted=true` 的墓碑，载荷为空。
- 墓碑保留 90 天。超过保留期未同步的设备，拉取时收到 `410 resync_required`，需要重新做全量上传与拉取。

## 7. 服务端接口

以下接口与具体框架无关（见第 12 节）。

- `POST /v1/spaces`：创建同步空间，由 TV 端调用。服务端只保存 token 的哈希。
- `POST /v1/spaces/{space_id}/push`：请求体为信封数组，单次最多 500 条，总大小不超过 1 MB。
  - 对每个 `(entity, key)`，比较 `updated_at` 与 `device_id`，较新者替换并分配新 `seq`，较旧者返回 `ignored`。
  - 返回 `max_seq`。
- `GET /v1/spaces/{space_id}/pull?since=<seq>&limit=500`：返回 `records`、`next_seq`、`has_more`。
  - `since` 早于保留窗口时，返回 `410 resync_required`。
- **鉴权：** 请求头 `X-Space-Token`，值为 `HMAC(sync_key, "auth")`。服务端只存它的哈希，因此拿不到 `sync_key` 本身。
- **限流：** 每个空间每分钟请求数设上限。
- **日志：** 不记录载荷、`sync_key` 或 token，只记录状态码与耗时。

## 8. 冲突与合并

- **history、favorite、source、setting：** 按键做 LWW，比较 `updated_at`，平局比较 `device_id`。墓碑与普通记录同样参与比较。
- **本地较新：** 合并时保留本地记录，并把它重新放入 outbox，使服务端得到较新版本。
- **进度类数据：** 播放中写入很频繁，但只在批量推送时上传同键的最新一条。
- **续播选集：** TV 端“继续观看”每部剧只展示最近一集。`episode_progress`（TV 新增，见第 10 节）保存全部集，`watch_history` 由它派生。
- **时钟偏差：** 设备时间可能不准。服务端为每条记录附加 `received_at`。若 `updated_at` 比 `received_at` 晚超过 10 分钟，按 `received_at` 处理，防止一台时钟超前的设备永远赢得冲突。

## 9. 安全与隐私

- **端到端加密：** AES-256-GCM，密钥为 `sync_key`，每条记录使用独立随机 nonce。
  服务端只能看到 `space_id`、`entity`、`key`、时间戳和大小。
- **密钥存放：** `sync_key` 只存入安全存储（Android Keystore、iOS Keychain）。不写入日志、数据库明文或截图。
- **TMDB token：** 默认不同步（D-06）。开启后随设置加密同步，接收端用本机 Keystore/Keychain 重新加密保存。
- **App 锁：** PIN、salt 与 verifier 不同步。
- **成人内容：** 站点列表可能包含成人源。同步前需要明确告知用户，并沿用现有的成人内容开关（手机端 `show_adult_sources`、`hide_adult_history`；TV 端 `is_adult`）。服务端即使加密也无法读取内容，但运营方的合规责任需要 D-04 与 D-08 确认。

## 10. 两端本地改造

### TV 端（`cineo_tv.db` v1 → v2）

- 实现真正的 `onUpgrade`。当前该方法几乎为空，首次迁移前备份数据库文件，失败时回滚到备份。
- 新增表：`sync_outbox`、`sync_state`（保存 `last_seq`、空间配置）、`episode_progress`。
- 历史、收藏、站点增加 `updated_at` 与 `deleted_at`。
- 观看记录补充 `lineIndex`、`episodeIndex`。升级前的旧记录缺少这两个字段，拉取后按 `episodeUrl` 与标题尽力匹配，匹配不到则按标题定位。
- 退出播放器时保存一次进度。当前退出时最多丢失 5 秒进度。
- 统一 `CineoDatabase` 实例。当前 `MacCmsRepository`、`NavigationMenuPreferences`、`VideoScreens` 各自创建一个。
- `writeSources` 从“整表删除后重新插入”改为按行增量更新，否则每次保存都会产生大量无效 outbox 记录。
- 删除改为写墓碑。

### 手机端（`cineo_local_media.db` v14 → v15）

- 新增 `sync_outbox`、`sync_state`。`favorites`、`sources` 增加 `updated_at`、`deleted_at`。
- `clearProgress` 的整表 `DELETE` 改为对每行写墓碑。
- `mergeMediaHistory` 会按标题跨站点合并历史并改写 `media_id`。同步后这些改写必须进入 outbox，否则两端会不一致。该功能是否保留见 D-09。
- 写入 `sources` 时区分本地 `source.id` 与跨端 `sourceKey`。

## 11. 同步触发时机

| 场景 | 动作 |
|---|---|
| 本地写入 | 写入 outbox，同键只保留最新一条 |
| 批量推送 | 每 30 秒一次 |
| 立即推送 | 播放器退出、App 切后台、用户点击“同步” |
| 拉取 | 启动、回到前台、用户点击“同步” |
| 拉取后 | 推送本地较新的 outbox |
| 失败 | 指数退避，最长间隔 5 分钟 |

TV 端在设置页提供“立即同步”按钮，显示上次同步时间与结果。

## 12. 服务端选型

| 方案 | 说明 | 适合 |
|---|---|---|
| 复用计划中的 Spring Boot 后端 | D-02 提到已有或计划中的 Spring Boot 服务，在其中增加同步接口与表 | 后端会上线时，避免维护两套服务 |
| Cloudflare Workers + D1 | 无服务器，D1 为 SQLite | 没有现成后端，希望运维最少。需确认国内访问情况 |
| 用户自带 WebDAV | 每个空间一个目录，整文件读写 | 自托管用户。无增量能力，首版不做 |

**建议：** 若 Spring Boot 后端会上线，优先复用。否则用 Workers + D1 做独立同步服务。最终选择见 D-08。

## 13. 分阶段任务

同步阶段记为 S，与 `DEVELOPMENT_PLAN.md` 的阶段独立。

**S0 决策与协议**

- [ ] **SYNC-01** 确认 D-05 至 D-09，定稿第 6、7 节的协议。
- [ ] **SYNC-02** 定稿 `sourceKey` 规范化规则，用两端现有站点数据验证。

**S1 服务端**

- [ ] **SYNC-03** 实现空间创建、push、pull、墓碑保留窗口与 `resync_required`。
- [ ] **SYNC-04** 实现 token 校验、限流与日志脱敏。

**S2 TV 端**

- [ ] **SYNC-05** DB v2 迁移（先备份），新增 outbox、sync_state、episode_progress，历史/收藏/站点增加时间戳与墓碑。
- [ ] **SYNC-06** 观看记录补充 `lineIndex`、`episodeIndex`；退出播放器时保存进度。
- [ ] **SYNC-07** 二维码配对页（生成配对 URL、倒计时、失效处理）。
- [ ] **SYNC-08** 同步引擎，设置页“立即同步”。

**S3 手机端**

- [ ] **SYNC-09** DB v15 迁移，新增字段与 outbox；`clearProgress` 改为墓碑。
- [ ] **SYNC-10** 扫码配对（`mobile_scanner`）与同步引擎。
- [ ] **SYNC-11** 设置页入口与同步状态展示。

**S4 联调**

- [ ] **SYNC-12** 手机观看后，TV 同步，续播集数与进度正确。
- [ ] **SYNC-13** 两端离线修改同一记录，联网后按 LWW 合并，无丢失。
- [ ] **SYNC-14** 删除会传播到另一端，且不会复活。
- [ ] **SYNC-15** 抓包确认服务端只看到密文。
- [ ] **SYNC-16** 服务不可用时，观看、收藏、站点管理正常。

**依赖：** S0 完成后开始。S1 完成后 S2 与 S3 可并行。S4 需要 S1 至 S3 全部完成。

## 14. 风险

- **TV 观看记录缺少集序号。** 必须先补字段（SYNC-06），否则跨端续播只能按标题猜测。
- **集 URL 过期。** 同步不传播 URL，接收端需重新请求站点详情，依赖站点接口稳定。
- **同一部剧在不同站点的 `vod_id` 不同。** 无法自动合并，见 D-09。
- **存储实现与计划不一致。** `DEVELOPMENT_PLAN.md` 的 DATA-07 要求 Room 与 DataStore，现有代码为 `SQLiteOpenHelper`。见 D-05。
- **配对二维码泄露等于同步空间泄露。** 二维码限时、仅显示一次。
- **数据库迁移失败。** 升级前备份，失败时回滚。

## 15. 待确认事项

见 [DECISIONS.md](./DECISIONS.md) 中的 D-05 至 D-09。

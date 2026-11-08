<div align="center">
  <img src="assets/branding/cineo_mark.png" width="112" alt="Cineo Logo" />
  <h1>Cineo TV OS</h1>
  <p><strong>把 Cineo 的观影体验，带到客厅大屏。</strong></p>
  <p>Cineo Flutter 的 Android TV 版本 · Kotlin 原生构建 · 为遥控器交互设计</p>
  <p>
    <img src="https://img.shields.io/badge/Platform-Android_TV-3DDC84?logo=android&amp;logoColor=white" alt="Platform: Android TV" />
    <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&amp;logoColor=white" alt="Language: Kotlin" />
    <img src="https://img.shields.io/badge/UI-Compose_for_TV-4285F4?logo=jetpackcompose&amp;logoColor=white" alt="UI: Compose for TV" />
    <img src="https://img.shields.io/badge/Player-Media3-8B5CF6" alt="Player: Media3" />
    <img src="https://img.shields.io/badge/Status-In_Development-F59E0B" alt="Status: In Development" />
  </p>
  <p>
    <a href="#功能概览">功能概览</a> ·
    <a href="#快速开始">快速开始</a> ·
    <a href="docs/DEVELOPMENT_PLAN.md">开发路线</a> ·
    <a href="docs/README.md">开发文档</a> ·
    <a href="https://github.com/benson-singapore/cineo-flutter">Flutter 版本</a>
  </p>
</div>

---

## 关于 Cineo TV OS

Cineo TV OS 是 [Cineo Flutter](https://github.com/benson-singapore/cineo-flutter) 面向电视场景的配套客户端，沿用 Cineo 的品牌与本地优先理念，让影视浏览、搜索、选集和播放更适合横屏大屏与遥控器操作。

TV 端采用 **Kotlin + Jetpack Compose for TV + Media3** 独立实现。这里的「TV OS」是项目名称；当前工程面向 **Android TV**，与 Flutter 手机端协同演进，功能进度以各端实现为准。

> **开发中：** 仓库已包含核心浏览、播放及本地数据代码，仍需持续完成遥控器焦点、设备兼容性与播放稳定性验证。手机扫码绑定、手机输入和云端同步目前处于方案阶段。

## 功能概览

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>大屏浏览</h3>
      <p>深色界面、首页 Hero、横向内容列表与侧栏导航，围绕 D-pad 焦点组织操作路径。</p>
    </td>
    <td width="50%" valign="top">
      <h3>多源内容</h3>
      <p>管理兼容 MacCMS 的 API 视频源，支持 JSON 配置导入、来源测试、分类浏览与关键词搜索。</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>连续观影</h3>
      <p>影视详情、线路与剧集选择，使用 Media3 播放 MP4 / HLS，并保存本地观看进度。</p>
    </td>
    <td valign="top">
      <h3>本地优先</h3>
      <p>视频源、收藏、观看记录与设置在本机持久化；可选配置 TMDB，补充影视元数据与图片。</p>
    </td>
  </tr>
</table>

### 当前实现与后续方向

以下状态根据仓库源码整理，表示已有实现入口，不代表已完成全部设备验收。

| 能力 | 当前状态 |
| --- | --- |
| 首页、分类、搜索、详情与选集 | 已有实现 |
| 视频源添加、编辑、启停、默认来源与配置导入 | 已有实现 |
| Media3 播放、播放控制与线路 / 剧集选择 | 已有实现，持续验证 |
| 收藏、观看历史与播放进度持久化 | 已有实现 |
| TMDB 元数据增强、缓存与菜单配置 | 已有实现 |
| 全流程遥控器焦点与返回体验 | 已有基础，持续完善 |
| Android TV 模拟器与当贝真机完整验收 | 待完成并归档验证记录 |
| 手机扫码绑定、手机输入与跨端同步 | 方案待评审，尚未实现 |

## 与 Flutter 版的关系

| | Cineo Flutter | Cineo TV OS |
| --- | --- | --- |
| 产品角色 | 跨平台影视客户端，手机端互联入口 | 面向客厅大屏的 Android TV 客户端 |
| 技术实现 | Flutter / Dart | Kotlin / Compose for TV |
| 主要交互 | 触屏操作 | 遥控器方向键、确认与返回 |
| 数据理念 | 本地优先 | 本地优先 |
| 跨端协同 | 计划在现有 App 内集成扫码与输入 | 计划提供配对、输入会话与同步入口 |

设备互联的当前提案是 **统一设备绑定 + 云端增量同步 + 按需实时输入**：手机与电视通过 Cineo 后端建立连接，无需处于同一 Wi-Fi；界面仍读取本地数据。实现范围、协议和安全设计见 [设备互联整合方案](docs/DEVICE_CONNECT_DESIGN.md)，相关能力尚未落地。

## 快速开始

### 1. 准备环境

- Android Studio，以及项目配置对应的 Android SDK / Build Tools。
- 支持 TV Launcher 的 Android TV 模拟器或可调试的电视设备。
- 使用仓库内的 Gradle Wrapper；当前 Gradle Daemon 工具链声明为 **JDK 25**，Java 源码兼容级别为 **11**。

| 工程配置 | 当前值 |
| --- | --- |
| 应用 ID / Namespace | `com.nova.tv` |
| 最低 SDK | `36` |
| 编译 / 目标 SDK | `37` |
| 应用版本 | `1.0`（工程配置值） |

> 当前最低 SDK 为 36。目标电视的 API Level 必须满足该配置；当贝等设备的最终适配范围仍待确认。`com.nova.tv` 是现有工程标识，文档品牌统一使用 Cineo。

### 2. 构建与安装

```bash
git clone https://github.com/benson-singapore/cineo-tv-os.git
cd cineo-tv-os
./gradlew :app:assembleDebug
```

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。

连接模拟器或启用 ADB 调试的电视后执行：

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nova.tv/.MainActivity
```

也可以直接在 Android Studio 中打开项目，完成 Gradle 同步并选择 TV 设备运行。

### 3. 配置内容

1. 在设置中添加兼容 MacCMS 的 API 视频源，或导入包含 `api_site` 的 JSON 配置。
2. 启用来源并设置默认来源，检查连通性后进入首页、分类或搜索。
3. 按需配置 TMDB Token，获取更丰富的海报、背景图与剧集信息。
4. 使用遥控器方向键移动焦点，确认键进入内容，返回键返回上一层。

内容可用性取决于所配置的服务端；请使用有权访问和播放的媒体资源。

## 技术与结构

| 层次 | 当前实现 |
| --- | --- |
| 界面 | Jetpack Compose、Compose for TV、深色主题与焦点组件 |
| 播放 | AndroidX Media3 / ExoPlayer，包含 HLS 支持 |
| 内容 | MacCMS 兼容 API、Repository 数据访问 |
| 元数据 | TMDB 信息增强与本地缓存 |
| 存储 | SQLiteOpenHelper、应用偏好与本机安全存储 |
| 构建 | Gradle Kotlin DSL、Version Catalog、单 `app` 模块 |

```text
cineo-tv-os/
├── app/src/main/
│   ├── java/com/nova/tv/
│   │   ├── MainActivity.kt             # 应用入口与页面导航
│   │   ├── VideoScreens.kt             # 浏览、设置、详情与播放界面
│   │   ├── MacCmsRepository.kt         # 视频源、内容与本地观看数据
│   │   ├── TmdbMetadataRepository.kt   # TMDB 元数据与缓存
│   │   ├── CineoDatabase.kt            # SQLite 数据库
│   │   └── ui/theme/                  # 主题、颜色与字体
│   └── res/                           # 启动图标与界面资源
├── assets/branding/cineo_mark.png      # 与 Flutter 版共用的 Cineo 标识
├── docs/                              # 开发计划、方案与决策记录
└── gradle/                            # Wrapper 与依赖版本目录
```

## 开发路线与文档

开发按「工程基础 → TV 导航与设计系统 → 核心浏览 → API 与本地数据 → 播放闭环 → 真机验收」推进。现有代码已覆盖其中多个部分，后续重点是完善体验、核对方案与实现差异，并完成设备验证。

| 文档 | 内容 |
| --- | --- |
| [开发文档索引](docs/README.md) | 当前状态、阅读顺序与文档维护约定 |
| [MVP 开发计划](docs/DEVELOPMENT_PLAN.md) | 阶段任务、依赖与验收标准；原始任务状态待逐项复核 |
| [设备互联整合方案](docs/DEVICE_CONNECT_DESIGN.md) | 统一绑定、手机输入、同步、安全与费用控制；待评审 |
| [多端同步设计草案](docs/SYNC_DESIGN.md) | 增量同步、数据模型、冲突规则与任务拆分；未实现 |
| [待确认事项与决策](docs/DECISIONS.md) | 设备、API、存储、身份与跨端同步的待决事项 |

---

<div align="center">
  <p><strong>Cineo · 手机与大屏，共享一份观影期待。</strong></p>
  <p>
    <a href="https://github.com/benson-singapore/cineo-flutter">Cineo Flutter</a> ·
    <a href="https://github.com/benson-singapore/cineo-tv-os">Cineo TV OS</a>
  </p>
</div>

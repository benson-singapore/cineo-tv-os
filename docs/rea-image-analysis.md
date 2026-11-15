# Orbit 图片兼容分析

分析日期：2026-11-11。目标：https://tju.oyvpqput.cc/ 。

## 已确认

页面公开的 `crypto-image-config` 提供 UTF-8 编码的 16 字节 key/IV。`crypto-image-worker.js` 使用 AES-128-CBC / NoPadding；尾部不满块时补零解密并裁切回原长度。先识别普通图片，只有密文才解密。浏览器通过 Blob/object URL 展示。

`crypto-image-rules.js` 将指定图片域名的 JPEG/PNG/BMP 路径映射到 `https://new2.drleao.cn`，保留原扩展名再追加 `.webp`，移除查询串。网页优先 WebP，失败回退原图。应用采用原图优先、WebP 回退以保持现有 URL 行为。

用户样例实际下载 85,680 字节，文件头 `4fe897a45e77d7d20e34ac4b`；按上述算法解密后为 `ffd8ffdb008400100b0c0e0c`，文件识别为 JPEG，720×1280。未执行目标网页脚本；这是静态脚本证据与独立样例解密验证。

## REA 证据

使用 REA 6.8.0 CLI 对下载的 5 个公开脚本执行 `analyze-javascript-application`，成功生成静态应用图。

Evidence ID：`ev_289eca57852c3d62a07d36ff1e416cb31484965593eb55ca6042e8a946cf80de`。

完整本地证据：`/tmp/rea-image-evidence.json`；下载脚本：`/tmp/rea-image-investigation/`（临时文件，非持久归档）。

## 实现及边界

`OrbitArtworkDecoder` 仅匹配列出的精确 CDN 域名，使用页面当前公开配置；`loadArtwork` 先尝试普通解码，再尝试对应域名的 AES 解密，失败后尝试 WebP。保留原有占位显示，限制单张下载为 16 MiB，并关闭连接。

密钥若轮换需更新适配；未实现网页的签名刷新接口。未在真实电视设备绑定 Hub 验证页面展示。现有工作区修改保留。

# 发布配置

## 版本和签名

`version.properties` 是应用与 bridge-core 的唯一版本来源。正式发布前确认 `versionName`、递增的 `versionCode` 和 tag `v<versionName>`。

首次正式发布前确定长期使用的签名密钥并离线备份。以前安装的调试包通常与正式签名不同；先导出配置，再确认迁移方式，不直接假设可覆盖安装。

GitHub `release` environment 中配置签名 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。密钥仅存 Secrets，源码不含签名材料。

## 每次发版

1. 修改版本文件与 `docs/release/CHANGELOG.md`，审核 README、发布标题和正文。
2. 运行 `Prepare signed release`，下载 `signed-release-assets` artifact；检查正式签名、APK 包名、版本、SHA-256 与 `update.json`。
3. 在对应 tag 创建正式 Release，上传生成的 APK、`update.json`、`SHA256SUMS`，使用已审核正文。
4. 在目标 ColorOS 设备上验证授权页、歌词与工具功能，以及下载、校验和 Root 覆盖安装流程。分别检查未授权、拒绝授权、撤销授权与授权成功的状态。
5. 验收自动提示、忽略版本、手动检查、GitHub 入口、下载取消、失败重试与更新后再次启动。

## GitHub 更新

更新仅使用本项目 GitHub Release。版本清单为 `https://github.com/XZOWO/ZHIColor/releases/latest/download/update.json`，不需要额外的更新服务配置。

`update.json` 中的 `downloadUrl` 固定为 `https://github.com/XZOWO/ZHIColor/releases/download/v<versionName>/ZHIColor-<versionName>.apk`，`sha256` 必填且必须与上传的 APK 一致。发布 tag、版本号和附件名必须匹配。

用户选择更新后，应用下载 APK，校验 SHA-256、包名、版本和正式签名，再通过 Root 执行覆盖安装；GitHub 入口用于查看版本详情。Root 和 LSPosed 授权均为进入应用的前置条件。

## 参考

- [GitHub release 事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)

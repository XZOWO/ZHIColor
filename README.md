# ZHIColor

ColorOS 原生锁屏音乐主要停留在基础播放信息，歌曲播放时锁屏内容较为单一。ZHIColor 在保留系统播放链路的基础上，提供锁屏歌词、歌词样式与音乐控制设置，让锁屏音乐信息更完整、更可定制。

## 使用前

- Android 12 及以上。具体功能取决于 ColorOS 版本和系统组件，尚未建立完整机型兼容列表。
- 安装兼容 libxposed API 102 的框架，在框架管理器中启用模块并按模块声明配置作用域。
- 必须授予 ZHIColor Root 权限和 LSPosed 权限；两项授权均通过后才能进入应用。
- 歌词功能需要 Lyricon 与对应播放器的数据源。

## 安装

1. 从 [Releases](https://github.com/XZOWO/ZHIColor/releases) 下载 APK。
2. 安装并启用模块，按框架要求重启相关作用域或设备。
3. 打开 ZHIColor，在授权页完成 Root 和 LSPosed 授权。
4. 两项授权通过后，进入应用配置歌词和工具选项。

请保留现有设置与重要数据。系统更新后，建议先确认模块兼容性。

## 更新

每次启动时自动检查一次更新，也可以在“关于”中选择“检查更新”。选择更新后，应用会下载并校验安装包，再通过 Root 覆盖安装；也可通过 GitHub 查看版本详情。“忽略本次”只忽略该版本的自动提醒，手动检查仍可查看。

## 构建

使用 JDK 21、Android SDK 37 和工程中的 Gradle Wrapper：

```sh
./gradlew :bridge-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

正式签名与发布流程见 [发布说明](docs/release/SETUP.md)。

## 反馈

反馈时请说明设备型号、系统版本、模块版本、播放器版本、复现步骤与预期结果。提交日志前，请检查并移除其中的账户信息、通知内容、歌曲信息和其他个人信息。

## 许可证

本项目以 [GNU GPL-3.0](LICENSE) 分发。部分代码与依赖保留各自许可证，参见 [NOTICE](NOTICE)、[第三方声明](THIRD_PARTY_NOTICES.md) 与 [bridge-core/LICENSE](bridge-core/LICENSE)。

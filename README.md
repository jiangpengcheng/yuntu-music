# 云途音乐

[![CI](https://github.com/jiangpengcheng/yuntu-music/actions/workflows/ci.yml/badge.svg)](https://github.com/jiangpengcheng/yuntu-music/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/jiangpengcheng/yuntu-music)](https://github.com/jiangpengcheng/yuntu-music/releases/latest)

适合 Android 4.4 车机的轻量网易云音乐客户端。使用原生 Java 界面与接口，**不需要自行部署 API 服务**，不依赖 Node.js、WebView 或第三方桌面协议。

- Android 4.4 / API 19 起，兼容 CS11；横屏大按钮、深色界面。
- 扫码登录；歌曲、歌手、专辑、歌单搜索；完整歌单分页加载。
- 私人 FM、自定义每批数量、音质选择、喜欢歌曲、收藏到歌单。
- 顺序 / 随机 / 单曲循环；无可用音源时自动下一首。
- 系统媒体键与方向盘控制、通知栏播放控制、仪表盘播放进度。
- 普通卡片、三行歌词大号卡片、两行交替悬浮歌词；按住拖动、单击回应用。
- 悬浮字号、颜色及下一句颜色设置；歌词窗口自适应文字宽度、居中、全透明。
- 可选开机自启、恢复上次队列并自动播放。
- CS11 蓝牙音乐测试功能：控制已连接手机的播放，显示收到的歌曲信息；可选将手机发送的蓝牙歌名作为歌词显示。

## 安装

从 [GitHub Releases](https://github.com/jiangpengcheng/yuntu-music/releases/latest) 下载 `yuntu-music-版本号.apk`。`SHA256SUMS.txt` 可校验下载文件。

包名为 `io.github.yuntumusic.direct`，显示名称为“云途音乐·直连”。正式版沿用 native 测试版的签名，可覆盖升级并保留登录、队列和设置；与旧网关版 `io.github.yuntumusic` 独立共存。

1. 打开“设置 → 扫码登录”，使用手机网易云音乐扫码确认，无需服务地址或 AUTH_TOKEN。
2. 播放歌曲后，可在“设置 → 播放设置”启用悬浮窗，并授予系统悬浮窗权限。
3. “悬浮文字样式”中分别设置字号、当前文字颜色。大号卡片和悬浮歌词还支持独立的“下一句歌词颜色”。点击“应用到设置”，再点击父页面“保存”。
4. 大小卡片共用可调背景透明度；悬浮歌词固定全透明，宽度随两行歌词变化，窗口外不拦截触摸。
5. 开机播放需同时启用开机自启和自动播放；部分系统还需允许后台运行。

音乐可用性和音质取决于网易云账号、曲目授权和设备解码能力。项目不提供会员解锁或离线曲库。蓝牙模式限有兼容原车服务的 CS11，使用方法和歌词限制见 [蓝牙音乐](docs/BLUETOOTH.md)。请避免同时运行多个播放器争夺音频焦点。

## 构建

需要 Python 3.10+、JDK 17、Android SDK Build Tools 33.0.2。无需 Gradle。建议设置 `ANDROID_SDK_ROOT` 和 `JAVA_HOME`。

```sh
sdkmanager 'build-tools;33.0.2' 'platform-tools'
python3 scripts/build_android.py
```

默认生成 `android/build/yuntu-music-版本号-debug.apk`，使用独立的本地调试签名，不能覆盖安装正式版。

构建严格使用 API 19 的 `android.jar`，优先读取 `ANDROID_19_JAR` 或 SDK 安装目录；缺少时从 Google 下载固定归档并验证 SHA256。固定版本的加密/二维码依赖从 Maven Central 下载并验证校验和。缓存不进入 Git。

正式签名构建见 [构建与发布](docs/RELEASING.md)。`AndroidManifest.xml` 是版本号的唯一来源；APK 文件名、应用关于信息、发布校验均读取它。

## 测试与 CI

```sh
python3 -m unittest discover -s tests -v
python3 scripts/build_android.py --tests
# 仅在隔离 Android 模拟器上运行，会清除该模拟器中的云途应用数据。
python3 scripts/run_android_tests.py --serial emulator-5554
```

主机测试覆盖依赖完整性、签名缺失拒绝、tag/版本匹配和 instrumentation 失败识别。设备测试覆盖接口数据契约、345 首歌单分页、密码学向量、网络重试边界、播放控制、开机策略、悬浮交互和字号颜色设置。测试音乐和业务接口由本机 fixture 提供；Node.js 22 仅用于开发测试，不进入 APK。

[CI](.github/workflows/ci.yml) 在 push / PR 时执行 API 19 编译及 Android 10 模拟器回归；[Release](.github/workflows/release.yml) 在 `v*` tag 上通过同一测试后签名发布。真实网易云/CDN 测试需要手动显式启用，不参与 CI。更多说明见 [测试文档](docs/TESTING.md)。

严格 API 19 编译与模拟器通过不能代替 OEM 车机实测。之前的 native 测试版已由用户在 CS11 验证无卡顿；不同固件的方控、仪表盘与后台权限仍应在实际设备确认。

## 代码结构

```text
android/src/             原生界面、播放服务、直连接口、悬浮窗
android/res/             资源与 CA 证书
android/test/            隔离测试、合成音频 fixture、协议测试向量
android/libs/            固定依赖与校验和（JAR 下载后缓存）
scripts/                 构建、测试及发布校验
.github/workflows/       CI 与 tag 发布
licenses/                第三方许可
```

接口通过最多 3 个后台工作线程执行，音频直接从 CDN 播放。使用 API 14 的 `RemoteControlClient` 和媒体键广播适配 API 19。账号会话保存在应用私有目录、禁止系统备份；退出登录清除会话。HTTPS 保留证书链和主机名校验，不忽略证书错误。

```sh
adb logcat -v threadtime -s YuntuDirect:I YuntuNetwork:I YuntuStartup:I YuntuBluetooth:I AndroidRuntime:E
```

日志不记录 Cookie、扫码 key、签名 URL 或完整响应正文。提交 issue 前请检查日志，不要附上账号凭证或私有签名材料。

## 许可

项目代码使用 [Apache-2.0](LICENSE)。移植协议代码及第三方依赖保留各自许可，见 [第三方声明](THIRD_PARTY_NOTICES.md)。本项目是独立客户端，与网易云音乐官方无隶属关系；相关商标和图标属于其权利人。

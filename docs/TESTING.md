# 测试

## 主机测试

```sh
python3 -m unittest discover -s tests -v
```

仅标准库，覆盖构建/发布边界：依赖校验失败、缺少正式签名拒绝构建、tag 与 Manifest 版本匹配、ADB 返回 0 但 instrumentation 实际崩溃时仍判定失败。

## 隔离模拟器回归

依赖 JDK 17、Android SDK、Node.js 22 和一个可使用 ADB 的模拟器。

```sh
python3 scripts/build_android.py --tests
python3 scripts/run_android_tests.py --serial emulator-5554
# 单独检查一组（也会删除模拟器内云途数据）：
python3 scripts/run_android_tests.py --suite FloatingLyricsTest
```

测试运行器先检查 `ro.kernel.qemu`，拒绝在实体设备运行。会卸载/重装测试 APK 和被测 APK，逐组清空应用数据，调整模拟器显示到 1024×600 / 160dpi，额外检查 1024×280，结束后恢复显示设置。不要用有真实账号的日用模拟器运行。

| 测试 | 内容 |
| --- | --- |
| NativeTest | 直连接口契约、扫码登录/session、345 首分页、FM、收藏/喜欢边界、歌词解析、协议测试向量 |
| NativeNetworkTest | 只读瞬态 TLS 重试、写请求不重放、证书失败不重试、响应大小上限 |
| MediaOutputTest | 播放/暂停/切歌/seek、媒体信息、悬浮卡片 |
| LargeCardTest | 等宽三行、当前句位置、歌词失败与重试、切换样式、独立位置 |
| FloatingLyricsTest | 两行交替、颜色、宽度自适应、空歌词不拦截、窗口外点击、拖动与返回应用 |
| OverlayTextTest | 字号颜色独立保存、下一句颜色、草稿取消/应用、恢复默认、大字号/低高度布局 |
| StartupTest | 开机和自动播放选择、等待网络、用户操作取消自动播放 |
| OverlayCompatTest | 生命周期和权限策略模拟、CS11 锁屏报告兼容分支 |

每个 fixture 测试独立启动 `android/test/fixture.mjs`，只监听 `127.0.0.1:3211`；模拟器通过 `10.0.2.2` 访问。测试用音频现场合成，不需要账号或真实歌曲。`vectors.json` 中的私钥是公开的合成密码学测试向量；Node crypto 会独立解码 Java 生成的 xeapi 请求，验证 X25519/GCM/AES 协议。

测试结果保存在 `android/build/test-results/`。失败的 instrumentation 会令脚本退出非零，并保存日志；只有明确的正向断言结果才判为通过。CI 使用 Android 10 x86_64 模拟器，编译仍严格使用 API 19。

## 真实上游测试（不属于 CI）

```sh
python3 scripts/run_android_tests.py --include-live
```

显式启用后才会运行 NativeLiveTest 和 NativePlaybackTest：匿名访问真实网易云接口/CDN，可能因地区、曲目授权或网络失败。不会登录真实账号。其结果不能作为稳定的 PR gate。

## 实车测试

Android API 19 编译证明代码未直接引用更新平台 API，不代表所有厂商固件都兼容。CS11 仍需人工检查扫码、长时间播放、导航共存、方控、仪表盘进度、悬浮窗与开机自启。模拟的锁屏条件和媒体广播不能替代真实硬件结果。

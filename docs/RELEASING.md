# 构建与发布

## 本地正式签名

保持现有包名和签名可以让 native 测试版直接升级。正式构建绝不自动创建新密钥或回退到调试签名。

```sh
# 私有文件只放在被 Git 忽略的目录，勿提交或上传为 Actions artifact。
# android/.signing/yuntu.jks
# android/.signing/password.txt
python3 scripts/build_android.py --release
python3 scripts/prepare_release.py --tag v1.0.0
```

也可用 `YUNTU_KEYSTORE_PATH`、`YUNTU_KEYSTORE_PASSWORD_FILE` 和 `YUNTU_KEY_ALIAS` 指定签名文件。默认 alias 为 `yuntu`。发布校验会核对维护者固定的证书 SHA256，不能用另一个签名发布冒充可覆盖升级的 APK。独立 fork 应自行维护包名、签名及校验指纹。

## GitHub Actions

仓库需要以下加密 Secrets：

- `ANDROID_KEYSTORE_BASE64`：现有 JKS 文件的 Base64。
- `ANDROID_KEYSTORE_PASSWORD`：签名密码，不含换行。

Secrets 仅在 tag 发布 job 中恢复为临时私有文件，结束时删除。PR / 普通 CI 生成独立 debug key，不接触正式签名。签名文件不应进入 Git、缓存或构建日志。建议维护者离线备份原签名，丢失后无法覆盖升级。

发布步骤：

1. 修改 `android/AndroidManifest.xml` 中的 `versionName`，并增加 `versionCode`。
2. 添加 `docs/releases/v版本号.md`，描述此次变化和验证范围。
3. 运行测试，合并到 `main` 并确认 CI 成功。
4. 创建指向该提交的 annotated tag 并推送，例如：

   ```sh
   git tag -a v1.0.0 -m 'Release v1.0.0'
   git push origin v1.0.0
   ```

5. Release workflow 再次执行 CI，生成正式 APK，核对版本、包名、API 19、DEX 035、v1 签名和证书指纹；上传 APK 与 SHA256 文件到 draft Release 后发布。
6. 确认 workflow 成功、公开 Release 存在，下载附件并运行 `shasum -a 256 -c SHA256SUMS.txt`。

已发布的 tag 不应移动，已发布的 APK 不应替换；修复应发布新版本。若 workflow 在创建 draft 后失败，先检查 draft 内容和失败日志，再由维护者处理，不自动覆盖已发布资产。

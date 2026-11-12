# MHub Voice Android

轻量 Android WebView 客户端。首次启动填写 MHub 服务器地址，之后直接进入播放器。APK 不包含后端、音乐平台账号或 AI 密钥。

## 构建

```sh
export ANDROID_HOME=/data/android-sdk
export ANDROID_SDK_ROOT=/data/android-sdk
cd android
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

当前 release 变体使用 Android 调试证书签名，适合直接侧载测试。上架应用商店前必须替换为私有签名证书，并把证书放在仓库外。

## 使用

1. 安装 APK。
2. 输入已部署的 MHub 地址，例如 `https://music.example.com`。
3. 允许麦克风权限。
4. 语音识别走 Android `SpeechRecognizer`，语音回复走系统 TTS。设备需要安装可用的语音识别服务和中文 TTS 引擎；没有语音服务时仍可在面板内输入文字。

清除应用数据可以重新配置服务器，也可以点击右侧悬浮的“设”按钮。

## 系统要求

- Android 8.0（API 26）或更高版本。
- Android System WebView 87 或更高版本。低于 87 时应用会提示更新，不会加载播放器界面。
- 必须能访问部署好的 MHub 服务器；APK 本身不包含音乐数据或 AI 密钥。

## CI 发布

`.github/workflows/android-release.yml` 在推送 `v*` 标签时构建 APK 并上传到 GitHub Release。仓库配置以下 Secrets 后使用私有证书签名，未配置时构建调试签名 APK：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

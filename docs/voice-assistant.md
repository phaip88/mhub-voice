# 语音助手

MHub 的语音助手用浏览器麦克风接收中文指令，直接操作当前播放器：搜索歌曲、播放、暂停、下一首、上一首、调节音量、跳转进度、随机/循环、加入队列、播放本地歌单、打开页面。回答通过浏览器语音合成朗读，也可以关闭“语音回复”只用文字。

入口：

- 播放器右侧的麦克风按钮，或 `Alt + V`。
- 移动端右下角的浮动麦克风按钮。
- 面板内始终可以输入文字，浏览器不支持语音识别时也能用。

浏览器不提供语音识别接口的 Android WebView，应接入服务端语音识别；浏览器自带的 Web Speech API 并非所有 APK 环境都可用。

## 本地指令

语音识别和指令解析在浏览器内完成，不需要配置模型。常用说法：

- “播放周杰伦的晴天”
- “搜索晴天”
- “下一首”“上一首”“暂停”“继续”“停止”
- “音量大一点”“音量调到百分之五十”“静音”
- “快进三十秒”“跳到一分三十秒”
- “随机播放”“单曲循环”“关闭循环”
- “播放我的歌单 通勤”
- “打开播放队列”“打开资料库”

搜索会优先使用当前平台；没有结果时依次尝试网易云、QQ 音乐和 Apple Music，并在找到后切换搜索平台。

## 可选 AI 对话

规则没有覆盖的说法（例如“今天适合听什么”）可以交给 OpenAI 兼容的对话模型。模型只在服务端调用，浏览器不会拿到密钥。

设置环境变量后重启 MHub：

```sh
MHUB_VOICE_LLM_BASE_URL=https://api.openai.com/v1
MHUB_VOICE_LLM_API_KEY=your-api-key
MHUB_VOICE_LLM_MODEL=gpt-4o-mini
```

可选：

```sh
# Azure 等使用 api-key 头的服务
MHUB_VOICE_LLM_AUTH_HEADER=api-key
MHUB_VOICE_LLM_AUTH_SCHEME=
```

DeepSeek 使用官方模型名 `deepseek-flash`，不要填写版本号：

```sh
MHUB_VOICE_LLM_BASE_URL=https://api.deepseek.com
MHUB_VOICE_LLM_API_KEY=your-api-key
MHUB_VOICE_LLM_MODEL=deepseek-flash
MHUB_VOICE_LLM_THINKING=disabled
```

语音控制需要低延迟，DeepSeek 模型默认开启思考模式，服务端会对 `deepseek-` 模型自动关闭；需要重新开启时设置 `MHUB_VOICE_LLM_THINKING=enabled`。

`MHUB_VOICE_LLM_BASE_URL` 可以写到 `/v1`，也可以直接写到 `/chat/completions`。没有配置时，面板显示“本地指令”，语音控制仍然可用。

## 接口

- `GET /api/v1/voice/status`：返回 `{ "configured": true|false, "model": "..." }`，不包含密钥。
- `POST /api/v1/voice/chat`：转发一次非流式 chat completion 请求。请求体为 `{ messages, tools }`，工具由前端定义并在本地执行。

语音对话计入现有的 `[upstream_limit]` 客户端配额（`voice` 类型），避免一个浏览器耗尽共享的模型额度。

## 验证

```sh
npm --prefix frontend test
npm --prefix frontend run build
cargo fmt --check
cargo check --locked
```

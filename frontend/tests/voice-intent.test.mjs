import test from "node:test";
import assert from "node:assert/strict";
import { parseDurationMs, parseNumber, parseVoiceIntent } from "../src/services/voice/intent.js";

const results = [{ title: "晴天" }, { title: "稻香" }, { title: "七里香" }];

test("Chinese and Arabic numbers", () => {
  assert.equal(parseNumber("五十"), 50);
  assert.equal(parseNumber("十五"), 15);
  assert.equal(parseNumber("二十五"), 25);
  assert.equal(parseNumber("2.5"), 2.5);
});

test("durations", () => {
  assert.equal(parseDurationMs("1分30秒"), 90_000);
  assert.equal(parseDurationMs("九十秒"), 90_000);
  assert.equal(parseDurationMs("1:30"), 90_000);
});

test("play query extracts artist and title", () => {
  const intent = parseVoiceIntent("播放周杰伦的晴天");
  assert.equal(intent.kind, "play-query");
  assert.equal(intent.query, "周杰伦 晴天");
});

test("transport commands win over search", () => {
  assert.equal(parseVoiceIntent("播放下一首").kind, "next");
  assert.equal(parseVoiceIntent("上一首").kind, "prev");
  assert.equal(parseVoiceIntent("暂停").kind, "pause");
  assert.equal(parseVoiceIntent("继续播放").kind, "resume");
  assert.equal(parseVoiceIntent("停止").kind, "stop");
});

test("volume commands", () => {
  assert.deepEqual(parseVoiceIntent("音量大一点"), { kind: "volume", mode: "delta", value: 0.1 });
  assert.deepEqual(parseVoiceIntent("音量调到百分之五十"), { kind: "volume", mode: "set", value: 0.5 });
  assert.deepEqual(parseVoiceIntent("音量50"), { kind: "volume", mode: "set", value: 0.5 });
  assert.equal(parseVoiceIntent("静音").mode, "mute");
  assert.equal(parseVoiceIntent("取消静音").mode, "unmute");
});

test("seek commands", () => {
  assert.deepEqual(parseVoiceIntent("快进30秒"), { kind: "seek", mode: "delta", ms: 30_000 });
  assert.deepEqual(parseVoiceIntent("后退10秒"), { kind: "seek", mode: "delta", ms: -10_000 });
  assert.deepEqual(parseVoiceIntent("快进到1分30秒"), { kind: "seek", mode: "absolute", ms: 90_000 });
  assert.deepEqual(parseVoiceIntent("从头播放"), { kind: "seek", mode: "absolute", ms: 0 });
});

test("mode commands", () => {
  assert.deepEqual(parseVoiceIntent("随机播放"), { kind: "shuffle", enabled: true });
  assert.deepEqual(parseVoiceIntent("顺序播放"), { kind: "shuffle", enabled: false });
  assert.deepEqual(parseVoiceIntent("单曲循环"), { kind: "repeat", mode: "one" });
  assert.deepEqual(parseVoiceIntent("关闭循环"), { kind: "repeat", mode: "off" });
});

test("search and platform selection", () => {
  const intent = parseVoiceIntent("用网易云搜索晴天");
  assert.equal(intent.kind, "search");
  assert.equal(intent.query, "晴天");
  assert.equal(intent.platform, "netease");
});

test("result selection uses the previous result list", () => {
  const intent = parseVoiceIntent("播放第二个", { lastResults: results });
  assert.deepEqual(intent, { kind: "select-result", index: 1 });
  assert.equal(parseVoiceIntent("最后一个", { lastResults: results }).index, -1);
});

test("queue commands", () => {
  const add = parseVoiceIntent("把第二首加入队列", { lastResults: results });
  assert.deepEqual(add, { kind: "queue-add", mode: "end", index: 1 });
  const next = parseVoiceIntent("把这首歌设为下一首播放", { lastResults: results });
  assert.equal(next.kind, "queue-add");
  assert.equal(next.mode, "next");
});

test("playlists, likes and views", () => {
  assert.equal(parseVoiceIntent("播放我的歌单 通勤").name, "通勤");
  assert.equal(parseVoiceIntent("收藏当前歌曲").kind, "like");
  assert.equal(parseVoiceIntent("打开播放队列").view, "queue");
  assert.equal(parseVoiceIntent("打开资料库").view, "library");
});

test("conversation fallback", () => {
  assert.equal(parseVoiceIntent("你好").kind, "greeting");
  assert.equal(parseVoiceIntent("你能做什么").kind, "help");
  assert.equal(parseVoiceIntent("今天适合听什么").kind, "unknown");
  assert.equal(parseVoiceIntent("现在播放什么").kind, "now-playing");
});

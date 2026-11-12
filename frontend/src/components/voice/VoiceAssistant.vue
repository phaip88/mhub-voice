<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { LoaderCircle, Mic, MicOff, Play, Send, Sparkles, X } from "lucide-vue-next";
import { searchTracks } from "../../apis/music";
import * as player from "../../core/player";
import { useMediaStore } from "../../stores/media";
import { usePlaylistStore } from "../../stores/playlists";
import { trackIdentity, useQueueStore } from "../../stores/queue";
import { useStatusStore } from "../../stores/status";
import { formatTime } from "../../utils/format";
import { trackFromSearch } from "../../utils/track";
import { parseVoiceIntent } from "../../services/voice/intent";
import { chatWithTools, getVoiceStatus } from "../../services/voice/llm";
import {
  cancelSpeech,
  createRecognizer,
  speakText,
  speechRecognitionSupported,
} from "../../services/voice/speech";

const props = defineProps({
  prepareTrack: {
    type: Function,
    default: (track) => track,
  },
});

const status = useStatusStore();
const media = useMediaStore();
const queue = useQueueStore();
const playlists = usePlaylistStore();

const PREF_KEY = "mhub-voice-preferences-v1";
const MAX_MESSAGES = 10;
const PLATFORM_LABELS = { applemusic: "Apple Music", netease: "网易云音乐", tencent: "QQ 音乐" };

function loadPrefs() {
  try {
    return JSON.parse(localStorage.getItem(PREF_KEY) || "{}");
  } catch {
    return {};
  }
}

const prefs = loadPrefs();
const open = ref(false);
const micSupported = ref(false);
const listening = ref(false);
const busy = ref(false);
const speaking = ref(false);
const interim = ref("");
const inputText = ref("");
const voiceReply = ref(prefs.voiceReply !== false);
const continuous = ref(prefs.continuous === true);
const llmConfigured = ref(false);
const llmChecked = ref(false);
const messages = ref([]);
const lastResults = ref([]);
const lastResultIndex = ref(-1);
const panelRef = ref(null);
const messagesRef = ref(null);
const inputRef = ref(null);
const preMuteVolume = ref(0.86);

let recognizer = null;
let searchAbort = null;
let chatAbort = null;
let speechToken = 0;
let duckedVolume = null;
let messageId = 0;
let unmounted = false;

function addMessage(role, text, { error = false, results = [] } = {}) {
  const value = String(text || "").trim();
  if (!value && !results.length) return;
  messages.value.push({ id: ++messageId, role, text: value, error, results });
  if (messages.value.length > MAX_MESSAGES) messages.value = messages.value.slice(-MAX_MESSAGES);
  void nextTick(() => {
    if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  });
}

function platformLabel(platform) {
  return PLATFORM_LABELS[platform] || platform || "当前平台";
}

function friendlyError(error) {
  if (!error || error.name === "AbortError") return "";
  if (error.status === 503) return "AI 对话还没有在服务端配置，本地指令可以继续用。";
  if (/fetch|network|load failed/i.test(error.message || "")) return "网络请求失败，检查连接后重试。";
  return error.message || "操作失败，请重试。";
}

function restoreVolume() {
  if (duckedVolume === null) return;
  player.setVolume(duckedVolume);
  duckedVolume = null;
}

function cancelSpeechAndRestore() {
  speechToken += 1;
  cancelSpeech();
  speaking.value = false;
  restoreVolume();
}

function maybeContinueListening() {
  if (!continuous.value || !open.value || busy.value || listening.value || !micSupported.value) return;
  window.setTimeout(() => {
    if (!unmounted && continuous.value && open.value && !busy.value && !listening.value) startListening();
  }, 320);
}

function say(text) {
  const value = String(text || "").trim();
  if (!value || !voiceReply.value) {
    maybeContinueListening();
    return;
  }
  speechToken += 1;
  const token = speechToken;
  restoreVolume();
  const base = Number(status.volume) || 0;
  if (base > 0.02) {
    duckedVolume = base;
    player.setVolume(base * 0.15);
  }
  speaking.value = true;
  const started = speakText(value, {
    onEnd: () => {
      if (token !== speechToken || unmounted) return;
      speaking.value = false;
      restoreVolume();
      maybeContinueListening();
    },
  });
  if (!started) {
    speaking.value = false;
    restoreVolume();
    maybeContinueListening();
  }
}

function respond(text, options = {}) {
  addMessage("assistant", text, options);
  say(text);
}

function startListening() {
  if (busy.value) return;
  if (!micSupported.value) {
    respond("当前浏览器不支持语音识别，可以直接输入文字。");
    void nextTick(() => inputRef.value?.focus());
    return;
  }
  cancelSpeechAndRestore();
  recognizer?.abort?.();
  interim.value = "";
  listening.value = true;
  recognizer = createRecognizer({
    lang: "zh-CN",
    onInterim: (text) => { interim.value = text; },
    onFinal: (text) => {
      interim.value = "";
      listening.value = false;
      if (text) void handleUtterance(text, "voice");
    },
    onError: (message) => {
      listening.value = false;
      interim.value = "";
      addMessage("assistant", message, { error: true });
    },
    onEnd: () => {
      listening.value = false;
      interim.value = "";
    },
  });
  recognizer.start();
}

function stopListening() {
  recognizer?.abort?.();
  recognizer = null;
  listening.value = false;
  interim.value = "";
}

function toggleListening() {
  if (listening.value) stopListening();
  else startListening();
}

function openPanel({ listen = true } = {}) {
  open.value = true;
  if (listen && micSupported.value) startListening();
  else if (!micSupported.value) void nextTick(() => inputRef.value?.focus());
}

function closePanel() {
  open.value = false;
  stopListening();
  cancelSpeechAndRestore();
  chatAbort?.abort();
  chatAbort = null;
  searchAbort?.abort();
  searchAbort = null;
}

function handleFab() {
  if (!open.value) openPanel({ listen: true });
  else toggleListening();
}

function sendText() {
  const text = inputText.value.trim();
  if (!text || busy.value) return;
  inputText.value = "";
  void handleUtterance(text, "text");
}

function useExample(text) {
  if (busy.value) return;
  inputText.value = text;
  sendText();
}

function cancelSearch() {
  searchAbort?.abort();
  searchAbort = null;
}

async function searchFor(query, platform) {
  cancelSearch();
  const controller = new AbortController();
  searchAbort = controller;
  const preferred = platform || status.searchPlatform || "applemusic";
  const order = [preferred, ...["netease", "tencent", "applemusic"].filter((item) => item !== preferred)];
  let lastError = null;

  for (const candidate of order) {
    try {
      const items = await searchTracks({ platform: candidate, q: query, limit: 6, signal: controller.signal });
      if (Array.isArray(items) && items.length) {
        if (candidate !== status.searchPlatform) {
          status.searchPlatform = candidate;
          status.persist();
        }
        return {
          platform: candidate,
          tracks: items.slice(0, 5).map((item) => props.prepareTrack(trackFromSearch(item, status.quality))),
        };
      }
    } catch (error) {
      if (error?.name === "AbortError") throw error;
      lastError = error;
    }
  }
  if (lastError) throw lastError;
  return { platform: preferred, tracks: [] };
}

function playbackError(error) {
  const message = friendlyError(error);
  if (!message) return;
  addMessage("assistant", message, { error: true });
  status.toast(message);
}

function playInBackground(track) {
  void player.playNow(track).catch(playbackError);
}

async function doSearch(query, platform) {
  if (!query) return { text: "要搜什么歌？", error: true };
  const found = await searchFor(query, platform);
  if (!found.tracks.length) {
    return { text: `没有找到“${query}”，换个歌名或歌手试试。`, error: true };
  }
  lastResults.value = found.tracks;
  lastResultIndex.value = -1;
  const first = found.tracks[0];
  const artist = first.artist ? `，${first.artist}` : "";
  return {
    text: `在${platformLabel(found.platform)}找到 ${found.tracks.length} 首。第一首是《${first.title}》${artist}。说“第一首”或直接点选。`,
    results: found.tracks,
  };
}

async function doPlayQuery(query, platform) {
  if (!query) return { text: "想听什么？说个歌名或歌手。", error: true };
  const found = await searchFor(query, platform);
  if (!found.tracks.length) {
    return { text: `没有找到“${query}”，换个歌名或歌手试试。`, error: true };
  }
  lastResults.value = found.tracks;
  lastResultIndex.value = 0;
  const track = found.tracks[0];
  playInBackground(track);
  const artist = track.artist ? `，${track.artist}` : "";
  return {
    text: `正在播放《${track.title}》${artist}。`,
    results: found.tracks,
  };
}

function doSelectResult(index) {
  const tracks = lastResults.value;
  const resolved = index === -1 ? tracks.length - 1 : index;
  const track = tracks[resolved];
  if (!track) return { text: "没有这个结果。先说歌名让我搜一下。", error: true };
  lastResultIndex.value = resolved;
  playInBackground(track);
  return { text: `播放第 ${resolved + 1} 首，《${track.title}》。` };
}

function doAnother() {
  if (lastResultIndex.value + 1 < lastResults.value.length) return doSelectResult(lastResultIndex.value + 1);
  if (queue.queueLength) return doNext();
  return { text: "没有别的结果了，说个歌名重新搜一下。", error: true };
}

function doNext() {
  if (!queue.queueLength) return { text: "队列是空的，先说想听什么。", error: true };
  void player.nextTrack(true).catch(playbackError);
  const track = status.currentTrack;
  return { text: track ? `下一首，《${track.title}》。` : "已切到下一首。" };
}

function doPrev() {
  if (!queue.queueLength) return { text: "队列是空的，先说想听什么。", error: true };
  void player.prevTrack().catch(playbackError);
  const track = status.currentTrack;
  return { text: track ? `上一首，《${track.title}》。` : "已回到上一首。" };
}

function doPause() {
  if (!status.isPlaying) return { text: "现在没有在播放。" };
  player.pause();
  return { text: "已暂停。" };
}

function doResume() {
  if (!media.track && !status.currentTrack) return { text: "先说要听什么。", error: true };
  if (status.isPlaying) return { text: "正在播放。" };
  void player.play().catch(playbackError);
  return { text: "继续播放。" };
}

function doStop() {
  player.stop();
  return { text: "已停止，播放队列还在。" };
}

function doVolume(intent) {
  const current = Number(status.volume) || 0;
  if (intent.mode === "mute") {
    if (current > 0.01) preMuteVolume.value = current;
    player.setVolume(0);
    return { text: "已静音。" };
  }
  if (intent.mode === "unmute") {
    const target = preMuteVolume.value > 0.01 ? preMuteVolume.value : 0.5;
    player.setVolume(target);
    return { text: `音量恢复到 ${Math.round(target * 100)}%。` };
  }
  if (intent.mode === "delta") {
    const target = Math.max(0, Math.min(1, current + Number(intent.value || 0)));
    player.setVolume(target);
    if (target > 0.01) preMuteVolume.value = target;
    return { text: `音量 ${Math.round(target * 100)}%。` };
  }
  const target = Math.max(0, Math.min(1, Number(intent.value)));
  if (!Number.isFinite(target)) return { text: "没听清音量要调到多少。", error: true };
  player.setVolume(target);
  if (target > 0.01) preMuteVolume.value = target;
  return { text: target === 0 ? "已静音。" : `音量调到 ${Math.round(target * 100)}%。` };
}

function doSeek(intent) {
  const duration = status.duration || media.track?.duration || 0;
  if (!duration) return { text: "播放进度还没准备好，稍后再试。", error: true };
  const target = intent.mode === "delta" ? status.position + intent.ms : intent.ms;
  const clamped = Math.max(0, Math.min(duration, target));
  player.seek(clamped);
  return { text: `已跳到 ${formatTime(clamped)}。` };
}

function doShuffle(intent) {
  player.setPlaybackMode(intent.enabled ? "shuffle" : "sequence");
  return { text: intent.enabled ? "随机播放已开启。" : "已改为顺序播放。" };
}

function doRepeat(intent) {
  player.setPlaybackMode(intent.mode);
  const labels = { one: "单曲循环", list: "列表循环", off: "关闭循环" };
  return { text: `${labels[intent.mode] || "循环"}已设置。` };
}

function doAutoplay(intent) {
  player.setAutoplayEnabled(intent.enabled);
  return { text: intent.enabled ? "自动续播已开启。" : "自动续播已关闭。" };
}

function doQueue(intent) {
  let track = status.currentTrack;
  if (Number.isInteger(intent.index) && intent.index >= 0) track = lastResults.value[intent.index];
  if (!track) return { text: "没有找到要加入队列的歌曲。", error: true };
  if (intent.mode === "next") {
    player.queueNextMany([track]);
    return { text: `《${track.title}》会下一首播放。` };
  }
  player.insertManyToQueue([track], queue.queueLength);
  return { text: `《${track.title}》已加入队列末尾。` };
}

function doPlayPlaylist(intent) {
  const name = String(intent.name || "").trim();
  const normalized = name.replace(/\s+/gu, "").toLowerCase();
  const matches = playlists.items.filter((item) => {
    if (!normalized) return true;
    return String(item.name || "").replace(/\s+/gu, "").toLowerCase().includes(normalized);
  });
  if (!matches.length) {
    const names = playlists.items.slice(0, 4).map((item) => item.name).join("、");
    return { text: names ? `没找到这个歌单。现有歌单：${names}。` : "还没有歌单。", error: true };
  }
  if (!name && matches.length > 1) {
    const names = matches.slice(0, 4).map((item) => item.name).join("、");
    return { text: `要播放哪个歌单？${names}。` };
  }
  const playlist = matches[0];
  if (!playlist.tracks?.length) return { text: `歌单《${playlist.name}》还是空的。`, error: true };
  void player.playFrom(playlist.tracks.map((track) => props.prepareTrack(track)), 0, playlist).catch(playbackError);
  return { text: `开始播放歌单《${playlist.name}》，共 ${playlist.tracks.length} 首。` };
}

function doLike() {
  const track = media.track || status.currentTrack;
  if (!track) return { text: "现在没有播放的歌曲。", error: true };
  playlists.addTaste("liked", trackIdentity(track));
  return { text: `已喜欢《${track.title}》。` };
}

function doNowPlaying(intent) {
  const track = media.track || status.currentTrack;
  if (!track) return { text: "现在没有播放。", error: true };
  const parts = [`《${track.title}》`];
  if (track.artist) parts.push(track.artist);
  if (intent.detail === "time") {
    parts.push(`${formatTime(status.position)} / ${formatTime(status.duration || track.duration || 0)}`);
  }
  if (!status.isPlaying) parts.push("已暂停");
  return { text: `${parts.join("，")}。` };
}

function doOpen(intent) {
  if (intent.view === "player") {
    media.expanded = true;
    return { text: "已打开全屏播放器。" };
  }
  const views = {
    queue: { view: "queue", text: "已打开播放队列。" },
    library: { view: "playlists", text: "已打开资料库。" },
    search: { view: "search", text: "已打开搜索。" },
    cache: { view: "cache", text: "已打开本地缓存。" },
    discover: { view: "discover", text: "已回到主页。" },
  };
  const target = views[intent.view];
  if (!target) return { text: "没有这个页面。", error: true };
  status.activeView = target.view;
  status.activePlaylistId = null;
  status.queueOpen = false;
  status.persist();
  return { text: target.text };
}

function doRandom() {
  if (!queue.queueLength) return { text: "队列是空的，先搜一首歌。", error: true };
  const index = Math.floor(Math.random() * queue.queueLength);
  const track = queue.getTrack(index);
  void player.playAtIndex(index).catch(playbackError);
  return { text: track ? `随机播放《${track.title}》。` : "随机播放。" };
}

function doHelp() {
  return { text: "可以说：播放周杰伦的晴天、下一首、暂停、音量大一点、播放我的歌单。" };
}

function doGreeting() {
  return { text: "我在，想听什么？" };
}

async function executeIntent(intent) {
  switch (intent.kind) {
    case "play-query": return doPlayQuery(intent.query, intent.platform);
    case "search": return doSearch(intent.query, intent.platform);
    case "select-result": return doSelectResult(intent.index);
    case "another": return doAnother();
    case "next": return doNext();
    case "prev": return doPrev();
    case "pause": return doPause();
    case "resume": return doResume();
    case "stop": return doStop();
    case "volume": return doVolume(intent);
    case "seek": return doSeek(intent);
    case "shuffle": return doShuffle(intent);
    case "repeat": return doRepeat(intent);
    case "autoplay": return doAutoplay(intent);
    case "queue-add": return doQueue(intent);
    case "play-playlist": return doPlayPlaylist(intent);
    case "like": return doLike();
    case "now-playing": return doNowPlaying(intent);
    case "open": return doOpen(intent);
    case "random": return doRandom();
    case "help": return doHelp();
    case "greeting": return doGreeting();
    default: return null;
  }
}

const TOOLS = [
  {
    type: "function",
    function: {
      name: "play_music",
      description: "搜索并播放一首歌或某位歌手的音乐。",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "歌名、歌手或歌名加歌手" },
          platform: { type: "string", enum: ["netease", "tencent", "applemusic"] },
        },
        required: ["query"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "search_music",
      description: "只搜索歌曲，列出结果，不立即播放。",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string" },
          platform: { type: "string", enum: ["netease", "tencent", "applemusic"] },
        },
        required: ["query"],
      },
    },
  },
  { type: "function", function: { name: "play_next", description: "播放下一首。", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "play_previous", description: "播放上一首。", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "pause_playback", description: "暂停播放。", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "resume_playback", description: "继续播放。", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "stop_playback", description: "停止播放但保留队列。", parameters: { type: "object", properties: {} } } },
  {
    type: "function",
    function: {
      name: "set_volume",
      description: "把音量调到指定百分比，0 到 100。",
      parameters: {
        type: "object",
        properties: { percent: { type: "number", minimum: 0, maximum: 100 } },
        required: ["percent"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "seek",
      description: "跳转播放进度。relative 为 true 时按秒前后移动，否则跳到指定秒数。",
      parameters: {
        type: "object",
        properties: {
          seconds: { type: "number" },
          relative: { type: "boolean" },
        },
        required: ["seconds"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "set_shuffle",
      description: "开启或关闭随机播放。",
      parameters: {
        type: "object",
        properties: { enabled: { type: "boolean" } },
        required: ["enabled"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "set_repeat",
      description: "设置循环模式：off 关闭，list 列表循环，one 单曲循环。",
      parameters: {
        type: "object",
        properties: { mode: { type: "string", enum: ["off", "list", "one"] } },
        required: ["mode"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "set_autoplay",
      description: "开启或关闭队列结束后的自动续播。",
      parameters: {
        type: "object",
        properties: { enabled: { type: "boolean" } },
        required: ["enabled"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "queue_music",
      description: "把当前歌曲或搜索到的歌曲加入队列。position 为 next 时下一首播放，否则放末尾。",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string" },
          platform: { type: "string", enum: ["netease", "tencent", "applemusic"] },
          position: { type: "string", enum: ["next", "end"] },
        },
      },
    },
  },
  {
    type: "function",
    function: {
      name: "play_playlist",
      description: "播放资料库里的本地歌单。",
      parameters: {
        type: "object",
        properties: { name: { type: "string" } },
        required: ["name"],
      },
    },
  },
  { type: "function", function: { name: "like_current", description: "喜欢当前播放的歌曲。", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "get_now_playing", description: "查询当前播放的歌曲和进度。", parameters: { type: "object", properties: {} } } },
  {
    type: "function",
    function: {
      name: "open_view",
      description: "打开页面。",
      parameters: {
        type: "object",
        properties: { view: { type: "string", enum: ["queue", "library", "search", "cache", "discover"] } },
        required: ["view"],
      },
    },
  },
];

function systemPrompt() {
  const track = media.track || status.currentTrack;
  const current = track
    ? `《${track.title || "未知歌曲"}》${track.artist ? `，${track.artist}` : ""}`
    : "没有播放";
  const names = playlists.items.slice(0, 8).map((item) => item.name).join("、") || "无";
  return [
    "你是 MHub 的音乐语音助手。用户用中文说话，你的回答会被浏览器直接朗读。",
    "回答保持 1 到 2 句，口语化，不要 Markdown，不要列长清单。",
    "需要操作播放器时必须调用工具，不要声称已经执行而未调用工具。",
    "如果用户只是聊天，可以简短回答，并顺势推荐一首歌或一个搜索词。",
    `当前状态：${status.isPlaying ? "正在播放" : "未播放"} ${current}；音量 ${Math.round((status.volume || 0) * 100)}%；队列 ${queue.queueLength} 首；搜索平台 ${platformLabel(status.searchPlatform)}；本地歌单：${names}。`,
  ].join("\n");
}

async function executeTool(name, args = {}) {
  try {
    switch (name) {
      case "play_music": return await doPlayQuery(args.query, args.platform);
      case "search_music": return await doSearch(args.query, args.platform);
      case "play_next": return doNext();
      case "play_previous": return doPrev();
      case "pause_playback": return doPause();
      case "resume_playback": return doResume();
      case "stop_playback": return doStop();
      case "set_volume": return doVolume({ mode: "set", value: Math.max(0, Math.min(1, Number(args.percent) / 100)) });
      case "seek": return doSeek({ mode: args.relative ? "delta" : "absolute", ms: Number(args.seconds) * 1000 });
      case "set_shuffle": return doShuffle({ enabled: Boolean(args.enabled) });
      case "set_repeat": return doRepeat({ mode: ["off", "list", "one"].includes(args.mode) ? args.mode : "off" });
      case "set_autoplay": return doAutoplay({ enabled: Boolean(args.enabled) });
      case "play_playlist": return doPlayPlaylist({ name: args.name });
      case "like_current": return doLike();
      case "get_now_playing": return doNowPlaying({ detail: "time" });
      case "open_view": return doOpen({ view: args.view });
      case "queue_music": {
        let track = status.currentTrack;
        if (args.query) {
          const found = await searchFor(args.query, args.platform);
          track = found.tracks[0] || null;
        }
        if (!track) return { ok: false, error: "没有找到要加入队列的歌曲。" };
        if (args.position === "next") player.queueNextMany([track]);
        else player.insertManyToQueue([track], queue.queueLength);
        return { ok: true, message: `《${track.title}》已加入队列。` };
      }
      default: return { ok: false, error: "不支持这个操作。" };
    }
  } catch (error) {
    return { ok: false, error: friendlyError(error) || "操作失败。" };
  }
}

async function runLlmConversation() {
  chatAbort?.abort();
  const controller = new AbortController();
  chatAbort = controller;
  const history = messages.value
    .filter((message) => !message.error && (message.role === "user" || message.role === "assistant"))
    .slice(-8)
    .map((message) => ({ role: message.role, content: message.text }));
  const chatMessages = [{ role: "system", content: systemPrompt() }, ...history];
  let lastToolText = "";

  for (let round = 0; round < 3; round += 1) {
    const data = await chatWithTools({ messages: chatMessages, tools: TOOLS, signal: controller.signal });
    const message = data?.choices?.[0]?.message;
    if (!message) throw new Error("语音模型返回为空");
    chatMessages.push(message);
    if (Array.isArray(message.tool_calls) && message.tool_calls.length) {
      for (const call of message.tool_calls) {
        let args = {};
        try {
          const raw = call?.function?.arguments;
          args = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
        } catch { args = {}; }
        const result = await executeTool(call?.function?.name, args);
        if (result?.text) lastToolText = result.text;
        if (result?.message) lastToolText = result.message;
        chatMessages.push({
          role: "tool",
          tool_call_id: call.id,
          content: JSON.stringify({ ok: !result?.error, message: result?.text || result?.message, error: result?.error }),
        });
      }
      continue;
    }
    const content = String(message.content || "").trim();
    if (content) return content;
  }
  return lastToolText || "我暂时没完成这个操作，换个说法再试一次。";
}

async function handleUtterance(text) {
  const clean = String(text || "").trim();
  if (!clean || busy.value) return;
  addMessage("user", clean);
  busy.value = true;
  try {
    const intent = parseVoiceIntent(clean, {
      hasTrack: Boolean(media.track || status.currentTrack),
      isPlaying: status.isPlaying,
      volume: status.volume,
      position: status.position,
      duration: status.duration,
      lastResults: lastResults.value,
      playlists: playlists.items,
      queueLength: queue.queueLength,
    });

    if (intent.kind === "unknown") {
      if (llmConfigured.value) {
        try {
          respond(await runLlmConversation());
        } catch (error) {
          if (error?.status === 503) {
            llmConfigured.value = false;
            respond("AI 对话还没有配置，本地指令可以继续用。想听什么？");
          } else if (error?.name !== "AbortError") {
            throw error;
          }
        }
      } else {
        respond(doHelp().text);
      }
      return;
    }

    const result = await executeIntent(intent);
    if (result?.text) respond(result.text, { error: Boolean(result.error), results: result.results || [] });
  } catch (error) {
    if (error?.name === "AbortError") return;
    const message = friendlyError(error) || "操作失败，请重试。";
    addMessage("assistant", message, { error: true });
    status.toast(message);
  } finally {
    busy.value = false;
    if (!voiceReply.value) maybeContinueListening();
  }
}

function playResultFromMessage(track, index) {
  lastResults.value = messages.value.findLast?.((message) => message.results?.length)?.results || lastResults.value;
  lastResultIndex.value = index;
  playInBackground(track);
  status.toast(`正在播放《${track.title}》`);
}

function handleWindowKeydown(event) {
  if (event.altKey && !event.ctrlKey && !event.metaKey && String(event.key).toLowerCase() === "v") {
    event.preventDefault();
    handleFab();
  }
  if (event.key === "Escape" && open.value) {
    event.preventDefault();
    closePanel();
  }
}

async function refreshLlmStatus() {
  try {
    const payload = await getVoiceStatus();
    llmConfigured.value = Boolean(payload.configured);
  } catch {
    llmConfigured.value = false;
  } finally {
    llmChecked.value = true;
  }
}

watch([continuous, voiceReply], () => {
  try {
    localStorage.setItem(PREF_KEY, JSON.stringify({ continuous: continuous.value, voiceReply: voiceReply.value }));
  } catch { /* Storage may be unavailable. */ }
});

watch(open, (value) => {
  if (!value) {
    stopListening();
    cancelSpeechAndRestore();
  } else {
    void nextTick(() => {
      if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
    });
  }
});

watch(() => media.expanded, (expanded) => {
  if (expanded) closePanel();
});

watch([() => messages.value.length, interim, busy], () => {
  void nextTick(() => {
    if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  });
});

onMounted(() => {
  micSupported.value = speechRecognitionSupported();
  addMessage("assistant", "我在。想听什么？也可以说“下一首”或“音量大一点”。");
  void refreshLlmStatus();
  window.addEventListener("keydown", handleWindowKeydown);
  window.addEventListener("mhub:voice-toggle", handleFab);
});

onBeforeUnmount(() => {
  unmounted = true;
  window.removeEventListener("keydown", handleWindowKeydown);
  window.removeEventListener("mhub:voice-toggle", handleFab);
  stopListening();
  cancelSpeechAndRestore();
  chatAbort?.abort();
  searchAbort?.abort();
});

const showExamples = computed(() => messages.value.length <= 1 && !busy.value);
const micLabel = computed(() => {
  if (!micSupported.value) return "浏览器不支持语音识别";
  if (listening.value) return "正在听，点一下停止";
  return "点一下说话";
});
</script>

<template>
  <Teleport to="body">
    <button
      v-if="!media.expanded"
      class="voice-fab"
      :class="{ 'is-listening': listening, 'is-open': open }"
      type="button"
      :aria-label="open ? '语音助手' : '打开语音助手'"
      :aria-expanded="open"
      title="语音助手（Alt+V）"
      @click="handleFab"
    >
      <span v-if="listening" class="voice-fab__ring" aria-hidden="true"></span>
      <Mic v-if="!listening" :size="22" />
      <MicOff v-else :size="22" />
    </button>

    <Transition name="voice-panel">
      <section
        v-if="open"
        ref="panelRef"
        class="voice-panel"
        role="dialog"
        aria-label="语音助手"
        aria-modal="false"
      >
        <header class="voice-header">
          <span class="voice-header__mark"><Sparkles :size="17" /></span>
          <div class="voice-header__titles">
            <strong>语音助手</strong>
            <small>{{ listening ? "正在听…" : busy ? "正在处理…" : speaking ? "正在回答…" : "说一句话，直接控制播放器" }}</small>
          </div>
          <span class="voice-badge" :class="{ 'is-ai': llmConfigured }">
            {{ llmConfigured ? "AI 对话" : llmChecked ? "本地指令" : "…" }}
          </span>
          <button class="voice-icon-button" type="button" aria-label="关闭语音助手" @click="closePanel">
            <X :size="18" />
          </button>
        </header>

        <div ref="messagesRef" class="voice-messages" aria-live="polite">
          <div
            v-for="message in messages"
            :key="message.id"
            class="voice-message"
            :class="[
              `voice-message--${message.role}`,
              { 'voice-message--error': message.error },
            ]"
          >
            <p>{{ message.text }}</p>
            <div v-if="message.results?.length" class="voice-results">
              <button
                v-for="(track, index) in message.results"
                :key="`${message.id}:${track.id || index}`"
                class="voice-result"
                type="button"
                @click="playResultFromMessage(track, index)"
              >
                <span class="voice-result__index">{{ index + 1 }}</span>
                <span class="voice-result__text">
                  <strong>{{ track.title }}</strong>
                  <small>{{ track.artist || "未知艺人" }}</small>
                </span>
                <Play :size="14" />
              </button>
            </div>
          </div>

          <div v-if="listening || interim" class="voice-message voice-message--assistant voice-message--listening">
            <p>{{ interim || "我在听…" }}</p>
          </div>
          <div v-if="busy" class="voice-message voice-message--assistant voice-message--thinking">
            <LoaderCircle :size="15" class="voice-spin" />
            <span>正在处理</span>
          </div>
        </div>

        <div v-if="showExamples" class="voice-examples" aria-label="示例指令">
          <button type="button" @click="useExample('播放周杰伦的晴天')">播放周杰伦的晴天</button>
          <button type="button" @click="useExample('下一首')">下一首</button>
          <button type="button" @click="useExample('音量大一点')">音量大一点</button>
          <button type="button" @click="useExample('播放我的歌单')">播放我的歌单</button>
        </div>

        <form class="voice-composer" @submit.prevent="sendText">
          <input
            ref="inputRef"
            v-model="inputText"
            type="text"
            autocomplete="off"
            enterkeyhint="send"
            :disabled="busy"
            aria-label="输入语音指令"
            placeholder="也可以直接输入，例如：下一首"
          />
          <button class="voice-send" type="submit" :disabled="busy || !inputText.trim()" aria-label="发送">
            <Send :size="17" />
          </button>
        </form>

        <div class="voice-actions">
          <button
            class="voice-talk"
            :class="{ 'is-listening': listening }"
            type="button"
            :disabled="!micSupported || busy"
            :aria-pressed="listening"
            :title="micLabel"
            @click="toggleListening"
          >
            <Mic v-if="!listening" :size="17" />
            <MicOff v-else :size="17" />
            <span>{{ micLabel }}</span>
          </button>
          <label class="voice-switch">
            <input v-model="continuous" type="checkbox" />
            <span aria-hidden="true"></span>
            连续对话
          </label>
          <label class="voice-switch">
            <input v-model="voiceReply" type="checkbox" />
            <span aria-hidden="true"></span>
            语音回复
          </label>
        </div>

        <p v-if="!micSupported" class="voice-note">
          当前浏览器不支持语音识别，可以用文字输入；Chrome 或 Edge 支持语音。
        </p>
      </section>
    </Transition>
  </Teleport>
</template>

<style scoped>
.voice-fab {
  position: fixed;
  right: max(22px, env(safe-area-inset-right, 0px));
  bottom: calc(var(--player-dock-height) + 12px);
  z-index: 45;
  width: 54px;
  height: 54px;
  border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--line));
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: var(--on-accent);
  box-shadow: 0 12px 30px color-mix(in srgb, var(--accent) 30%, transparent), var(--shadow-sm);
  cursor: pointer;
  transition: transform var(--motion-fast) var(--ease-standard), box-shadow var(--motion-fast) var(--ease-standard);
}

.voice-fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 16px 36px color-mix(in srgb, var(--accent) 38%, transparent), var(--shadow-md);
}

.voice-fab:focus-visible,
.voice-icon-button:focus-visible,
.voice-send:focus-visible,
.voice-talk:focus-visible,
.voice-result:focus-visible,
.voice-examples button:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.voice-fab.is-listening {
  background: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 40%, var(--line));
}

.voice-fab__ring {
  position: absolute;
  inset: -7px;
  border-radius: inherit;
  border: 2px solid color-mix(in srgb, var(--danger) 55%, transparent);
  animation: voice-ring 1.5s ease-out infinite;
  pointer-events: none;
}

@keyframes voice-ring {
  0% { transform: scale(0.86); opacity: 0.9; }
  100% { transform: scale(1.35); opacity: 0; }
}

.voice-panel {
  position: fixed;
  right: max(22px, env(safe-area-inset-right, 0px));
  bottom: calc(var(--player-dock-height) + 78px);
  z-index: 46;
  width: min(390px, calc(100vw - 24px));
  max-height: min(620px, 72vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 24px;
  background:
    linear-gradient(180deg, var(--glass-tint), transparent 42%),
    var(--surface-raised);
  color: var(--text);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(28px) saturate(1.25);
}

.voice-header {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto 32px;
  align-items: center;
  gap: 9px;
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--line);
}

.voice-header__mark {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: var(--accent-soft);
  color: var(--accent);
}

.voice-header__titles {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.voice-header__titles strong {
  font-size: 14px;
  font-weight: 750;
}

.voice-header__titles small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11.5px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-badge {
  padding: 4px 7px;
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: 10.5px;
  font-weight: 700;
  white-space: nowrap;
}

.voice-badge.is-ai {
  background: var(--accent-soft);
  color: var(--accent);
}

.voice-icon-button {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.voice-icon-button:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.voice-messages {
  flex: 1;
  min-height: 150px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 14px;
  overscroll-behavior: contain;
}

.voice-message {
  max-width: 88%;
  padding: 9px 12px;
  border-radius: 16px;
  font-size: 13.5px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.voice-message p {
  margin: 0;
  white-space: pre-wrap;
}

.voice-message--assistant {
  align-self: flex-start;
  border-bottom-left-radius: 6px;
  background: var(--surface-muted);
  color: var(--text);
}

.voice-message--user {
  align-self: flex-end;
  border-bottom-right-radius: 6px;
  background: var(--accent);
  color: var(--on-accent);
}

.voice-message--error {
  background: color-mix(in srgb, var(--danger) 12%, var(--surface-raised));
  color: var(--danger);
}

.voice-message--listening {
  color: var(--text-secondary);
  border: 1px dashed var(--line-strong);
  background: transparent;
}

.voice-message--thinking {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
}

.voice-spin {
  animation: voice-spin 1s linear infinite;
}

@keyframes voice-spin {
  to { transform: rotate(360deg); }
}

.voice-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 9px;
}

.voice-result {
  width: 100%;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 7px;
  padding: 6px 7px;
  border: 0;
  border-radius: 10px;
  background: color-mix(in srgb, var(--text) 4%, transparent);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.voice-result:hover {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

.voice-result__index {
  color: var(--text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.voice-result__text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.voice-result__text strong {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-result__text small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 0 14px 12px;
}

.voice-examples button {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 11.5px;
  cursor: pointer;
}

.voice-examples button:hover {
  border-color: var(--accent-line);
  color: var(--accent);
}

.voice-composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 36px;
  gap: 7px;
  padding: 0 14px 10px;
}

.voice-composer input {
  min-width: 0;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--input-bg);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  outline: none;
}

.voice-composer input:focus {
  border-color: var(--accent-line);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.voice-send {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  align-self: center;
  background: var(--accent);
  color: var(--on-accent);
  cursor: pointer;
}

.voice-send:disabled {
  opacity: 0.45;
  cursor: default;
}

.voice-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px 12px;
  border-top: 1px solid var(--line);
}

.voice-talk {
  flex: 1;
  min-width: 0;
  min-height: 36px;
  border: 1px solid var(--line);
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.voice-talk.is-listening {
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 10%, transparent);
  color: var(--danger);
}

.voice-talk:disabled {
  opacity: 0.5;
  cursor: default;
}

.voice-switch {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}

.voice-switch input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.voice-switch > span {
  position: relative;
  width: 28px;
  height: 17px;
  border-radius: 999px;
  background: var(--surface-muted);
  box-shadow: inset 0 0 0 1px var(--line);
  transition: background var(--motion-fast) var(--ease-standard);
}

.voice-switch > span::after {
  content: "";
  position: absolute;
  top: 2px;
  left: 2px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--surface-raised);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
  transition: transform var(--motion-fast) var(--ease-standard);
}

.voice-switch input:checked + span {
  background: var(--accent);
  box-shadow: none;
}

.voice-switch input:checked + span::after {
  transform: translateX(11px);
  background: var(--on-accent);
}

.voice-switch input:focus-visible + span {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.voice-note {
  margin: 0;
  padding: 0 14px 12px;
  color: var(--text-muted);
  font-size: 10.5px;
  line-height: 1.35;
}

.voice-panel-enter-active,
.voice-panel-leave-active {
  transition: opacity var(--motion-base) var(--ease-emphasized), transform var(--motion-base) var(--ease-emphasized);
}

.voice-panel-enter-from,
.voice-panel-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}

@media (max-width: 880px) {
  .voice-fab {
    right: max(14px, env(safe-area-inset-right, 0px));
    bottom: calc(var(--player-dock-height) + 10px);
    width: 50px;
    height: 50px;
  }

  .voice-panel {
    right: 12px;
    left: 12px;
    bottom: calc(var(--player-dock-height) + 70px);
    width: auto;
    max-height: min(64vh, 560px);
    border-radius: 22px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .voice-fab,
  .voice-panel,
  .voice-fab__ring,
  .voice-spin {
    animation: none;
    transition: none;
  }
}
</style>

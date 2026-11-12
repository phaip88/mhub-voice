const CN_DIGITS = {
  零: 0,
  〇: 0,
  一: 1,
  二: 2,
  两: 2,
  三: 3,
  四: 4,
  五: 5,
  六: 6,
  七: 7,
  八: 8,
  九: 9,
};

const PLATFORM_WORDS = [
  { platform: "netease", pattern: /(?:网易云音乐|网易云|网易|netease)/u },
  { platform: "tencent", pattern: /(?:qq音乐|qq|腾讯音乐|腾讯)/u },
  { platform: "applemusic", pattern: /(?:apple music|苹果音乐|applemusic|苹果)/u },
];

const PRONOUNS = new Set(["我", "你", "他", "她", "它", "我们", "你们", "他们", "她们"]);

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

/** Parse Arabic or simple Chinese numerals (0-9999). */
export function parseNumber(input) {
  const text = String(input ?? "").trim();
  if (!text) return Number.NaN;
  if (/^\d+(?:\.\d+)?$/u.test(text)) return Number(text);

  let total = 0;
  let section = 0;
  let number = 0;
  let sawNumber = false;
  for (const char of text) {
    if (Object.hasOwn(CN_DIGITS, char)) {
      number = CN_DIGITS[char];
      sawNumber = true;
      continue;
    }
    if (char === "十") {
      section += (number || 1) * 10;
      number = 0;
      sawNumber = true;
      continue;
    }
    if (char === "百") {
      section += (number || 1) * 100;
      number = 0;
      sawNumber = true;
      continue;
    }
    if (char === "千") {
      section += (number || 1) * 1000;
      number = 0;
      sawNumber = true;
      continue;
    }
    if (char === "万") {
      total += (section + number) * 10000;
      section = 0;
      number = 0;
      sawNumber = true;
    }
  }
  return sawNumber ? total + section + number : Number.NaN;
}

/** Milliseconds from "1分30秒", "九十秒", "1:30". */
export function parseDurationMs(input) {
  const text = String(input ?? "");
  let total = 0;
  let matched = false;
  const units = /(\d+(?:\.\d+)?|[零〇一二三四五六七八九十百千两]+)\s*(小时|钟头|分钟|分|秒)/gu;
  for (const match of text.matchAll(units)) {
    const value = parseNumber(match[1]);
    if (!Number.isFinite(value)) continue;
    matched = true;
    if (match[2] === "小时" || match[2] === "钟头") total += value * 3_600_000;
    else if (match[2] === "分钟" || match[2] === "分") total += value * 60_000;
    else total += value * 1_000;
  }
  if (matched) return total;

  const clock = text.match(/(\d{1,3})\s*[:：]\s*(\d{1,2})(?:\s*[:：]\s*(\d{1,2}))?/u);
  if (clock) {
    return (Number(clock[1]) * 60 + Number(clock[2])) * 1_000 + Number(clock[3] || 0) * 1_000;
  }
  return Number.NaN;
}

export function normalizeText(input) {
  let text = String(input ?? "").normalize("NFKC").toLowerCase();
  text = text.replace(/[，。！？、；：""''（）【】《》〈〉…—~～!?,.;:"'()\[\]<>]/gu, " ");
  text = text.replace(/^(?:嘿|嗨|哈喽|hello|hi)\s*/u, "");
  text = text.replace(/^(?:请|麻烦|帮我|给我|能不能|可以)\s*/u, "");
  text = text.replace(/^(?:海鸥|小鸥|小海|mhub|m hub)\s*/u, "");
  text = text.replace(/\s+/gu, " ").trim();
  return text.replace(/(?:谢谢|好吗|可以吗|行吗|好不好|吧|呗)$/u, "").trim();
}

function platformFrom(text) {
  for (const entry of PLATFORM_WORDS) {
    if (entry.pattern.test(text)) return entry.platform;
  }
  return null;
}

function removePlatformPrefix(text) {
  return text
    .replace(/^(?:用|在|从|通过)?\s*(?:网易云音乐|网易云|网易|qq音乐|腾讯音乐|apple music|苹果音乐|applemusic|qq)\s*(?:上|里|中)?\s*/u, "")
    .trim();
}

function cleanQuery(input) {
  let query = String(input || "")
    .replace(/^(?:一下|一首|首|点|一些|一段|一首歌|首歌)\s*/u, "")
    .replace(/(?:这首歌|这首歌曲|歌曲|音乐)$/u, "")
    .trim();
  const parts = query.split("的");
  if (parts.length === 2 && parts[0].length >= 2 && parts[1].length >= 1 && !PRONOUNS.has(parts[0])) {
    query = `${parts[0]} ${parts[1]}`;
  }
  return query.trim();
}

function resultIndex(text) {
  if (/最后一个/u.test(text)) return -1;
  const match = text.match(/第?\s*([零〇一二三四五六七八九十百千两\d]+)\s*(?:个|首|条)/u);
  if (!match) return null;
  const value = parseNumber(match[1]);
  return Number.isFinite(value) && value >= 1 ? value - 1 : null;
}

function percentFrom(text) {
  const match =
    text.match(/百分之\s*([零〇一二三四五六七八九十百千两\d.]+)/u) ||
    text.match(/([零〇一二三四五六七八九十百千两\d.]+)\s*%/u);
  if (!match) return Number.NaN;
  const value = parseNumber(match[1]);
  if (!Number.isFinite(value)) return Number.NaN;
  return clamp01(value > 1 ? value / 100 : value);
}

function parseVolume(text) {
  if (/(?:取消静音|解除静音|恢复声音|恢复音量|打开声音|取消无声)/u.test(text)) {
    return { kind: "volume", mode: "unmute" };
  }
  if (/(?:静音|关闭声音|不要声音|别出声|无声)/u.test(text)) {
    return { kind: "volume", mode: "mute" };
  }
  if (/(?:最大音量|音量最大|声音最大|最大声)/u.test(text)) {
    return { kind: "volume", mode: "set", value: 1 };
  }
  if (/(?:最小音量|音量最小|声音最小|最小声)/u.test(text)) {
    return { kind: "volume", mode: "set", value: 0 };
  }

  const explicit =
    text.match(/(?:音量|声音|声量|声音大小)\s*(?:调到|调成|设置为|设为|到)\s*(?:百分之)?\s*([零〇一二三四五六七八九十百千两\d.]+)\s*%?/u) ||
    text.match(/(?:调到|调成|设置为|设为)\s*(?:百分之)?\s*([零〇一二三四五六七八九十百千两\d.]+)\s*%?/u);
  if (explicit) {
    const percent = percentFrom(explicit[0]);
    const value = Number.isFinite(percent) ? percent : clamp01(parseNumber(explicit[1]) / 100);
    if (Number.isFinite(value)) return { kind: "volume", mode: "set", value };
  }

  const bare = text.match(/^(?:音量|声音|声量)\s*(?:是|为)?\s*([零〇一二三四五六七八九十百千两\d.]+)\s*%?$/u);
  if (bare) {
    const value = clamp01(parseNumber(bare[1]) / 100);
    if (Number.isFinite(value)) return { kind: "volume", mode: "set", value };
  }
  const percent = percentFrom(text);
  if (Number.isFinite(percent) && /(?:音量|声音|声量)/u.test(text)) {
    return { kind: "volume", mode: "set", value: percent };
  }

  const delta = text.match(/(?:音量|声音|声量)?\s*(?:加|增加|增大|调大|提高)\s*([零〇一二三四五六七八九十百千两\d.]+)\s*%?/u);
  if (/(?:大声|大一点|大点|调大|增大|提高)/u.test(text) || /(?:音量|声音|声量)\s*(?:加|增加)\s*[零〇一二三四五六七八九十百千两\d]/u.test(text)) {
    const size = delta?.[1] ? parseNumber(delta[1]) : Number.NaN;
    return { kind: "volume", mode: "delta", value: Number.isFinite(size) ? clamp01(size / 100) : 0.1 };
  }
  const down = text.match(/(?:音量|声音|声量)?\s*(?:减|减少|降低|调小)\s*([零〇一二三四五六七八九十百千两\d.]+)\s*%?/u);
  if (/(?:小声|小一点|小点|调小|减小|降低)/u.test(text) || /(?:音量|声音|声量)\s*(?:减|减少)\s*[零〇一二三四五六七八九十百千两\d]/u.test(text)) {
    const size = down?.[1] ? parseNumber(down[1]) : Number.NaN;
    return { kind: "volume", mode: "delta", value: -(Number.isFinite(size) ? clamp01(size / 100) : 0.1) };
  }
  return null;
}

function parseSeek(text, raw) {
  if (/(?:从头|重新开始|重头)/u.test(text)) return { kind: "seek", mode: "absolute", ms: 0 };
  const ms = parseDurationMs(raw);
  if (!Number.isFinite(ms)) return null;
  if (/(?:快进到|跳到|跳转到|定位到)/u.test(text)) return { kind: "seek", mode: "absolute", ms };
  if (/(?:后退|倒退|倒回|往回|退)/u.test(text)) return { kind: "seek", mode: "delta", ms: -ms };
  if (/(?:快进|前进|快退)/u.test(text)) return { kind: "seek", mode: "delta", ms };
  if (/(?:到)/u.test(text)) return { kind: "seek", mode: "absolute", ms };
  return null;
}

function parseQueue(text) {
  const hasQueue = /(?:队列|播放列表|播放队列)/u.test(text);
  const asNext = /(?:设为|作为|放到|加入|插入).{0,6}下一首|下一首播放/u.test(text);
  if (!hasQueue && !asNext) return null;
  const target = text.match(/(?:把|将)?\s*第?\s*([零〇一二三四五六七八九十百千两\d]+)\s*(?:首|个|条)/u);
  const index = target ? parseNumber(target[1]) - 1 : null;
  const next = asNext;
  if (!/(?:加入|加到|放到|添加到|放进|设为|作为|插入)/u.test(text)) return null;
  return {
    kind: "queue-add",
    mode: next ? "next" : "end",
    index: Number.isFinite(index) && index >= 0 ? index : null,
  };
}

function openView(text) {
  if (!/^(?:打开|去|进入|回到|返回|切换)/u.test(text)) return null;
  if (/(?:播放)?队列/u.test(text)) return { kind: "open", view: "queue" };
  if (/(?:资料库|我的歌单|歌单页|歌单列表)/u.test(text)) return { kind: "open", view: "library" };
  if (/(?:搜索|搜索页)/u.test(text)) return { kind: "open", view: "search" };
  if (/(?:缓存|本地缓存)/u.test(text)) return { kind: "open", view: "cache" };
  if (/(?:歌词|全屏|播放页|播放器)/u.test(text)) return { kind: "open", view: "player" };
  if (/(?:主页|首页|推荐|发现)/u.test(text)) return { kind: "open", view: "discover" };
  return null;
}

/**
 * Turn one utterance into a player intent. The parser deliberately prefers
 * transport commands over search: "播放下一首" must skip a track, not search
 * for a song literally named 下一首.
 */
export function parseVoiceIntent(input, context = {}) {
  const raw = String(input ?? "").trim();
  if (!raw) return { kind: "unknown", text: raw };
  const text = normalizeText(raw);
  if (!text) return { kind: "greeting" };

  if (/^(?:你好|嗨|哈喽|hello|hi|在吗|早上好|晚上好|早安|晚安)$/u.test(text)) {
    return { kind: "greeting" };
  }
  if (/(?:帮助|你能做什么|你会什么|怎么用|支持什么|有什么功能|使用说明|命令列表)/u.test(text)) {
    return { kind: "help" };
  }

  if (
    /(?:现在|当前|正在)(?:播放|放)?(?:的)?(?:是)?(?:什么|哪首|啥)/u.test(text) ||
    /(?:这|那)(?:是)?(?:什么|哪首)歌/u.test(text) ||
    /(?:谁唱的|播放到哪|还有多久|剩多久|正在播放什么|播放进度)/u.test(text) ||
    /(?:当前歌曲|正在播放).{0,6}(?:什么|哪首|谁|多久)/u.test(text)
  ) {
    return { kind: "now-playing", detail: /(?:哪|多久|时间|进度)/u.test(text) ? "time" : "full" };
  }

  if (/^(?:停止|停下|关掉|别放了|不听了)$/u.test(text) || /^停止(?:播放|音乐)?$/u.test(text)) {
    return { kind: "stop" };
  }
  if (/(?:暂停|停一下|先停|别放|不要放)/u.test(text) && !/(?:继续|恢复)/u.test(text)) {
    return { kind: "pause" };
  }
  if (
    /^(?:继续|继续播放|接着放|接着播放|恢复播放|播放|放吧|开始播放|再放)$/u.test(text) ||
    /(?:继续播放|接着放|恢复播放)/u.test(text)
  ) {
    return { kind: "resume" };
  }

  if (/(?:下一首|下首|切歌|跳过|next)/u.test(text) && !/(?:队列|加入|设为|排队)/u.test(text)) {
    return { kind: "next" };
  }
  if (/(?:上一首|上首|前一首|回到上一首|previous)/u.test(text)) {
    return { kind: "prev" };
  }
  if (/^(?:换一个|换一首|再来一首|不要这首|不要这个|换一个吧|换首歌)$/u.test(text)) {
    return { kind: "another" };
  }

  const volume = parseVolume(text);
  if (volume) return volume;

  const seek = parseSeek(text, raw);
  if (seek) return seek;

  if (/(?:取消随机|关闭随机|不要随机|顺序播放)/u.test(text)) return { kind: "shuffle", enabled: false };
  if (/(?:随机播放一首|随便放一首|随机来一首|随便来一首)/u.test(text)) return { kind: "random" };
  if (/(?:随机|打乱)/u.test(text)) return { kind: "shuffle", enabled: true };

  if (/(?:关闭循环|取消循环|不要循环)/u.test(text)) return { kind: "repeat", mode: "off" };
  if (/(?:单曲循环|循环这首|重复这首)/u.test(text)) return { kind: "repeat", mode: "one" };
  if (/(?:列表循环|全部循环|循环播放|循环)/u.test(text)) return { kind: "repeat", mode: "list" };

  if (/(?:关闭自动续播|不要自动续播|取消自动续播)/u.test(text)) return { kind: "autoplay", enabled: false };
  if (/(?:开启自动续播|打开自动续播|自动续播|自动播放推荐)/u.test(text)) return { kind: "autoplay", enabled: true };

  const platform = platformFrom(text);
  const command = removePlatformPrefix(text);

  const playlistMatch = command.match(/^(?:播放|听|放|打开|我想听|想听|给我放)\s*(?:我的)?\s*歌单\s*(.*)$/u);
  if (playlistMatch) {
    return { kind: "play-playlist", name: playlistMatch[1].trim(), platform };
  }

  const view = openView(command);
  if (view) return view;

  const queue = parseQueue(command);
  if (queue) return queue;

  if (/(?:喜欢|收藏|点赞)/u.test(command) && /(?:这|当前|现在|正在|这首|首歌)/u.test(command)) {
    return { kind: "like" };
  }

  const index = resultIndex(command);
  if (index !== null && (/(?:播放|听|要|就|选)/u.test(command) || Array.isArray(context.lastResults))) {
    return { kind: "select-result", index };
  }

  const searchMatch = command.match(/^(?:搜索|搜一下|搜|查找|查一下|查|找一下|找)\s*(.+)$/u);
  if (searchMatch) return { kind: "search", query: cleanQuery(searchMatch[1]), platform };

  const playThenMatch = command.match(/^(?:搜索|搜|查|找)\s*(?:一下)?\s*(.+?)\s*(?:并|然后)?\s*(?:播放|放)\s*(.*)$/u);
  if (playThenMatch) {
    const query = cleanQuery([playThenMatch[1], playThenMatch[2]].filter(Boolean).join(" "));
    if (query) return { kind: "play-query", query, platform };
  }

  if (/^(?:听歌|放歌|来点音乐|播放音乐|放点音乐|随便听听)$/u.test(command)) {
    return { kind: "random" };
  }

  const playMatch = command.match(/^(?:播放|放|听|来一首|来点|我想听|想听|我要听|给我放|放一首|放首)\s*(.+)$/u);
  if (playMatch) {
    const query = cleanQuery(playMatch[1]);
    if (!query || /^(?:音乐|歌|歌曲)$/u.test(query)) return { kind: "resume" };
    return { kind: "play-query", query, platform };
  }

  return { kind: "unknown", text: raw };
}

const RECOGNITION_ERRORS = {
  "not-allowed": "麦克风权限被拒绝，请在浏览器设置里允许后重试。",
  "service-not-allowed": "麦克风权限被拒绝，请在浏览器设置里允许后重试。",
  "audio-capture": "没有找到可用的麦克风。",
  "no-speech": "没有听到声音，再说一次。",
  network: "语音识别网络暂时不可用，可以改用文字输入。",
  "language-not-supported": "当前浏览器不支持中文语音识别，可以改用文字输入。",
  aborted: "",
};

const nativeBridge = typeof window !== "undefined" ? window.MHubNative : null;
const hasNativeRecognition = Boolean(
  nativeBridge
  && typeof nativeBridge.startListening === "function"
  && typeof nativeBridge.isSpeechRecognitionAvailable === "function"
  && nativeBridge.isSpeechRecognitionAvailable(),
);
const hasNativeTts = Boolean(
  nativeBridge
  && typeof nativeBridge.speak === "function"
  && (typeof nativeBridge.isTtsAvailable !== "function" || nativeBridge.isTtsAvailable()),
);

let nativeTurn = null;
let nativeTtsTurn = null;
let nativeTtsSequence = 0;

if (typeof window !== "undefined" && nativeBridge) {
  window.__mhubNativeVoice = {
    onInterim(text) {
      nativeTurn?.onInterim?.(String(text || ""));
    },
    onFinal(text) {
      nativeTurn?.onFinal?.(String(text || ""));
    },
    onError(message, code) {
      nativeTurn?.onError?.(String(message || ""), String(code || "native"));
    },
    onEnd() {
      const turn = nativeTurn;
      nativeTurn = null;
      turn?.onEnd?.();
    },
    onTtsStart(id) {
      if (!nativeTtsTurn || nativeTtsTurn.id !== Number(id)) return;
      nativeTtsTurn.onStart?.();
    },
    onTtsEnd(id) {
      if (!nativeTtsTurn || nativeTtsTurn.id !== Number(id)) return;
      const turn = nativeTtsTurn;
      nativeTtsTurn = null;
      turn.onEnd?.();
    },
    onTtsError(id, message) {
      if (!nativeTtsTurn || nativeTtsTurn.id !== Number(id)) return;
      const turn = nativeTtsTurn;
      nativeTtsTurn = null;
      if (browserTtsSupported()) {
        browserSpeakText(turn.text, {
          lang: turn.lang,
          rate: turn.rate,
          onStart: turn.onStart,
          onEnd: turn.onEnd,
        });
        return;
      }
      console.warn("Native speech synthesis failed:", message);
      turn.onEnd?.();
    },
  };
}

export function speechRecognitionSupported() {
  return hasNativeRecognition
    || (typeof window !== "undefined" && Boolean(window.SpeechRecognition || window.webkitSpeechRecognition));
}

export function speechSynthesisSupported() {
  return hasNativeTts || browserTtsSupported();
}

function browserTtsSupported() {
  return typeof window !== "undefined" && "speechSynthesis" in window && typeof window.SpeechSynthesisUtterance === "function";
}

export function recognitionErrorMessage(code) {
  return RECOGNITION_ERRORS[code] ?? "语音识别失败，请重试或输入文字。";
}

/**
 * Thin wrapper around the browser's SpeechRecognition. It keeps one
 * recognition object per listening turn, so a failed start never poisons the
 * next attempt.
 */
export function createRecognizer({ lang = "zh-CN", onInterim, onFinal, onError, onEnd } = {}) {
  if (hasNativeRecognition) {
    return createNativeRecognizer({ lang, onInterim, onFinal, onError, onEnd });
  }
  if (!speechRecognitionSupported()) {
    return {
      supported: false,
      start() { return false; },
      stop() {},
      abort() {},
    };
  }

  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recognition = null;
  let active = false;
  let finalDelivered = false;

  function release() {
    active = false;
    recognition = null;
  }

  function start() {
    if (active) return false;
    active = true;
    finalDelivered = false;
    recognition = new Recognition();
    recognition.lang = lang;
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event) => {
      let interim = "";
      let finalText = "";
      for (let index = event.resultIndex || 0; index < event.results.length; index += 1) {
        const result = event.results[index];
        const transcript = result?.[0]?.transcript || "";
        if (result?.isFinal) finalText += transcript;
        else interim += transcript;
      }
      if (interim.trim()) onInterim?.(interim.trim());
      if (finalText.trim() && !finalDelivered) {
        finalDelivered = true;
        onFinal?.(finalText.trim());
      }
    };

    recognition.onerror = (event) => {
      if (event.error === "aborted") return;
      const message = recognitionErrorMessage(event.error);
      release();
      if (message) onError?.(message, event.error);
    };

    recognition.onend = () => {
      release();
      onEnd?.();
    };

    try {
      recognition.start();
      return true;
    } catch (error) {
      release();
      onError?.(error?.name === "InvalidStateError" ? "语音识别已经在运行。" : "无法启动语音识别，请重试。", error?.name);
      return false;
    }
  }

  function stop() {
    if (!recognition) return;
    try { recognition.stop(); } catch { /* Already stopped. */ }
  }

  function abort() {
    if (!recognition) return;
    try { recognition.abort(); } catch { /* Already stopped. */ }
  }

  return { supported: true, start, stop, abort };
}

function createNativeRecognizer({ lang, onInterim, onFinal, onError, onEnd }) {
  const turn = { onInterim, onFinal, onError, onEnd };
  let active = true;

  return {
    supported: true,
    start() {
      if (!active) return false;
      nativeTurn = turn;
      try {
        nativeBridge.startListening(lang);
        return true;
      } catch (error) {
        nativeTurn = null;
        onError?.("无法启动系统语音识别，请重试。", error?.name);
        return false;
      }
    },
    stop() {
      try { nativeBridge.stopListening(); } catch { /* Bridge may be gone. */ }
    },
    abort() {
      active = false;
      if (nativeTurn === turn) nativeTurn = null;
      try { nativeBridge.cancelListening(); } catch { /* Bridge may be gone. */ }
    },
  };
}

let currentUtterance = null;

/**
 * Speak a short confirmation. `onEnd` always runs exactly once, including
 * when speech synthesis is unavailable or cancelled by the browser.
 */
export function speakText(text, { lang = "zh-CN", rate = 1.04, onStart, onEnd } = {}) {
  if (!text) {
    onEnd?.();
    return false;
  }
  if (hasNativeTts) {
    const id = ++nativeTtsSequence;
    nativeTtsTurn = { id, text: String(text), lang, rate, onStart, onEnd };
    try {
      nativeBridge.speak(String(text), lang, rate, id);
      return true;
    } catch (error) {
      nativeTtsTurn = null;
      console.warn("Native speech synthesis unavailable:", error);
    }
  }
  if (!browserTtsSupported()) {
    onEnd?.();
    return false;
  }
  return browserSpeakText(text, { lang, rate, onStart, onEnd });
}

function browserSpeakText(text, { lang = "zh-CN", rate = 1.04, onStart, onEnd } = {}) {
  window.speechSynthesis.cancel();
  const utterance = new window.SpeechSynthesisUtterance(text);
  utterance.lang = lang;
  utterance.rate = rate;
  utterance.pitch = 1;
  const voices = window.speechSynthesis.getVoices?.() || [];
  const chineseVoice = voices.find((voice) => /^zh/i.test(voice.lang || "")) || voices.find((voice) => /chinese|中文|普通话/i.test(voice.name || ""));
  if (chineseVoice) utterance.voice = chineseVoice;

  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    if (currentUtterance === utterance) currentUtterance = null;
    onEnd?.();
  };
  utterance.onstart = () => onStart?.();
  utterance.onend = finish;
  utterance.onerror = finish;
  currentUtterance = utterance;
  try {
    window.speechSynthesis.speak(utterance);
    return true;
  } catch {
    finish();
    return false;
  }
}

export function cancelSpeech() {
  currentUtterance = null;
  nativeTtsTurn = null;
  if (hasNativeTts) {
    try { nativeBridge.stopSpeaking(); } catch { /* Bridge may be gone. */ }
  }
  try { window.speechSynthesis?.cancel(); } catch { /* Optional API. */ }
}

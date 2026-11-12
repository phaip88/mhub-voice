const API = "/api/v1/voice";

async function readJson(response) {
  return response.json().catch(() => ({}));
}

export async function getVoiceStatus({ signal } = {}) {
  const response = await fetch(`${API}/status`, { signal, cache: "no-store" });
  const payload = await readJson(response);
  if (!response.ok) throw new Error(payload.detail || `语音服务 HTTP ${response.status}`);
  return payload;
}

/**
 * The API key and model live on the server. The browser only sends the
 * conversation and tool schemas, so credentials never enter the client.
 */
export async function chatWithTools({ messages, tools, signal }) {
  const response = await fetch(`${API}/chat`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ messages, tools }),
    signal,
  });
  const payload = await readJson(response);
  if (!response.ok) {
    const error = new Error(payload.detail || `语音服务 HTTP ${response.status}`);
    error.status = response.status;
    error.code = payload.code;
    throw error;
  }
  return payload;
}

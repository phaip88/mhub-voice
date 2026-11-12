//! Optional AI conversation proxy for the browser voice assistant.
//!
//! Credentials stay on the server. The browser sends only the conversation
//! and the tool schemas; this module forwards one non-streaming
//! OpenAI-compatible chat completion request.

use crate::{
    ApiError, ApiResult,
    upstream_limit::{self, Kind},
};
use axum::{Json, http::StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{sync::OnceLock, time::Duration};

const MAX_MESSAGES: usize = 24;
const MAX_TOOLS: usize = 32;
const MAX_BODY_BYTES: usize = 96 * 1024;

#[derive(Clone, Debug)]
struct VoiceConfig {
    base_url: String,
    api_key: String,
    model: String,
    thinking: Option<String>,
    auth_header: String,
    auth_scheme: String,
}

#[derive(Debug, Serialize)]
pub struct VoiceStatus {
    configured: bool,
    model: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ChatRequest {
    messages: Vec<Value>,
    #[serde(default)]
    tools: Option<Value>,
}

fn env_value(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn explicit_env_value(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
}

fn completion_url(base: &str) -> Result<url::Url, String> {
    let trimmed = base.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err("MHUB_VOICE_LLM_BASE_URL is empty".to_string());
    }
    let explicit = url::Url::parse(trimmed)
        .map(|url| url.path().ends_with("/chat/completions") || url.query().is_some())
        .unwrap_or(false);
    let full = if explicit {
        trimmed.to_string()
    } else {
        format!("{trimmed}/chat/completions")
    };
    let url =
        url::Url::parse(&full).map_err(|error| format!("invalid voice model URL: {error}"))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err("voice model URL must use http or https".to_string());
    }
    Ok(url)
}

impl VoiceConfig {
    fn from_env() -> Result<Self, String> {
        let base_url = env_value("MHUB_VOICE_LLM_BASE_URL")
            .ok_or_else(|| "MHUB_VOICE_LLM_BASE_URL is not configured".to_string())?;
        let api_key = env_value("MHUB_VOICE_LLM_API_KEY")
            .ok_or_else(|| "MHUB_VOICE_LLM_API_KEY is not configured".to_string())?;
        completion_url(&base_url)?;
        let model = env_value("MHUB_VOICE_LLM_MODEL").unwrap_or_else(|| "gpt-4o-mini".to_string());
        let thinking =
            match env_value("MHUB_VOICE_LLM_THINKING").map(|value| value.to_ascii_lowercase()) {
                Some(value) if matches!(value.as_str(), "enabled" | "disabled") => Some(value),
                Some(value) => {
                    return Err(format!(
                        "MHUB_VOICE_LLM_THINKING must be enabled or disabled, got {value}"
                    ));
                }
                None if model.to_ascii_lowercase().starts_with("deepseek-") => {
                    Some("disabled".to_string())
                }
                None => None,
            };
        Ok(Self {
            base_url,
            api_key,
            model,
            thinking,
            auth_header: env_value("MHUB_VOICE_LLM_AUTH_HEADER")
                .unwrap_or_else(|| "authorization".to_string()),
            auth_scheme: explicit_env_value("MHUB_VOICE_LLM_AUTH_SCHEME")
                .unwrap_or_else(|| "Bearer".to_string()),
        })
    }
}

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(45))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

fn bad_request(message: impl Into<String>) -> ApiError {
    ApiError::Http {
        status: StatusCode::BAD_REQUEST,
        message: message.into(),
    }
}

fn unavailable(message: impl Into<String>) -> ApiError {
    ApiError::Http {
        status: StatusCode::SERVICE_UNAVAILABLE,
        message: message.into(),
    }
}

fn validate(request: &ChatRequest) -> Result<(), ApiError> {
    if request.messages.is_empty() || request.messages.len() > MAX_MESSAGES {
        return Err(bad_request("messages must contain 1 to 24 entries"));
    }
    for message in &request.messages {
        let Some(object) = message.as_object() else {
            return Err(bad_request("each message must be an object"));
        };
        let role = object
            .get("role")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if !matches!(role, "system" | "user" | "assistant" | "tool") {
            return Err(bad_request("unsupported message role"));
        }
        if !matches!(
            object.get("content"),
            Some(Value::String(_)) | Some(Value::Null)
        ) {
            return Err(bad_request("message content must be a string or null"));
        }
    }
    if let Some(tools) = &request.tools {
        let Some(items) = tools.as_array() else {
            return Err(bad_request("tools must be an array"));
        };
        if items.len() > MAX_TOOLS {
            return Err(bad_request("too many tools"));
        }
    }
    Ok(())
}

pub async fn status() -> Json<VoiceStatus> {
    match VoiceConfig::from_env() {
        Ok(config) => Json(VoiceStatus {
            configured: true,
            model: Some(config.model),
        }),
        Err(_) => Json(VoiceStatus {
            configured: false,
            model: None,
        }),
    }
}

pub async fn chat(Json(request): Json<ChatRequest>) -> ApiResult<Json<Value>> {
    let config = VoiceConfig::from_env().map_err(|_| {
        unavailable("AI 对话未配置：请设置 MHUB_VOICE_LLM_BASE_URL 和 MHUB_VOICE_LLM_API_KEY")
    })?;
    validate(&request)?;

    // Voice turns are upstream work too: they share the per-client limiter,
    // so one browser cannot drain a shared model key.
    upstream_limit::charge(Kind::Voice).await?;

    let mut payload = json!({
        "model": config.model,
        "messages": request.messages,
        "temperature": 0.3,
        "max_tokens": 600,
        "stream": false,
    });
    if let Some(thinking) = &config.thinking {
        payload["thinking"] = json!({ "type": thinking });
    }
    if let Some(tools) = request.tools {
        payload["tools"] = tools;
        payload["tool_choice"] = Value::String("auto".to_string());
    }
    let body = serde_json::to_vec(&payload)?;
    if body.len() > MAX_BODY_BYTES {
        return Err(bad_request("voice request is too large"));
    }

    let url = completion_url(&config.base_url).map_err(unavailable)?;
    let mut outgoing = client()
        .post(url)
        .header("content-type", "application/json")
        .header("user-agent", "MHub-Voice/1.0")
        .body(body);
    outgoing = if config.auth_scheme.is_empty() {
        outgoing.header(config.auth_header, config.api_key)
    } else {
        outgoing.header(
            config.auth_header,
            format!("{} {}", config.auth_scheme, config.api_key),
        )
    };

    let response = outgoing.send().await.map_err(|error| {
        tracing::warn!(target: "voice", error = %error, "voice model connection failed");
        ApiError::Http {
            status: StatusCode::BAD_GATEWAY,
            message: "语音模型连接失败".to_string(),
        }
    })?;
    let status = response.status();
    let text = response.text().await?;
    if !status.is_success() {
        tracing::warn!(
            target: "voice",
            status = %status,
            body = %text.chars().take(500).collect::<String>(),
            "voice model returned an error"
        );
        return Err(ApiError::Http {
            status: StatusCode::BAD_GATEWAY,
            message: format!("语音模型返回 HTTP {}", status.as_u16()),
        });
    }
    let value: Value = serde_json::from_str(&text)?;
    Ok(Json(value))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completion_url_appends_the_standard_path() {
        let url = completion_url("https://example.com/v1/").unwrap();
        assert_eq!(url.as_str(), "https://example.com/v1/chat/completions");
    }

    #[test]
    fn completion_url_keeps_an_explicit_endpoint() {
        let url = completion_url("https://example.com/v1/chat/completions").unwrap();
        assert_eq!(url.as_str(), "https://example.com/v1/chat/completions");
    }

    #[test]
    fn completion_url_keeps_explicit_query_parameters() {
        let url = completion_url(
            "https://example.com/openai/deployments/demo/chat/completions?api-version=2024-10-21",
        )
        .unwrap();
        assert_eq!(
            url.as_str(),
            "https://example.com/openai/deployments/demo/chat/completions?api-version=2024-10-21"
        );
    }

    #[test]
    fn completion_url_rejects_other_schemes() {
        assert!(completion_url("file:///tmp/model").is_err());
        assert!(completion_url("").is_err());
    }
}

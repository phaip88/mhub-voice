package com.mhub.voice;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "mhub_voice";
    private static final String KEY_SERVER = "server_url";
    private static final int MIC_REQUEST = 4101;
    private static final int BG = Color.rgb(14, 15, 19);
    private static final int PANEL = Color.rgb(25, 27, 34);
    private static final int LINE = Color.rgb(49, 53, 64);
    private static final int TEXT = Color.rgb(244, 246, 250);
    private static final int MUTED = Color.rgb(164, 171, 185);
    private static final int ACCENT = Color.rgb(79, 140, 255);
    private static final int ERROR = Color.rgb(255, 112, 112);

    private FrameLayout root;
    private ScrollView setupView;
    private WebView webView;
    private ProgressBar progress;
    private TextView settingsTab;
    private EditText serverInput;
    private TextView errorView;
    private SharedPreferences prefs;
    private String serverUrl;
    private boolean webVisible;
    private boolean webViewBlocked;

    private SpeechRecognizer recognizer;
    private boolean recognizing;
    private String pendingMicLang;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private String pendingSpeech;
    private String pendingSpeechLang;
    private float pendingSpeechRate;
    private int pendingSpeechId;
    private int activeSpeechId;
    private boolean ttsSpeaking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        buildRoot();
        if (webViewBlocked) return;
        buildWebView();
        buildSetup();
        buildProgress();
        buildSettingsTab();
        initTts();
        serverUrl = prefs.getString(KEY_SERVER, "");
        if (!serverUrl.isEmpty()) {
            openServer(serverUrl, false);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
    }

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        if (webViewPackage != null && webViewPackage.versionName != null && isWebViewTooOld(webViewPackage.versionName)) {
            webViewBlocked = true;
            showWebViewUpdate(webViewPackage.versionName);
        }
    }

    private boolean isWebViewTooOld(String versionName) {
        String major = versionName.split("\\.")[0];
        try {
            return Integer.parseInt(major) < 87;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void showWebViewUpdate(String versionName) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(40), dp(28), dp(40));

        TextView title = new TextView(this);
        title.setText(R.string.webview_too_old_title);
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(getString(R.string.webview_too_old_message, versionName));
        message.setTextColor(MUTED);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(16);
        content.addView(message, messageParams);

        Button update = new Button(this);
        update.setText(R.string.update_webview);
        update.setTextColor(Color.WHITE);
        update.setAllCaps(false);
        update.setBackground(rounded(ACCENT, ACCENT, 14));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(24);
        content.addView(update, buttonParams);
        update.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview")));
            } catch (Exception ignored) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")));
            }
        });

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildWebView() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        webView.setVisibility(View.GONE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " MHubVoice/1.0.0");
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                progress.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, R.string.connect_failed, Toast.LENGTH_LONG).show();
                showSetup(getString(R.string.connect_failed));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String host = request.getOrigin() == null ? "" : request.getOrigin().getHost();
                if (sameServerHost(host)) {
                    request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                } else {
                    request.deny();
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                manager.enqueue(request);
                Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        });

        webView.addJavascriptInterface(new VoiceBridge(), "MHubNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsFailed = true;
                failPendingSpeech();
                return;
            }
            ttsReady = true;
            tts.setSpeechRate(1.04f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    runOnUiThread(() -> {
                        if (!matchesSpeech(utteranceId)) return;
                        ttsSpeaking = true;
                        emit("onTtsStart", activeSpeechId);
                    });
                }

                @Override
                public void onDone(String utteranceId) {
                    runOnUiThread(() -> finishSpeech(utteranceId));
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(() -> failSpeech(utteranceId, "系统语音合成失败。"));
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    runOnUiThread(() -> failSpeech(utteranceId, "系统语音合成失败。"));
                }
            });
            if (pendingSpeech != null) {
                String text = pendingSpeech;
                String lang = pendingSpeechLang;
                float rate = pendingSpeechRate;
                int id = pendingSpeechId;
                pendingSpeech = null;
                speakInternal(text, lang, rate, id);
            }
        });
    }

    private void failPendingSpeech() {
        if (pendingSpeech == null) return;
        int id = pendingSpeechId;
        pendingSpeech = null;
        emit("onTtsError", id, "设备没有可用的语音合成引擎。");
    }

    private void speakInternal(String text, String lang, float rate, int id) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            emit("onTtsError", id, "没有可朗读的内容。");
            return;
        }
        if (ttsFailed) {
            emit("onTtsError", id, "设备没有可用的语音合成引擎。");
            return;
        }
        activeSpeechId = id;
        float safeRate = Math.max(0.5f, Math.min(2.0f, rate <= 0f ? 1.0f : rate));
        if (!ttsReady) {
            pendingSpeech = value;
            pendingSpeechLang = lang;
            pendingSpeechRate = safeRate;
            pendingSpeechId = id;
            return;
        }
        Locale locale = Locale.forLanguageTag(lang == null || lang.isEmpty() ? "zh-CN" : lang);
        int available = tts.setLanguage(locale);
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("onTtsError", id, "系统语音合成不支持中文。");
            return;
        }
        tts.setSpeechRate(safeRate);
        int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "mhub-" + id);
        if (result == TextToSpeech.ERROR) {
            emit("onTtsError", id, "系统语音合成启动失败。");
        }
    }

    private boolean matchesSpeech(String utteranceId) {
        return utteranceId != null && utteranceId.equals("mhub-" + activeSpeechId);
    }

    private void finishSpeech(String utteranceId) {
        if (!matchesSpeech(utteranceId)) return;
        ttsSpeaking = false;
        emit("onTtsEnd", activeSpeechId);
    }

    private void failSpeech(String utteranceId, String message) {
        if (!matchesSpeech(utteranceId)) return;
        ttsSpeaking = false;
        emit("onTtsError", activeSpeechId, message);
    }

    private void stopSpeech() {
        if (tts != null) tts.stop();
        pendingSpeech = null;
        if (ttsSpeaking) {
            ttsSpeaking = false;
            emit("onTtsEnd", activeSpeechId);
        }
    }

    private void requestListening(String lang) {
        if (!trustedPage()) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("onError", "设备没有可用的语音识别服务，请安装或启用系统的语音服务。", "service-not-allowed");
            emit("onEnd");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicLang = lang;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MIC_REQUEST);
            return;
        }
        beginListening(lang);
    }

    private void beginListening(String lang) {
        if (!trustedPage()) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
        }
        try {
            recognizer.cancel();
        } catch (Exception ignored) {
            // A previous turn may already have ended.
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null ? "zh-CN" : lang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang == null ? "zh-CN" : lang);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(intent);
            recognizing = true;
        } catch (Exception error) {
            recognizing = false;
            emit("onError", "无法启动语音识别，请重试。", "audio-capture");
            emit("onEnd");
        }
    }

    private String firstResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null || values.isEmpty()) return "";
        String value = values.get(0);
        return value == null ? "" : value.trim();
    }

    private String recognitionError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音失败，请检查麦克风是否被其他应用占用。";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "麦克风权限被拒绝，请在系统设置里允许后重试。";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "语音识别网络暂时不可用，可以改用文字输入。";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有听清，再说一次。";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别正忙，请稍后重试。";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "系统语音识别不支持中文。";
            default:
                return "语音识别失败，请重试或输入文字。";
        }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            recognizing = false;
        }

        @Override
        public void onError(int error) {
            recognizing = false;
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                emit("onEnd");
                return;
            }
            emit("onError", recognitionError(error), error == SpeechRecognizer.ERROR_NO_MATCH ? "no-speech" : "native");
            emit("onEnd");
        }

        @Override
        public void onResults(Bundle results) {
            recognizing = false;
            String text = firstResult(results);
            if (text.isEmpty()) {
                emit("onError", "没有听清，再说一次。", "no-speech");
            } else {
                emit("onFinal", text);
            }
            emit("onEnd");
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (!text.isEmpty()) emit("onInterim", text);
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    private boolean trustedPage() {
        if (!webVisible || webView == null || webView.getUrl() == null) return false;
        try {
            return sameServerHost(new URI(webView.getUrl()).getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private void emit(String name, Object... args) {
        StringBuilder script = new StringBuilder("window.__mhubNativeVoice&&window.__mhubNativeVoice.")
                .append(name).append('(');
        for (int index = 0; index < args.length; index += 1) {
            if (index > 0) script.append(',');
            Object value = args[index];
            if (value instanceof Number || value instanceof Boolean) {
                script.append(value);
            } else {
                script.append(JSONObject.quote(String.valueOf(value)));
            }
        }
        script.append(");");
        callJs(script.toString());
    }

    private void callJs(String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    private final class VoiceBridge {
        @JavascriptInterface
        public boolean isSpeechRecognitionAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public boolean isTtsAvailable() {
            return !ttsFailed;
        }

        @JavascriptInterface
        public void startListening(String lang) {
            runOnUiThread(() -> requestListening(lang == null || lang.isEmpty() ? "zh-CN" : lang));
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> {
                if (recognizer == null || !recognizing) return;
                try {
                    recognizer.stopListening();
                } catch (Exception ignored) {
                    // Recognition may already have ended.
                }
            });
        }

        @JavascriptInterface
        public void cancelListening() {
            runOnUiThread(() -> {
                if (recognizer == null) return;
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                    // Recognition may already have ended.
                }
                recognizing = false;
            });
        }

        @JavascriptInterface
        public void speak(String text, String lang, float rate, int id) {
            runOnUiThread(() -> {
                if (!trustedPage()) return;
                speakInternal(text, lang, rate, id);
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            runOnUiThread(MainActivity.this::stopSpeech);
        }
    }

    private void buildProgress() {
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progress, params);
    }

    private void buildSetup() {
        setupView = new ScrollView(this);
        setupView.setFillViewport(true);
        setupView.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(96), dp(28), dp(40));
        setupView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView mark = new TextView(this);
        mark.setText("♪");
        mark.setTextColor(ACCENT);
        mark.setTextSize(58);
        mark.setGravity(Gravity.CENTER);
        content.addView(mark, new LinearLayout.LayoutParams(dp(88), dp(88)));

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextColor(TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(18);
        content.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.setup_subtitle);
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(30);
        content.addView(subtitle, subtitleParams);

        serverInput = new EditText(this);
        serverInput.setHint(R.string.server_hint);
        serverInput.setHintTextColor(Color.rgb(105, 112, 128));
        serverInput.setTextColor(TEXT);
        serverInput.setTextSize(16);
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        serverInput.setPadding(dp(18), dp(15), dp(18), dp(15));
        serverInput.setBackground(rounded(PANEL, LINE, 14));
        content.addView(serverInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        errorView = new TextView(this);
        errorView.setTextColor(ERROR);
        errorView.setTextSize(13);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = dp(10);
        content.addView(errorView, errorParams);

        Button connect = new Button(this);
        connect.setText(R.string.connect);
        connect.setTextColor(Color.WHITE);
        connect.setTextSize(16);
        connect.setAllCaps(false);
        connect.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        connect.setBackground(rounded(ACCENT, ACCENT, 14));
        connect.setStateListAnimator(null);
        connect.setElevation(0);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(18);
        content.addView(connect, buttonParams);
        connect.setOnClickListener(view -> openServer(serverInput.getText().toString(), true));
        serverInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openServer(serverInput.getText().toString(), true);
                return true;
            }
            return false;
        });

        TextView help = new TextView(this);
        help.setText(R.string.server_help);
        help.setTextColor(MUTED);
        help.setTextSize(12);
        help.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpParams.topMargin = dp(18);
        content.addView(help, helpParams);

        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildSettingsTab() {
        settingsTab = new TextView(this);
        settingsTab.setText(R.string.server_settings_short);
        settingsTab.setTextColor(Color.WHITE);
        settingsTab.setTextSize(16);
        settingsTab.setGravity(Gravity.CENTER);
        settingsTab.setAlpha(0.42f);
        settingsTab.setContentDescription(getString(R.string.server_settings));
        settingsTab.setBackground(rounded(Color.rgb(38, 41, 50), LINE, 18));
        settingsTab.setVisibility(View.GONE);
        settingsTab.setOnClickListener(view -> {
            webView.stopLoading();
            showSetup("");
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(34), dp(62), Gravity.END | Gravity.CENTER_VERTICAL);
        params.rightMargin = dp(6);
        root.addView(settingsTab, params);
    }

    private void openServer(String raw, boolean showErrors) {
        String normalized = normalizeUrl(raw);
        if (normalized == null) {
            if (showErrors) showSetup(getString(R.string.invalid_server));
            return;
        }
        serverUrl = normalized;
        prefs.edit().putString(KEY_SERVER, normalized).apply();
        setupView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        settingsTab.setVisibility(View.VISIBLE);
        webVisible = true;
        webView.loadUrl(normalized);
    }

    private void showSetup(String error) {
        webVisible = false;
        webView.setVisibility(View.GONE);
        settingsTab.setVisibility(View.GONE);
        setupView.setVisibility(View.VISIBLE);
        serverInput.setText(serverUrl == null ? "" : serverUrl);
        serverInput.setSelection(serverInput.getText().length());
        if (error == null || error.isEmpty()) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(error);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = uri.getHost();
        if (sameServerHost(host)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // No browser installed; keep the navigation inside the app.
            return false;
        }
        return true;
    }

    private boolean sameServerHost(String host) {
        if (host == null || serverUrl == null || serverUrl.isEmpty()) return false;
        try {
            String serverHost = new URI(serverUrl).getHost();
            return serverHost != null
                    && (host.equalsIgnoreCase(serverHost)
                    || host.toLowerCase().endsWith("." + serverHost.toLowerCase()));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
            return uri.toString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_REQUEST) return;
        String lang = pendingMicLang == null ? "zh-CN" : pendingMicLang;
        pendingMicLang = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginListening(lang);
            return;
        }
        emit("onError", "麦克风权限被拒绝，请在系统设置里允许后重试。", "not-allowed");
        emit("onEnd");
    }

    @Override
    protected void onPause() {
        if (recognizing && recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
                // Recognition may already have ended.
            }
            recognizing = false;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webVisible) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                showSetup("");
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}

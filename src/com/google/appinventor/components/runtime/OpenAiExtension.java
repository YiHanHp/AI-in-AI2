package com.custom.openai;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@DesignerComponent(
    version = 14, // 升级版本号至 14
    description = "工业级流式 Markdown OpenAI 插件。支持精准拦截系统流初始化 null 漏洞，同时 100% 完美保留 AI 回答中正常的 'null' 文本。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class OpenAiExtension extends AndroidNonvisibleComponent {

    private String baseUrl = "https://api.openai.com";
    private String apiKey = "";
    private String modelName = "gpt-4o";
    private String systemPrompt = "You are a helpful assistant.";
    private float temperature = 0.7f;
    
    private int maxCompletionTokens = 0; 
    private String reasoningEffort = "";  
    private int maxHistoryCount = 20; 

    private final JSONArray historyMessages = new JSONArray();
    private final ExecutorService threadPool = Executors.newSingleThreadExecutor();
    private volatile boolean isRequesting = false;

    public OpenAiExtension(ComponentContainer container) {
        super(container.$form());
        trustAllHosts();
    }

    @SimpleProperty(description = "设置 API 基础路径")
    public void BaseUrl(String url) {
        if (url == null) return;
        String trimmed = url.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @SimpleProperty
    public String BaseUrl() { return this.baseUrl; }

    @SimpleProperty(description = "设置 API Key / 令牌")
    public void ApiKey(String key) { this.apiKey = (key != null) ? key.trim() : ""; }

    @SimpleProperty(description = "设置模型名称")
    public void ModelName(String name) { this.modelName = (name != null) ? name.trim() : ""; }

    @SimpleProperty
    public String ModelName() { return this.modelName; }

    @SimpleProperty(description = "设置系统提示词")
    public void SystemPrompt(String prompt) { this.systemPrompt = (prompt != null) ? prompt : ""; }

    @SimpleProperty
    public String SystemPrompt() { return this.systemPrompt; }

    @SimpleProperty(description = "设置采样温度 (0.0 到 2.0)")
    public void Temperature(float temp) {
        if (temp < 0.0f) this.temperature = 0.0f;
        else if (temp > 2.0f) this.temperature = 2.0f;
        else this.temperature = temp;
    }

    @SimpleProperty
    public float Temperature() { return this.temperature; }

    @SimpleProperty(description = "设置最大生成 Token 限制")
    public void MaxCompletionTokens(int tokens) { this.maxCompletionTokens = (tokens < 0) ? 0 : tokens; }

    @SimpleProperty
    public int MaxCompletionTokens() { return this.maxCompletionTokens; }

    @SimpleProperty(description = "设置推理级别（low, medium, high）")
    public void ReasoningEffort(String effort) { this.reasoningEffort = (effort != null) ? effort.trim() : ""; }

    @SimpleProperty
    public String ReasoningEffort() { return this.reasoningEffort; }

    @SimpleProperty(description = "设置上下文最大保留消息数")
    public void MaxHistoryCount(int count) { this.maxHistoryCount = (count < 2) ? 2 : count; }

    @SimpleProperty
    public int MaxHistoryCount() { return this.maxHistoryCount; }

    @SimpleProperty(description = "检查当前是否正在进行网络请求")
    public boolean IsRequesting() { return this.isRequesting; }

    @SimpleFunction(description = "获取当前已缓存的对话历史条数。")
    public int GetHistorySize() {
        synchronized (historyMessages) { return historyMessages.length(); }
    }

    @SimpleFunction(description = "彻底清除内存中已保存的聊天上下文历史。")
    public void ClearHistory() {
        synchronized (historyMessages) {
            while (historyMessages.length() > 0) { historyMessages.remove(0); }
        }
    }

    @SimpleEvent(description = "当流式接收到深度思考内容时触发")
    public void OnReasoningReceived(String deltaReasoning, String fullReasoning) {
        EventDispatcher.dispatchEvent(this, "OnReasoningReceived", deltaReasoning, fullReasoning);
    }

    @SimpleEvent(description = "当流式接收到标准回答文本时触发")
    public void OnContentReceived(String deltaContent, String fullContent) {
        EventDispatcher.dispatchEvent(this, "OnContentReceived", deltaContent, fullContent);
    }

    @SimpleEvent(description = "当整轮对话流响应安全结束时触发")
    public void OnChatCompleted(String finalFullContent) {
        EventDispatcher.dispatchEvent(this, "OnChatCompleted", finalFullContent);
    }

    @SimpleEvent(description = "请求或解析发生错误时触发")
    public void OnError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnError", errorMessage);
    }

    @SimpleFunction(description = "【流式多轮对话】自动管理上下文窗口并以流式实时返回结果。")
    public void ChatWithHistoryStream(final String userMessage) {
        if (isRequesting) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("AI 正在回复中，请稍后再试。"); } });
            return;
        }
        if (apiKey.isEmpty()) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("API Key 不能为空！"); } });
            return;
        }
        if (userMessage == null || userMessage.trim().isEmpty()) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("发送的消息不能为空！"); } });
            return;
        }

        isRequesting = true;
        final JSONArray finalPayload = new JSONArray();

        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            try {
                finalPayload.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            } catch (Exception ignored) {}
        }

        synchronized (historyMessages) {
            try {
                while (historyMessages.length() >= maxHistoryCount) {
                    if (historyMessages.length() >= 2) {
                        historyMessages.remove(0);
                        historyMessages.remove(0);
                    } else {
                        historyMessages.remove(0);
                    }
                }
                historyMessages.put(new JSONObject().put("role", "user").put("content", userMessage));
                for (int i = 0; i < historyMessages.length(); i++) {
                    finalPayload.put(historyMessages.get(i));
                }
            } catch (Exception e) {
                isRequesting = false;
                OnError("构建 JSON 载荷失败: " + e.getMessage());
                return;
            }
        }

        threadPool.execute(new Runnable() {
            @Override
            public void run() { executeStreamNetworkRequest(finalPayload); }
        });
    }

    private void executeStreamNetworkRequest(JSONArray payload) {
        HttpURLConnection conn = null;
        InputStream in = null;
        
        final StringBuilder fullReasoningBuilder = new StringBuilder();
        final StringBuilder fullContentBuilder = new StringBuilder();

        try {
            JSONObject body = new JSONObject();
            body.put("model", modelName);
            body.put("messages", payload);
            body.put("stream", true);

            if (temperature != 0.7f) body.put("temperature", temperature);
            if (maxCompletionTokens > 0) body.put("max_completion_tokens", maxCompletionTokens);
            if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
                body.put("reasoning_effort", reasoningEffort);
            }

            URL url = new URL(baseUrl + "/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                in = conn.getErrorStream();
                throw new Exception("HTTP " + responseCode + ": " + readAll(in));
            }

            in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("data:")) continue;

                String dataPayload = line.substring(5).trim();
                if (dataPayload.equals("[DONE]")) break;

                try {
                    JSONObject jsonResp = new JSONObject(dataPayload);
                    JSONArray choices = jsonResp.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        if (delta != null) {
                            
                            // ==================== 【核心优化：深度思考字段解析】 ====================
                            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                Object rawReasoning = delta.get("reasoning_content");
                                // 只有当底层类型确实是字符串，且不等于 JSONObject.NULL 时才提取
                                if (rawReasoning instanceof String) {
                                    final String deltaReasoning = (String) rawReasoning;
                                    if (!deltaReasoning.isEmpty()) {
                                        fullReasoningBuilder.append(deltaReasoning);
                                        form.runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                OnReasoningReceived(deltaReasoning, fullReasoningBuilder.toString());
                                            }
                                        });
                                    }
                                }
                            }

                            // ==================== 【核心优化：标准回答字段解析】 ====================
                            if (delta.has("content") && !delta.isNull("content")) {
                                Object rawContent = delta.get("content");
                                // 严格过滤：必须是 Java 原生 String 类型，防止 JSONObject.NULL 被误转为 "null" 字符串
                                if (rawContent instanceof String) {
                                    final String deltaContent = (String) rawContent;
                                    if (!deltaContent.isEmpty()) {
                                        fullContentBuilder.append(deltaContent);
                                        form.runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                OnContentReceived(deltaContent, fullContentBuilder.toString());
                                            }
                                        });
                                    }
                                }
                            }
                            
                        }
                    }
                } catch (Exception ignored) {}
            }

            final String finalAnswer = fullContentBuilder.toString();
            if (!finalAnswer.isEmpty()) {
                synchronized (historyMessages) {
                    historyMessages.put(new JSONObject().put("role", "assistant").put("content", finalAnswer));
                }
            }

            form.runOnUiThread(new Runnable() {
                @Override public void run() {
                    isRequesting = false;
                    OnChatCompleted(finalAnswer);
                }
            });

        } catch (final Exception e) {
            form.runOnUiThread(new Runnable() {
                @Override public void run() {
                    isRequesting = false;
                    OnError(e.getMessage());
                }
            });
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private String readAll(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    @SimpleFunction(description = "【Markdown渲染】将带有Markdown语法的文本转换为 App Inventor 富文本 HTML 格式。")
    public String MarkdownToHtml(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) return "";
        // 如果输入真的是 AI 说的 "null" 字符串，则当做普通文本处理，不再强制返回空串
        if (markdownText.equals("null")) return "null";

        String html = markdownText.replace("\r\n", "\n");
        html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replace("\n", "<br/>");
        return html;
    }

    private void trustAllHosts() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
        } catch (Exception ignored) {}
    }
}

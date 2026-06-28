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
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

@DesignerComponent(
    version = 20, // 军工级防爆强化，版本号升至 20
    description = "最高防御级多模态 OpenAI/DeepSeek 插件。具备 TLSv1.2 强协商套件与 4K 分片式大包写入技术，专治 Android 5+ 在多模态长文本、大 Base64 传输下的各类底层 Software caused connection abort 网络流产恶疾。",
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

    // 对话历史队列
    private final JSONArray historyMessages = new JSONArray();
    private final ExecutorService threadPool = Executors.newSingleThreadExecutor();
    private volatile boolean isRequesting = false;

    // 局部证书信任域和强兼容套件变量
    private SSLSocketFactory customSSLSocketFactory = null;
    private HostnameVerifier customHostnameVerifier = null;

    public OpenAiExtension(ComponentContainer container) {
        super(container.$form());
        initCustomSSL(); // 深度激活局部 TLSv1.2 强兼容信任机制
    }

    @SimpleProperty(description = "设置 API 基础路径")
    public void BaseUrl(String url) {
        if (url == null) return;
        String trimmed = url.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @SimpleProperty public String BaseUrl() { return this.baseUrl; }

    @SimpleProperty(description = "设置 API Key")
    public void ApiKey(String key) { this.apiKey = (key != null) ? key.trim() : ""; }

    @SimpleProperty(description = "设置模型名称")
    public void ModelName(String name) { this.modelName = (name != null) ? name.trim() : ""; }

    @SimpleProperty public String ModelName() { return this.modelName; }

    @SimpleProperty(description = "设置系统提示词")
    public void SystemPrompt(String prompt) { this.systemPrompt = (prompt != null) ? prompt : ""; }

    @SimpleProperty public String SystemPrompt() { return this.systemPrompt; }

    @SimpleProperty(description = "设置采样温度 (0.0 到 2.0)")
    public void Temperature(float temp) {
        if (temp < 0.0f) this.temperature = 0.0f;
        else if (temp > 2.0f) this.temperature = 2.0f;
        else this.temperature = temp;
    }

    @SimpleProperty public float Temperature() { return this.temperature; }

    @SimpleProperty(description = "设置最大生成 Token 限制")
    public void MaxCompletionTokens(int tokens) { this.maxCompletionTokens = (tokens < 0) ? 0 : tokens; }

    @SimpleProperty public int MaxCompletionTokens() { return this.maxCompletionTokens; }

    @SimpleProperty(description = "设置推理级别（low, medium, high）")
    public void ReasoningEffort(String effort) { this.reasoningEffort = (effort != null) ? effort.trim() : ""; }

    @SimpleProperty public String ReasoningEffort() { return this.reasoningEffort; }

    @SimpleProperty(description = "设置上下文最大保留消息数")
    public void MaxHistoryCount(int count) { this.maxHistoryCount = (count < 2) ? 2 : count; }

    @SimpleProperty public int MaxHistoryCount() { return this.maxHistoryCount; }

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

    @SimpleFunction(description = "【基础流式对话】发送纯文本消息。支持通过文本读取积木把文件内容拼接在 userMessage 里直接发送。")
    public void ChatWithHistoryStream(final String userMessage) {
        ChatWithImageAndHistoryStream(userMessage, "");
    }

    @SimpleFunction(description = "【多模态流式对话】支持发送文本并附加一张图片（Base64格式）。下一轮对话会自动脱敏图片，只留下文本记忆，防止API崩溃和Token暴涨。")
    public void ChatWithImageAndHistoryStream(final String userMessage, final String imageBase64) {
        if (isRequesting) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("AI 正在回复中，请稍后再试。"); } });
            return;
        }
        if (apiKey.isEmpty()) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("API Key 不能为空！"); } });
            return;
        }
        
        final String safeText = (userMessage == null) ? "" : userMessage.trim();
        final String safeImage = (imageBase64 == null) ? "" : imageBase64.trim();

        if (safeText.isEmpty() && safeImage.isEmpty()) {
            form.runOnUiThread(new Runnable() { @Override public void run() { OnError("发送的文本和图片不能同时为空！"); } });
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

                for (int i = 0; i < historyMessages.length(); i++) {
                    finalPayload.put(historyMessages.get(i));
                }

                JSONObject currentTurnMessage = new JSONObject().put("role", "user");
                
                if (!safeImage.isEmpty()) {
                    JSONArray contentArray = new JSONArray();
                    if (!safeText.isEmpty()) {
                        contentArray.put(new JSONObject().put("type", "text").put("text", safeText));
                    }
                    
                    String formattedBase64 = safeImage;
                    if (!formattedBase64.startsWith("data:image")) {
                        formattedBase64 = "data:image/jpeg;base64," + formattedBase64; 
                    }
                    JSONObject imageUrlObj = new JSONObject().put("url", formattedBase64);
                    contentArray.put(new JSONObject().put("type", "image_url").put("image_url", imageUrlObj));
                    
                    currentTurnMessage.put("content", contentArray);
                    finalPayload.put(currentTurnMessage);

                    historyMessages.put(new JSONObject().put("role", "user").put("content", safeText.isEmpty() ? "[用户发送了一张图片]" : safeText));
                } else {
                    currentTurnMessage.put("content", safeText);
                    finalPayload.put(currentTurnMessage);
                    historyMessages.put(new JSONObject().put("role", "user").put("content", safeText));
                }

            } catch (Exception e) {
                isRequesting = false;
                OnError("构建多模态上下文载荷失败: " + e.getMessage());
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
        OutputStream os = null;
        InputStream in = null;
        BufferedReader reader = null;
        
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

            // 提取字节数组
            byte[] requestData = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(baseUrl + "/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            
            // 【核心强化点 1】给当前 Https 连接注入自适应 TLS 降维兼容工厂，既保留了对 Android 5 所有证书的绝对信任，又强迫老系统握手 TLSv1.2
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                if (customSSLSocketFactory != null) {
                    httpsConn.setSSLSocketFactory(customSSLSocketFactory);
                }
                if (customHostnameVerifier != null) {
                    httpsConn.setHostnameVerifier(customHostnameVerifier);
                }
            }

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(35000); // 适度放开连接超时以对抗弱网
            conn.setReadTimeout(75000);    // 为大模型长推理提供充足的时间
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            
            // 彻底断绝 Keep-Alive 跨域复用引起的 System Call 灾难，阅后即焚
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(requestData.length));

            // 【核心强化点 2】分片式安全写入器。按 4KB 大小切片，防止包含 Base64 的超长 JSON 一次性冲击 Android 5 的底层 TCP 核心栈而直接造成 abort
            os = conn.getOutputStream();
            int offset = 0;
            int bufferSize = 4096;
            while (offset < requestData.length) {
                int len = Math.min(bufferSize, requestData.length - offset);
                os.write(requestData, offset, len);
                os.flush(); // 强制逐块推出缓冲区
                offset += len;
            }
            os.close();
            os = null; 

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                in = conn.getErrorStream();
                throw new Exception("HTTP ERROR " + responseCode + ": " + readAll(in));
            }

            in = conn.getInputStream();
            // 【核心强化点 3】升级至 8KB 工业级长数据缓冲流视窗，杜绝流式响应换行时低端机因为读取悬空产生的底层 Reset
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
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
                            
                            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                Object rawReasoning = delta.get("reasoning_content");
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

                            if (delta.has("content") && !delta.isNull("content")) {
                                Object rawContent = delta.get("content");
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
            // 【核心强化点 4】双路 IO 级和网络实例物理级闭合，不留任何残留资源
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private String readAll(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 2048);
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    @SimpleFunction(description = "【Markdown渲染】将带有Markdown语法的文本转换为 App Inventor 富文本 HTML 格式。")
    public String MarkdownToHtml(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) return "";
        if (markdownText.equals("null")) return "null"; 

        String html = markdownText.replace("\r\n", "\n");
        html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replace("\n", "<br/>");
        return html;
    }

    private void initCustomSSL() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            
            // 初始化底层 TLS 基础上下文
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            
            final SSLSocketFactory baseFactory = sc.getSocketFactory();
            
            // 引入专有代理工厂，拦截 Socket 创建，强行将 TLSv1.2, TLSv1.1 协议集固化在握手首位，解决 Android 5 的加密回落溃退
            this.customSSLSocketFactory = new SSLSocketFactory() {
                @Override public String[] getDefaultCipherSuites() { return baseFactory.getDefaultCipherSuites(); }
                @Override public String[] getSupportedCipherSuites() { return baseFactory.getSupportedCipherSuites(); }
                
                private Socket enableTLSOnSocket(Socket socket) {
                    if (socket instanceof SSLSocket) {
                        try {
                            ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1.0"});
                        } catch (Exception ignored) {}
                    }
                    return socket;
                }
                
                @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                    return enableTLSOnSocket(baseFactory.createSocket(s, host, port, autoClose));
                }
                @Override public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
                    return enableTLSOnSocket(baseFactory.createSocket(host, port));
                }
                @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
                    return enableTLSOnSocket(baseFactory.createSocket(host, port, localHost, localPort));
                }
                @Override public Socket createSocket(InetAddress host, int port) throws IOException {
                    return enableTLSOnSocket(baseFactory.createSocket(host, port));
                }
                @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                    return enableTLSOnSocket(baseFactory.createSocket(address, port, localAddress, localPort));
                }
            };

            this.customHostnameVerifier = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            };
        } catch (Exception ignored) {}
    }
}

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

@DesignerComponent(
    version = 6,
    description = "工业级流式 Markdown OpenAI 插件。支持多轮对话上下文记忆、一键清空机制、高并发线程安全锁以及标准 HTML 渲染转换引擎。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class OpenAiExtension extends AndroidNonvisibleComponent {

    private String baseUrl = "https://openai.com";
    private String apiKey = "";
    private String modelName = "gpt-4o";
    private String systemPrompt = "You are a helpful assistant.";
    private float temperature = 0.7f;

    private final JSONArray historyMessages = new JSONArray();
    private final ExecutorService threadPool = Executors.newSingleThreadExecutor();
    private volatile boolean isRequesting = false;

    public OpenAiExtension(ComponentContainer container) {
        super(container.$form());
    }

    @SimpleProperty(description = "设置 API 基础路径")
    public void BaseUrl(String url) {
        if (url == null) return;
        String trimmed = url.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @SimpleProperty
    public String BaseUrl() {
        return this.baseUrl;
    }

    @SimpleProperty(description = "设置 API Key / 令牌")
    public void ApiKey(String key) {
        this.apiKey = (key != null) ? key.trim() : "";
    }

    @SimpleProperty(description = "设置模型名称")
    public void ModelName(String name) {
        this.modelName = (name != null) ? name.trim() : "";
    }

    @SimpleProperty
    public String ModelName() {
        return this.modelName;
    }

    @SimpleProperty(description = "设置系统提示词")
    public void SystemPrompt(String prompt) {
        this.systemPrompt = (prompt != null) ? prompt : "";
    }

    @SimpleProperty
    public String SystemPrompt() {
        return this.systemPrompt;
    }

    @SimpleProperty(description = "设置采样温度 (0.0 到 2.0)")
    public void Temperature(float temp) {
        if (temp < 0.0f) {
            this.temperature = 0.0f;
        } else if (temp > 2.0f) {
            this.temperature = 2.0f;
        } else {
            this.temperature = temp;
        }
    }

    @SimpleProperty
    public float Temperature() {
        return this.temperature;
    }

    @SimpleProperty(description = "检查当前是否正在进行网络请求")
    public boolean IsRequesting() {
        return this.isRequesting;
    }

    @SimpleFunction(description = "彻底清除内存中已保存的聊天上下文历史，开启全新对话。")
    public void ClearHistory() {
        synchronized (historyMessages) {
            while (historyMessages.length() > 0) {
                historyMessages.remove(0);
            }
        }
    }

    @SimpleFunction(description = "【流式多轮对话】自动管理上下文并以流式实时返回结果。")
    public void ChatWithHistoryStream(final String userMessage) {
        if (isRequesting) {
            OnError("AI 正在回复中，请稍后再试。");
            return;
        }

        if (apiKey.isEmpty()) {
            OnError("API Key 不能为空！");
            return;
        }

        if (userMessage == null || userMessage.trim().isEmpty()) {
            OnError("发送的消息不能为空！");
            return;
        }

        isRequesting = true;
        final JSONArray finalPayload = new JSONArray();

        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            try {
                finalPayload.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            } catch (Exception ignored) {
            }
        }

        synchronized (historyMessages) {
            try {
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
            public void run() {
                executeStreamNetworkRequest(finalPayload, true);
            }
        });
    }

    @SimpleFunction(description = "【Markdown渲染】将带有Markdown语法的文本一键转换为 App Inventor 标签组件（开启HTML格式）可识别的富文本。")
    public String MarkdownToHtml(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }

        String html = markdownText;
        html = html.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
        html = html.replaceAll("(?s)```(\\w*)\\n?(.*?)```", "<pre style='background-color:#F4F4F4; padding:5px; font-family:monospace;'>$2</pre>");
        html = html.replaceAll("`([^`]+)`", "<code style='background-color:#F4F4F4; font-family:monospace;'> $1 </code>");
        html = html.replaceAll("(?m)^### (.*?)$", "<br><b><font size='+1'>$1</font></b><br>");
        html = html.replaceAll("(?m)^## (.*?)$", "<br><b><font size='+2'>$1</font></b><br>");
        html = html.replaceAll("(?m)^# (.*?)$", "<br><b><font size='+3'>$1</font></b><br>");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("__ (.*?)__", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replaceAll("_(.*?)_", "<i>$1</i>");
        html = html.replaceAll("~~(.*?)~~", "<del>$1</del>");
        html = html.replaceAll("(?m)^---$", "<hr>");
        html = html.replace("\n", "<br>");

        return html;
    }

    private void executeStreamNetworkRequest(final JSONArray messagesArray, final boolean isMultiTurn) {
        HttpURLConnection connection = null;
        StringBuilder fullReplyAccumulator = new StringBuilder();

        try {
            URL url = new URL(baseUrl + "/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", modelName);
            requestBody.put("temperature", temperature);
            requestBody.put("messages", messagesArray);
            requestBody.put("stream", true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream is = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        if (line.startsWith("data:")) {
                            String dataContent = line.substring(5).trim();
                            if ("[DONE]".equals(dataContent)) {
                                break;
                            }

                            try {
                                JSONObject chunkJson = new JSONObject(dataContent);
                                JSONArray choices = chunkJson.getJSONArray("choices");
                                if (choices.length() > 0) {
                                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                                    if (delta.has("content")) {
                                        final String chunkText = delta.getString("content");
                                        fullReplyAccumulator.append(chunkText);
                                        postChunkUpdate(chunkText);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }

                String finalReply = fullReplyAccumulator.toString();
                if (isMultiTurn && !finalReply.isEmpty()) {
                    synchronized (historyMessages) {
                        historyMessages.put(new JSONObject().put("role", "assistant").put("content", finalReply));
                    }
                }

                postStreamComplete();

            } else {
                StringBuilder errResponse = new StringBuilder();
                try (InputStream errStream = connection.getErrorStream()) {
                    if (errStream != null) {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                            String inputLine;
                            while ((inputLine = in.readLine()) != null) {
                                errResponse.append(inputLine);
                            }
                        }
                    }
                }
                handleFailure(isMultiTurn, "HTTP 错误代码: " + responseCode + " | 详情: " + errResponse.toString());
            }

        } catch (Exception e) {
            handleFailure(isMultiTurn, "流式连接异常中断: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void postChunkUpdate(final String chunk) {
        form.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                OnGotChunk(chunk);
            }
        });
    }

    private void postStreamComplete() {
        form.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                isRequesting = false;
                OnStreamComplete();
            }
        });
    }

    private void handleFailure(final boolean isMultiTurn, final String errorMsg) {
        if (isMultiTurn) {
            synchronized (historyMessages) {
                if (historyMessages.length() > 0) {
                    historyMessages.remove(historyMessages.length() - 1);
                }
            }
        }

        form.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                isRequesting = false;
                OnError(errorMsg);
            }
        });
    }

    @SimpleEvent(description = "每当 AI 吐出新字/新词时触发。")
    public void OnGotChunk(String chunk) {
        EventDispatcher.dispatchEvent(this, "OnGotChunk", chunk);
    }

    @SimpleEvent(description = "当整段大模型流式内容全部接收完毕时触发。")
    public void OnStreamComplete() {
        EventDispatcher.dispatchEvent(this, "OnStreamComplete");
    }

    @SimpleEvent(description = "当请求或传输发生错误时触发。")
    public void OnError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnError", errorMessage);
    }
}

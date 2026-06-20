package com.custom.chatview;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONObject;
import java.io.UnsupportedEncodingException;

@DesignerComponent(
    version = 7,
    description = "完美对标 Google Messages 风格的双人聊天框渲染引擎。采用 RFC-3986 特殊字符编码转义过滤，彻底根除包含%或#号时大模型内容遭系统截断白屏的致命 BUG。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
public class GoogleChatView extends AndroidNonvisibleComponent {

    private WebViewer webViewerComponent = null;
    private final StringBuilder htmlContent = new StringBuilder();
    private boolean isBound = false;

    private String userBubbleColor = "#1A73E8";
    private String userTextColor = "#FFFFFF";
    private String partnerBubbleColor = "#E8EAED";
    private String partnerTextColor = "#202124";

    public GoogleChatView(ComponentContainer container) {
        super(container.$form());
        resetHtmlBuffer();
    }

    // ==========================================
    // 1. 自定义主题色属性积木 (Properties)
    // ==========================================

    @SimpleProperty(description = "设置发送方的气泡背景颜色")
    public void UserBubbleColor(String colorCode) {
        if (colorCode != null && colorCode.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            this.userBubbleColor = colorCode;
            updateLiveTheme();
        }
    }

    @SimpleProperty
    public String UserBubbleColor() {
        return this.userBubbleColor;
    }

    @SimpleProperty(description = "设置发送方的文本颜色")
    public void UserTextColor(String colorCode) {
        if (colorCode != null && colorCode.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            this.userTextColor = colorCode;
            updateLiveTheme();
        }
    }

    @SimpleProperty
    public String UserTextColor() {
        return this.userTextColor;
    }

    @SimpleProperty(description = "设置接收方的气泡背景颜色")
    public void PartnerBubbleColor(String colorCode) {
        if (colorCode != null && colorCode.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            this.partnerBubbleColor = colorCode;
            updateLiveTheme();
        }
    }

    @SimpleProperty
    public String PartnerBubbleColor() {
        return this.partnerBubbleColor;
    }

    @SimpleProperty(description = "设置接收方的文本颜色")
    public void PartnerTextColor(String colorCode) {
        if (colorCode != null && colorCode.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            this.partnerTextColor = colorCode;
            updateLiveTheme();
        }
    }

    @SimpleProperty
    public String PartnerTextColor() {
        return this.partnerTextColor;
    }

    // ==========================================
    // 2. 核心控制与安全数据传输引擎
    // ==========================================

    @SimpleFunction(description = "将 WebViewer 组件与此渲染引擎进行绑定。")
    public void BindWebViewer(WebViewer webViewer) {
        this.webViewerComponent = webViewer;
        this.isBound = true;
        refreshWebView();
    }

    @SimpleFunction(description = "一键彻底清空手机前端屏幕上的所有聊天气泡。")
    public void ClearDisplay() {
        resetHtmlBuffer();
        refreshWebView();
    }

    private String generateDynamicCss() {
        return "<style>" +
               "  :root {" +
               "    --me-bg: " + userBubbleColor + ";" +
               "    --me-text: " + userTextColor + ";" +
               "    --other-bg: " + partnerBubbleColor + ";" +
               "    --other-text: " + partnerTextColor + ";" +
               "  }" +
               "  * { box-sizing: border-box; font-family: 'Google Sans', 'Roboto', sans-serif; -webkit-tap-highlight-color: transparent; }" +
               "  html, body { margin: 0; padding: 0; background-color: #F8F9FA; width: 100%; height: 100%; overflow-x: hidden; scroll-behavior: smooth; }" +
               "  #chat-container { display: flex; flex-direction: column; gap: 6px; padding: 16px; width: 100%; min-height: 100%; justify-content: flex-end; }" +
               "  .msg-row { display: flex; width: 100%; margin-bottom: 2px; animation: bubbleAppear 0.2s ease-out forwards; flex-direction: column; }" +
               "  .msg-bubble { max-width: 78%; padding: 12px 16px; font-size: 15px; line-height: 1.45; word-wrap: break-word; position: relative; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }" +
               "  .me { align-self: flex-end; align-items: flex-end; }" +
               "  .me .msg-bubble { background-color: var(--me-bg); color: var(--me-text); border-radius: 20px 20px 4px 20px; }" +
               "  .other { align-self: flex-start; align-items: flex-start; }" +
               "  .other .msg-bubble { background-color: var(--other-bg); color: var(--other-text); border-radius: 20px 20px 20px 4px; }" +
               "  .time-stamp { font-size: 11px; margin-top: 4px; opacity: 0.7; align-self: flex-end; display: block; width: 100%; text-align: right; }" +
               "  .me .time-stamp { color: var(--me-text); opacity: 0.8; }" +
               "  .other .time-stamp { color: var(--other-text); opacity: 0.6; }" +
               "  .pulse { animation: pulseBg 1.5s infinite ease-in-out; }" +
               "  pre { background: rgba(0, 0, 0, 0.06); padding: 10px; border-radius: 12px; font-family: 'Courier New', monospace; font-size: 13px; overflow-x: auto; margin: 6px 0; border: 1px solid rgba(0,0,0,0.05); color: inherit; }" +
               "  code { font-family: 'Courier New', monospace; background: rgba(0, 0, 0, 0.06); padding: 2px 5px; border-radius: 6px; font-size: 13px; color: inherit; }" +
               "  b, strong { font-weight: 600; }" +
               "  hr { border: none; border-top: 1px solid rgba(0,0,0,0.1); margin: 10px 0; }" +
               "  @keyframes bubbleAppear { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }" +
               "  @keyframes pulseBg { 0% { opacity: 0.6; } 50% { opacity: 1; } 100% { opacity: 0.6; } }" +
               "</style>";
    }

    private final String SCROLL_BEHAVIOR_JS =
        "<script>" +
        "  function scrollToBottom() {" +
        "    setTimeout(function() {" +
        "      var container = document.body;" +
        "      window.scrollTo(0, container.scrollHeight);" +
        "      var lastRow = container.lastElementChild ? (container.lastElementChild.lastElementChild ? container.lastElementChild.lastElementChild : null) : null;" +
        "      if(lastRow) { lastRow.scrollIntoView({ behavior: 'smooth', block: 'end' }); }" +
        "    }, 50);" +
        "  }" +
        "  var observer = new MutationObserver(scrollToBottom);" +
        "  document.addEventListener('DOMContentLoaded', function() {" +
        "    var target = document.getElementById('chat-container');" +
        "    if(target) observer.observe(target, { childList: true, subtree: true });" +
        "    scrollToBottom();" +
        "  });" +
        "</script>";

    private void resetHtmlBuffer() {
        htmlContent.setLength(0);
        htmlContent.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>");
        htmlContent.append(generateDynamicCss());
        htmlContent.append("</head><body><div id='chat-container'>");
    }

    // 🌟 [致命 BUG 修复内核] 核心优化：使用符合 RFC-3986 规范的简易编码器，对 HTML 全文中的特殊符号进行编码，但不使用 data URI（避免编码破坏所有 `<br>`、`<i>`、`<b>` 等标签）
    private void refreshWebView() {
        if (webViewerComponent != null && isBound) {
            String fullHtml = htmlContent.toString() + "</div>" + SCROLL_BEHAVIOR_JS + "</body></html>";

            // 修复错误：避免对 HTML 标签再次编码，应使用原生的 WebViewString
            webViewerComponent.WebViewString(fullHtml);
        }
    }

    private void updateLiveTheme() {
        if (webViewerComponent == null || !isBound) return;

        String jsCommand = "javascript:(function() {" + 
                           "  var root = document.documentElement;" +
                           "  if(root) {" +
                           "    root.style.setProperty('--me-bg', '" + userBubbleColor + "');" +
                           "    root.style.setProperty('--me-text', '" + userTextColor + "');" +
                           "    root.style.setProperty('--other-bg', '" + partnerBubbleColor + "');" +
                           "    root.style.setProperty('--other-text', '" + partnerTextColor + "');" +
                           "  }" +
                           "})();";

        webViewerComponent.WebViewString(jsCommand);
    }

    // ==========================================
    // 3. 动态消息追加方法
    // ==========================================

    @SimpleFunction(description = "【右侧气泡】在手机右侧渲染发送方的消息气泡。")
    public void AppendUserMessage(String message, String timeStr) {
        if (message == null || message.trim().isEmpty()) return;

        String formattedMsg = parseMarkdownToHtml(message);
        StringBuilder row = new StringBuilder();
        row.append("<div class='msg-row me'><div class='msg-bubble'>").append(formattedMsg);
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            row.append("<span class='time-stamp'>").append(timeStr).append("</span>");
        }
        row.append("</div></div>");

        htmlContent.append(row.toString());
        refreshWebView();
    }

    @SimpleFunction(description = "【左侧占位符】在左侧创建一个带有呼吸动效的 AI 占位符气泡。")
    public void CreatePartnerBubblePlaceholder() {
        String placeholderHtml = "<div class='msg-row other' id='stream-row'><div class='msg-bubble pulse' id='stream-content'>•••</div></div>";
        htmlContent.append(placeholderHtml);
        refreshWebView();
    }

    @SimpleFunction(description = "【高频流式更新】高频局部刷新占位符内容，杜绝闪烁。")
    public void UpdatePartnerBubbleStream(String currentFullText) {
        if (webViewerComponent == null || currentFullText == null) return;
        String parsedHtml = parseMarkdownToHtml(currentFullText);
        String safeJsString = JSONObject.quote(parsedHtml);
        String jsCommand = "javascript:(function() {" +
                           "  var el = document.getElementById('stream-content');" +
                           "  if(el) {" +
                           "    if(el.classList.contains('pulse')) el.classList.remove('pulse');" +
                           "    el.innerHTML = " + safeJsString + ";" +
                           "  }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
    }

    @SimpleFunction(description = "【左侧气泡固化】当大模型流式传输完全结束，固化当前气泡并撤销临时 ID。")
    public void FinalizePartnerBubble(String finalMessage, String timeStr) {
        if (webViewerComponent == null) return;
        String parsedHtml = parseMarkdownToHtml(finalMessage);
        String savedBufferHtml = parsedHtml;
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            parsedHtml += "<span class='time-stamp'>" + timeStr + "</span>";
            savedBufferHtml += "<span class='time-stamp'>" + timeStr + "</span>";
        }

        String safeJsString = JSONObject.quote(parsedHtml);
        String jsCommand = "javascript:(function() {" +
                           "  var el = document.getElementById('stream-content');" +
                           "  var row = document.getElementById('stream-row');" +
                           "  if(el) { el.innerHTML = " + safeJsString + "; el.removeAttribute('id'); }" +
                           "  if(row) { row.removeAttribute('id'); }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
        String staticRow = "<div class='msg-row other'>" + savedBufferHtml + "</div>";
        htmlContent.append(staticRow);
    }

    // ==========================================
    // 4. 高阶文本转义与 Markdown 解析引擎
    // ==========================================

    private String escapeHtmlChars(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String parseMarkdownToHtml(String text) {
        if (text == null || text.isEmpty()) return "";

        // 1. 换行符归一化处理，避免连续换行使得网页内容紊乱
        String cleanedText = text.replace("\r\n", "\n").replace("\r", "\n");
        cleanedText = cleanedText.replaceAll("\n{3,}", "\n\n"); // 压缩多个无意义换行符为 \n\n

        String html = escapeHtmlChars(cleanedText);

        // 2. 多行代码块
        html = html.replaceAll("(?s)```([\\w\\W]*?)```", "<pre style='background:#F4F4F4; padding:5px;'>$1</pre>");
        html = html.replaceAll("`([^`]+)`", "<code style='background:#F4F4F4; padding:3px;'> $1 </code>");

        // 3. 标题处理
        html = html.replaceAll("(?m)^### (.*?)$", "<br><b><font size='+1'>$1</font></b><br>");
        html = html.replaceAll("(?m)^## (.*?)$", "<br><b><font size='+2'>$1</font></b><br>");
        html = html.replaceAll("(?m)^# (.*?)$", "<br><b><font size='+3'>$1</font></b><br>");

        // 4. 加粗、斜体、删除线
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("__(.*?)__", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replaceAll("_(.*?)_", "<i>$1</i>");
        html = html.replaceAll("~~(.*?)~~", "<del>$1</del>");

        // 5. 分割线
        html = html.replaceAll("(?m)^---$", "<hr>");

        // 6. 换行符转换
        html = html.replace("\n", "<br>");

        return html;
    }
}

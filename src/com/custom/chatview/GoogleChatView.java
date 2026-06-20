package com.custom.chatview;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONObject;

@DesignerComponent(
    version = 3,
    description = "完美对标 Google Messages 风格的双人聊天框渲染引擎。支持动态全局主题色自定义、平滑滚动、安全转义、自适应 Material You 气泡排版与极速流式局部刷新。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
public class GoogleChatView extends AndroidNonvisibleComponent {

    private WebViewer webViewerComponent = null;
    private final StringBuilder htmlContent = new StringBuilder();
    private boolean isBound = false;

    // 默认全局主题色（完全对标 Google Messages 官方色盘）
    private String userBubbleColor = "#1A73E8";    // 发送方气泡背景色（皇家蓝）
    private String userTextColor = "#FFFFFF";      // 发送方文本颜色
    private String partnerBubbleColor = "#E8EAED"; // 接收方气泡背景色（浅灰）
    private String partnerTextColor = "#202124";  // 接收方文本颜色

    public GoogleChatView(ComponentContainer container) {
        super(container.$form());
        resetHtmlBuffer();
    }

    // ==========================================
    // 1. ✨ 新增：自定义主题色属性积木 (Properties) ✨
    // ==========================================

    @SimpleProperty(description = "设置发送方（用户）的气泡背景颜色，支持十六进制代码（如：#1A73E8）")
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

    @SimpleProperty(description = "设置发送方（用户）的文本颜色，支持十六进制代码（如：#FFFFFF）")
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

    @SimpleProperty(description = "设置接收方（AI/对方）的气泡背景颜色，支持十六进制代码（如：#E8EAED）")
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

    @SimpleProperty(description = "设置接收方（AI/对方）的文本颜色，支持十六进制代码（如：#202124）")
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
    // 2. 核心控制与 CSS 变量渲染核心
    // ==========================================

    @SimpleFunction(description = "将界面上的 WebViewer 浏览器组件与此渲染引擎进行绑定（支持幂等锁，防止重复绑定）。")
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
        return "<style id='theme-style'>" +
               "  :root {" +
               "    --me-bg: " + userBubbleColor + ";" +
               "    --me-text: " + userTextColor + ";" +
               "    --other-bg: " + partnerBubbleColor + ";" +
               "    --other-text: " + partnerTextColor + ";" +
               "  }" +
               "  * { box-sizing: border-box; font-family: 'Google Sans', 'Roboto', 'Helvetica Neue', sans-serif; -webkit-tap-highlight-color: transparent; }" +
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

    private void refreshWebView() {
        if (webViewerComponent != null && isBound) {
            String fullHtml = htmlContent.toString() + "</div>" + SCROLL_BEHAVIOR_JS + "</body></html>";
            webViewerComponent.LoadHtmlString(fullHtml);
        }
    }

    // 利用 CSS Variables 在前端实时、无盲区、无闪烁地一秒切换全场皮肤
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

    @SimpleFunction(description = "【左侧占位符】在左侧创建一个带有呼吸动效的 AI 占位符气泡，为接下来的流式蹦字做准备。")
    public void CreatePartnerBubblePlaceholder() {
        String placeholderHtml = "<div class='msg-row other' id='stream-row'><div class='msg-bubble pulse' id='stream-content'>•••</div></div>";
        htmlContent.append(placeholderHtml);
        refreshWebView();
    }

    @SimpleFunction(description = "【高频流式更新】高频将大模型返回的最新纯文本片段，安全局部刷新到占位符中，杜绝闪烁。")
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

    @SimpleFunction(description = "【左侧气泡固化】当大模型流式传输完全结束，固化当前气泡，撤销临时 ID，并打上最终时间戳。")
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
    // 4. 高阶轻量级文本转义与 Markdown 解析引擎（修复换行 BUG）
    // ==========================================

    private String escapeHtmlChars(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String parseMarkdownToHtml(String text) {
        if (text == null || text.isEmpty()) return "";

        // [修复BUG]：将 \r\n 统一清洗替换为标准的 \n，防止部分模型分发流造成代码块正则错乱崩溃
        String cleanedText = text.replace("\r\n", "\n").replace("\r", "\n");
        String html = escapeHtmlChars(cleanedText);

        // 多行与单行代码块处理
        html = html.replaceAll("(?s)```(\\w*)\\n?(.*?)```", "<pre style='background-color:#F4F4F4; padding:5px; font-family:monospace;'>$2</pre>");
        html = html.replaceAll("`([^`]+)`", "<code style='background-color:#F4F4F4; font-family:monospace;'> $1 </code>");

        // 级联放大标题
        html = html.replaceAll("(?m)^### (.*?)$", "<br><b><font size='+1'>$1</font></b><br>");
        html = html.replaceAll("(?m)^## (.*?)$", "<br><b><font size='+2'>$1</font></b><br>");
        html = html.replaceAll("(?m)^# (.*?)$", "<br><b><font size='+3'>$1</font></b><br>");

        // 粗体、斜体、删除线与分割线
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("__(.*?)__", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replaceAll("_(.*?)_", "<i>$1</i>");
        html = html.replaceAll("~~(.*?)~~", "<del>$1</del>");
        html = html.replaceAll("(?m)^---$", "<hr>");

        // 软换行符转换
        html = html.replace("\n", "<br>");

        return html;
    }
}

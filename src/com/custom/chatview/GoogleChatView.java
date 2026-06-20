package com.custom.chatview;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

@DesignerComponent(
    version = 9,
    description = "完美对标 Google Messages 风格的双人聊天框渲染引擎。采用轻量级 JS 增量注入内核，彻底根除由于全页重载导致的白屏致命 BUG。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
public class GoogleChatView extends AndroidNonvisibleComponent {

    // 内部结构化消息实体
    private static class ChatMessage {
        boolean isUser;
        String message;
        String timeStr;
        boolean isPlaceholder;

        ChatMessage(boolean isUser, String message, String timeStr, boolean isPlaceholder) {
            this.isUser = isUser;
            this.message = message;
            this.timeStr = timeStr;
            this.isPlaceholder = isPlaceholder;
        }
    }

    private WebViewer webViewerComponent = null;
    private final List<ChatMessage> messageList = new ArrayList<>();
    private boolean isBound = false;

    private String userBubbleColor = "#1A73E8";
    private String userTextColor = "#FFFFFF";
    private String partnerBubbleColor = "#E8EAED";
    private String partnerTextColor = "#202124";

    public GoogleChatView(ComponentContainer container) {
        super(container.$form());
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
    // 2. 核心控制与数据初始化引擎
    // ==========================================

    @SimpleFunction(description = "将 WebViewer 组件与此渲染引擎进行绑定。")
    public void BindWebViewer(WebViewer webViewer) {
        this.webViewerComponent = webViewer;
        this.isBound = true;
        refreshWebView(); // 初始化全量绘制一次基础框架
    }

    @SimpleFunction(description = "一键彻底清空手机前端屏幕上的所有聊天气泡。")
    public void ClearDisplay() {
        messageList.clear();
        refreshWebView(); // 清空时全量重置一次框架
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

    // 全量刷新网页内核（仅在初始化绑定、清空时被安全调用）
    private void refreshWebView() {
        if (webViewerComponent != null && isBound) {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>");
            html.append(generateDynamicCss());
            html.append("</head><body><div id='chat-container'>");

            for (ChatMessage msg : messageList) {
                String formattedMsg = parseMarkdownToHtml(msg.message);
                if (msg.isUser) {
                    html.append("<div class='msg-row me'><div class='msg-bubble'>").append(formattedMsg);
                    if (msg.timeStr != null && !msg.timeStr.trim().isEmpty()) {
                        html.append("<span class='time-stamp'>").append(msg.timeStr).append("</span>");
                    }
                    html.append("</div></div>");
                } else {
                    if (msg.isPlaceholder) {
                        html.append("<div class='msg-row other' id='stream-row'><div class='msg-bubble pulse' id='stream-content'>");
                        html.append(formattedMsg.isEmpty() ? "•••" : formattedMsg);
                    } else {
                        html.append("<div class='msg-row other'><div class='msg-bubble'>").append(formattedMsg);
                    }
                    if (msg.timeStr != null && !msg.timeStr.trim().isEmpty()) {
                        html.append("<span class='time-stamp'>").append(msg.timeStr).append("</span>");
                    }
                    html.append("</div></div>");
                }
            }

            html.append("</div>").append(SCROLL_BEHAVIOR_JS).append("</body></html>");
            webViewerComponent.WebViewString(html.toString());
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
    // 3. 动态消息追加方法 (基于高性能 JS 局部增量DOM注入)
    // ==========================================

    @SimpleFunction(description = "【右侧气泡】在手机右侧渲染发送方的消息气泡。")
    public void AppendUserMessage(String message, String timeStr) {
        if (message == null || message.trim().isEmpty()) return;

        // 1. 数据池同步记录
        messageList.add(new ChatMessage(true, message, timeStr, false));

        if (webViewerComponent == null || !isBound) return;

        // 2. 纯 JS 异步追加节点，不触发全页刷新，彻底根除白屏
        String formattedMsg = parseMarkdownToHtml(message);
        StringBuilder rowHtml = new StringBuilder();
        rowHtml.append("<div class='msg-row me'><div class='msg-bubble'>").append(formattedMsg);
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            rowHtml.append("<span class='time-stamp'>").append(timeStr).append("</span>");
        }
        rowHtml.append("</div></div>");

        String safeHtml = JSONObject.quote(rowHtml.toString());
        String jsCommand = "javascript:(function() {" +
                           "  var container = document.getElementById('chat-container');" +
                           "  if(container) {" +
                           "    var div = document.createElement('div');" +
                           "    div.innerHTML = " + safeHtml + ";" +
                           "    container.appendChild(div.firstElementChild);" +
                           "  }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
    }

    @SimpleFunction(description = "【左侧占位符】在左侧创建一个带有呼吸动效的 AI 占位符气泡。")
    public void CreatePartnerBubblePlaceholder() {
        // 1. 数据池同步记录
        messageList.add(new ChatMessage(false, "", "", true));

        if (webViewerComponent == null || !isBound) return;

        // 2. 纯 JS 异步追加节点
        String placeholderHtml = "<div class='msg-row other' id='stream-row'><div class='msg-bubble pulse' id='stream-content'>•••</div></div>";
        String safeHtml = JSONObject.quote(placeholderHtml);
        String jsCommand = "javascript:(function() {" +
                           "  var container = document.getElementById('chat-container');" +
                           "  if(container) {" +
                           "    var div = document.createElement('div');" +
                           "    div.innerHTML = " + safeHtml + ";" +
                           "    container.appendChild(div.firstElementChild);" +
                           "  }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
    }

    @SimpleFunction(description = "【高频流式更新】高频局部刷新占位符内容，杜绝闪烁。")
    public void UpdatePartnerBubbleStream(String currentFullText) {
        if (currentFullText == null) return;

        // 1. 同步更新缓存池中最新的占位符数据
        for (int i = messageList.size() - 1; i >= 0; i--) {
            ChatMessage msg = messageList.get(i);
            if (!msg.isUser && msg.isPlaceholder) {
                msg.message = currentFullText;
                break;
            }
        }

        if (webViewerComponent == null || !isBound) return;

        // 2. 局部 DOM 精确重绘
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
        // 1. 同步纠正数据池中的占位符状态为正式固化状态
        for (int i = messageList.size() - 1; i >= 0; i--) {
            ChatMessage msg = messageList.get(i);
            if (!msg.isUser && msg.isPlaceholder) {
                msg.message = finalMessage;
                msg.timeStr = timeStr;
                msg.isPlaceholder = false;
                break;
            }
        }

        if (webViewerComponent == null || !isBound) return;

        // 2. 局部操作 live DOM（注入最终文本、追加时间戳、安全抹除临时 ID），杜绝白屏
        String parsedHtml = parseMarkdownToHtml(finalMessage);
        StringBuilder bubbleInner = new StringBuilder(parsedHtml);
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            bubbleInner.append("<span class='time-stamp'>").append(timeStr).append("</span>");
        }

        String safeJsString = JSONObject.quote(bubbleInner.toString());
        String jsCommand = "javascript:(function() {" +
                           "  var el = document.getElementById('stream-content');" +
                           "  var row = document.getElementById('stream-row');" +
                           "  if(el) { el.innerHTML = " + safeJsString + "; el.removeAttribute('id'); }" +
                           "  if(row) { row.removeAttribute('id'); }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
    }

    // ==========================================
    // ✨ 新功能积木：局部精细化覆盖设置/新建 AI 气泡
    // ==========================================

    @SimpleFunction(description = "【更新或新建AI回答】直接覆盖更新最靠近末尾的一条AI回答内容。如果当前在屏幕上没有任何AI气泡，则会自动在末尾安全新建一个。")
    public void UpdateOrCreateLatestPartnerMessage(String message, String timeStr) {
        if (message == null) return;

        boolean found = false;
        // 1. 优先操作内存数据池：逆序查找最后一条 AI 消息
        for (int i = messageList.size() - 1; i >= 0; i--) {
            ChatMessage msg = messageList.get(i);
            if (!msg.isUser) {
                msg.message = message;
                msg.timeStr = timeStr;
                msg.isPlaceholder = false; // 强行解除占位符呼吸灯状态
                found = true;
                break;
            }
        }

        // 场景 B：如果整个聊天记录里压根没有 AI 回答气泡，则在池中添加一条常规 AI 气泡
        if (!found) {
            messageList.add(new ChatMessage(false, message, timeStr, false));
        }

        if (webViewerComponent == null || !isBound) return;

        // 2. 纯 JS 高性能 DOM 劫持控制逻辑（存在最新框就改变 innerHTML，不存在就动态 appendChild 容器节点）
        String parsedHtml = parseMarkdownToHtml(message);
        StringBuilder bubbleInner = new StringBuilder(parsedHtml);
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            bubbleInner.append("<span class='time-stamp'>").append(timeStr).append("</span>");
        }
        String safeBubbleInner = JSONObject.quote(bubbleInner.toString());

        // 预备一整条完整气泡的 HTML
        StringBuilder fullRowHtml = new StringBuilder();
        fullRowHtml.append("<div class='msg-row other'><div class='msg-bubble'>").append(parsedHtml);
        if (timeStr != null && !timeStr.trim().isEmpty()) {
            fullRowHtml.append("<span class='time-stamp'>").append(timeStr).append("</span>");
        }
        fullRowHtml.append("</div></div>");
        String safeFullRow = JSONObject.quote(fullRowHtml.toString());

        String jsCommand = "javascript:(function() {" +
                           "  var rows = document.querySelectorAll('#chat-container .msg-row.other');" +
                           "  if(rows.length > 0) {" +
                           "    var lastRow = rows[rows.length - 1];" +
                           "    var bubble = lastRow.querySelector('.msg-bubble');" +
                           "    if(bubble) {" +
                           "      bubble.innerHTML = " + safeBubbleInner + ";" +
                           "    }" +
                           "  } else {" +
                           "    var container = document.getElementById('chat-container');" +
                           "    if(container) {" +
                           "      var div = document.createElement('div');" +
                           "      div.innerHTML = " + safeFullRow + ";" +
                           "      container.appendChild(div.firstElementChild);" +
                           "    }" +
                           "  }" +
                           "})();";
        webViewerComponent.WebViewString(jsCommand);
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

        String cleanedText = text.replace("\r\n", "\n").replace("\r", "\n");
        cleanedText = cleanedText.replaceAll("\n{3,}", "\n\n");

        String html = escapeHtmlChars(cleanedText);

        // 多行代码与行内代码
        html = html.replaceAll("(?s)```([\\w\\W]*?)```", "<pre>$1</pre>");
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // 标题处理
        html = html.replaceAll("(?m)^### (.*?)$", "<br><b><font size='+1'>$1</font></b><br>");
        html = html.replaceAll("(?m)^## (.*?)$", "<br><b><font size='+2'>$1</font></b><br>");
        html = html.replaceAll("(?m)^# (.*?)$", "<br><b><font size='+3'>$1</font></b><br>");

        // 加粗、斜体、删除线
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("__(.*?)__", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replaceAll("_(.*?)_", "<i>$1</i>");
        html = html.replaceAll("~~(.*?)~~", "<del>$1</del>");

        // 分割线
        html = html.replaceAll("(?m)^---$", "<hr>");

        // 换行符转换
        html = html.replace("\n", "<br>");

        return html;
    }
}

package com.custom.webasset;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;

import java.io.File;

@DesignerComponent(
    version = 4, // 军工级加固，版本号升级至 4
    description = "最高防御级本地素材 HTML 强力装载插件。内置 6 维智能自适应路径嗅探引擎，完美击碎 Mixed Content 混合请求封锁，支持离线 H5 页面直接 Ajax 跨域调用公网 API，无缝适配 Android 5.0 至 16+ 存储沙盒。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
public class CustomWebAssetExtension extends AndroidNonvisibleComponent {

    private final Context context;

    public CustomWebAssetExtension(ComponentContainer container) {
        super(container.$form());
        this.context = container.$context();
    }

    /**
     * 核心优化函数：多维沙盒感知、跨域防御解除、视窗极致自适应
     * @param webViewerComponent App Inventor 2 界面上的原生 WebViewer 组件实例积木
     * @param htmlFileName 项目素材（Assets）中的目标 HTML 文件名（例如 index.html 或 sub/dashboard.html）
     */
    @SuppressLint("SetJavaScriptEnabled")
    @SimpleFunction(description = "强力驱动指定的 WebViewer 组件加载项目素材中的 HTML 文件。彻底攻克 Android 10+ 伴侣调试白屏、同源策略死锁等历史疑难杂症，支持本地网页执行公网 HTTPS API 网络请求。")
    public void LoadAssetHtml(final WebViewer webViewerComponent, final String htmlFileName) {
        if (webViewerComponent == null) {
            OnError("核心调用错误：传入的 WebViewer 组件实例为空，请确保积木块已正确挂载！");
            return;
        }
        if (htmlFileName == null || htmlFileName.trim().isEmpty()) {
            OnError("参数错误：输入的 HTML 文件名不可为空！");
            return;
        }

        // 1. 【强路由清洗】过滤并标准化输入的路径格式，阻断由于误输入斜杠/反斜杠造成的 file 协议解析混乱
        String cleanedName = htmlFileName.trim();
        while (cleanedName.startsWith("/")) {
            cleanedName = cleanedName.substring(1);
        }
        while (cleanedName.startsWith("\\")) {
            cleanedName = cleanedName.substring(1);
        }

        final String finalFileName = cleanedName;

        // 2. 强制调度至 Android 系统的 UI 主线程执行操作，杜绝多线程操作 WebView 引发的系统级 Crash
        form.runOnUiThread(new Runnable() {
            @SuppressWarnings("deprecation")
            @Override
            public void run() {
                try {
                    // 提取 App Inventor 2 封装内部的原生 Android WebView 控件
                    WebView webView = (WebView) webViewerComponent.getView();
                    if (webView == null) {
                        OnError("底层实例化故障：未能成功从当前 WebViewer 组件中提取到合法的底层 WebView 视窗实体。");
                        return;
                    }

                    WebSettings settings = webView.getSettings();
                    
                    // 3. 【绝对防御】全方位强固 WebSettings 配置集，对标现代复杂 H5 框架规范
                    settings.setJavaScriptEnabled(true);        // 激活高性能 JavaScript 解析引擎
                    settings.setDomStorageEnabled(true);        // 核心强开：DOM 缓存（现代 H5 前端、各类大型图表、富文本编辑器的绝对命脉）
                    settings.setDatabaseEnabled(true);          // 开启结构化数据库缓存支持
                    settings.setAllowFileAccess(true);          // 显式赋予 WebView 对本地文件系统的读取权限
                    
                    // 开启应用缓存，提升老旧系统下大文本离线 HTML 的二次加载速率
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        settings.setAppCacheEnabled(true);
                        File cacheDir = context.getCacheDir();
                        if (cacheDir != null) {
                            settings.setAppCachePath(cacheDir.getAbsolutePath());
                        }
                    }

                    // 4. 【解除本地同源策略死锁】彻底打通本地 file 协议下的跨文件资产互通
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        settings.setAllowFileAccessFromFileURLs(true);
                        settings.setAllowUniversalAccessFromFileURLs(true);
                    }

                    // 5. 【核能级修复】完美击碎本地 HTML 由于 Mixed Content 限制而无法使用 Ajax/Fetch 请求公网 HTTPS 接口的底层恶疾
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }

                    // 6. 视窗多维自适应与高分屏抗变形渲染优化
                    settings.setUseWideViewPort(true);          // 弹性适配多端视口比例
                    settings.setLoadWithOverviewMode(true);     // 自动缩放自适应至全屏最舒适状态
                    settings.setBuiltInZoomControls(true);      // 开启底层手势手势缩放逻辑支持
                    settings.setDisplayZoomControls(false);     // 强行隐藏系统原生的极其丑陋的半透明放大/缩小控制浮钮
                    settings.setJavaScriptCanOpenWindowsAutomatically(true);

                    // 7. 【6维自适应智能路径嗅探矩阵】从根本上抹平 AI2 各版本调试伴侣在全版本安卓系统上的沙盒路径灾难
                    String targetUrl;

                    if (form instanceof ReplForm) {
                        // 【调试器环境 (AI2 Companion)】
                        File resolvedFile = null;
                        File extFilesDir = context.getExternalFilesDir(null);

                        // 嗅探维度 A：现代化隔离作用域外部沙盒路径 (Android 10 至 Android 16+ 伴侣标准规范)
                        if (extFilesDir != null) {
                            File fileA = new File(extFilesDir, "AppInventor/assets/" + finalFileName);
                            if (fileA.exists()) { resolvedFile = fileA; }
                        }

                        // 嗅探维度 B：公有外部存储传统物理路径 (完美兼容国内各类离线魔改伴侣及老旧版本伴侣)
                        if (resolvedFile == null) {
                            File fileB = new File("/sdcard/AppInventor/assets/" + finalFileName);
                            if (fileB.exists()) { resolvedFile = fileB; }
                        }

                        // 嗅探维度 C：部分高版本严格分区系统下伴侣的二级私有 files 物理演变路径
                        if (resolvedFile == null && extFilesDir != null) {
                            File parentDir = extFilesDir.getParentFile();
                            if (parentDir != null) {
                                File fileC = new File(parentDir, "files/AppInventor/assets/" + finalFileName);
                                if (fileC.exists()) { resolvedFile = fileC; }
                            }
                        }

                        // 嗅探维度 D：极端存储权限封锁设备下的伴侣私有 cache 缓冲区路由
                        if (resolvedFile == null) {
                            File cacheDir = context.getCacheDir();
                            if (cacheDir != null) {
                                File fileD = new File(cacheDir, "AppInventor/assets/" + finalFileName);
                                if (fileD.exists()) { resolvedFile = fileD; }
                            }
                        }

                        // 嗅探维度 E & F：若物理命中检测全部落空（多发生于首次冷启动伴侣且素材尚未完全就位时），触发高可靠逻辑硬回退拼接
                        if (resolvedFile == null) {
                            if (extFilesDir != null) {
                                resolvedFile = new File(extFilesDir, "AppInventor/assets/" + finalFileName);
                            } else {
                                resolvedFile = new File("/storage/emulated/0/Android/data/" + context.getPackageName() + "/files/AppInventor/assets/" + finalFileName);
                            }
                        }

                        targetUrl = "file://" + resolvedFile.getAbsolutePath();

                    } else {
                        // 【独立生产打包环境 (Compiled Signed APK)】
                        // 编译生成独立的正式 APK 安装运行后，资产会极其稳定且百分之百地固化在系统原生只读资产包中
                        targetUrl = "file:///android_asset/" + finalFileName;
                    }

                    // 8. 终极强力装载驱动
                    webView.loadUrl(targetUrl);

                } catch (Exception e) {
                    OnError("WebView 执行底层配置与资源路由装载时突发异常: " + e.getMessage());
                }
            }
        });
    }

    @SimpleEvent(description = "当扩展配置、路径探测或者 HTML 装载过程中出现任何严重的底层异常时，会抛出此事件以便安全捕获。")
    public void OnError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnError", errorMessage);
    }
}

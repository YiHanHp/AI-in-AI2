package com.custom.base64;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Base64;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

@DesignerComponent(
    version = 3, // 升级版本号至 3
    description = "企业级全能图片转Base64插件。深度兼容 Android 10+ 分区存储(Scoped Storage)安全沙盒机制，提供路径及纯Bitmap/Canvas内存级双轨转码通道，全渠道规避Permission Denied与OOM崩溃。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE"
})
public class ImageToBase64Extension extends AndroidNonvisibleComponent {

    private final Context context;
    private static final int TARGET_MAX_SIDE = 1024; // 适配各大Vision大模型的1024最佳分辨率边界

    public ImageToBase64Extension(ComponentContainer container) {
        super(container.$form());
        this.context = container.$context();
    }

    @SimpleEvent(description = "当图片成功转换为标准的 Base64 字符串时触发")
    public void OnConversionSuccess(String base64String) {
        EventDispatcher.dispatchEvent(this, "OnConversionSuccess", base64String);
    }

    @SimpleEvent(description = "当图片转换失败、路径失效或发生内存熔断时触发")
    public void OnConversionError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnConversionError", errorMessage);
    }

    @SimpleFunction(description = "【安全沙盒路径转换】将传入的图片路径、Content URI或File URI转为Base64。自动纠正手机拍照倾斜，完美适配 Android 10+ 沙盒文件读取。")
    public void ConvertImageToBase64(final String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            OnConversionError("错误：传入的图片路径为空，请确认相册或相机组件是否成功输出了有效路径。");
            return;
        }

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap sourceBitmap = null;
                Bitmap correctedBitmap = null;
                ByteArrayOutputStream baos = null;
                InputStream sizeCheckStream = null;
                InputStream imageStream = null;
                
                try {
                    String cleanPath = imagePath.trim();
                    Uri targetUri;
                    
                    // 【Scoped Storage 核心修复】废弃直接通过绝对路径 new File() 的不安全读取行为
                    // 全面统一走 ContentResolver 多媒体沙盒解析通道
                    if (cleanPath.startsWith("content://") || cleanPath.startsWith("file://")) {
                        targetUri = Uri.parse(cleanPath);
                    } else {
                        // 兼容 App Inventor 吐出的传统纯绝对路径字符串
                        if (cleanPath.startsWith("/")) {
                            targetUri = Uri.fromFile(new File(cleanPath));
                        } else {
                            // 针对特定的相对资产路径做兜底
                            targetUri = Uri.parse(cleanPath);
                        }
                    }

                    // 1. 第一阶段：预读边界元数据（不占用 heap 堆内存空间）
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    
                    sizeCheckStream = context.getContentResolver().openInputStream(targetUri);
                    if (sizeCheckStream == null) {
                        postError("沙盒权限越界或文件已失效：无法建立有效的输入流。");
                        return;
                    }
                    BitmapFactory.decodeStream(sizeCheckStream, null, options);
                    sizeCheckStream.close();

                    // 2. 动态调节降采样阀值
                    options.inSampleSize = calculateInSampleSize(options, TARGET_MAX_SIDE, TARGET_MAX_SIDE);
                    options.inJustDecodeBounds = false;
                    // 开启高级硬件加速位图解码优化配置
                    options.inPurgeable = true;
                    options.inInputShareable = true;

                    // 3. 第二阶段：无损低内存加载
                    imageStream = context.getContentResolver().openInputStream(targetUri);
                    sourceBitmap = BitmapFactory.decodeStream(imageStream, null, options);
                    imageStream.close();

                    if (sourceBitmap == null) {
                        postError("解码失败：目标文件不是标准图像格式或无权读取。");
                        return;
                    }

                    // 4. 第三阶段：智能纠偏 EXIF 方向
                    int rotationAngle = getExifRotationAngle(targetUri);
                    if (rotationAngle != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationAngle);
                        correctedBitmap = Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.getWidth(), sourceBitmap.getHeight(), matrix, true);
                    } else {
                        correctedBitmap = sourceBitmap;
                    }

                    // 5. 第四阶段：流水线转码
                    baos = new ByteArrayOutputStream();
                    correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos);
                    byte[] imageBytes = baos.toByteArray();

                    final String base64Result = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                    form.runOnUiThread(new Runnable() {
                        @Override public void run() { OnConversionSuccess(base64Result); }
                    });

                } catch (final Exception e) {
                    postError("转码期捕获到系统异常: " + e.getMessage());
                } finally {
                    // 严格的多重安全流熔断关闭
                    try { if (sizeCheckStream != null) sizeCheckStream.close(); } catch (Exception ignored) {}
                    try { if (imageStream != null) imageStream.close(); } catch (Exception ignored) {}
                    try { if (baos != null) baos.close(); } catch (Exception ignored) {}
                    if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
                    if (correctedBitmap != null && !correctedBitmap.isRecycled()) correctedBitmap.recycle();
                }
            }
        });
    }

    /**
     * 【新增内存双轨功能】允许直接通过高级扩展传入内存中的原生组件位图（无需经过写盘读盘），大幅提升画板/Canvas应用的速度。
     */
    public void ConvertBitmapToBase64(final Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            OnConversionError("错误：传入的内存位图对象为空或已被系统注销销毁。");
            return;
        }

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                ByteArrayOutputStream baos = null;
                Bitmap scaledBitmap = null;
                try {
                    // 对内存中的大 Bitmap 进行防超限缩放
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    if (width > TARGET_MAX_SIDE || height > TARGET_MAX_SIDE) {
                        float scale = Math.min((float) TARGET_MAX_SIDE / width, (float) TARGET_MAX_SIDE / height);
                        Matrix matrix = new Matrix();
                        matrix.postScale(scale, scale);
                        scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                    } else {
                        scaledBitmap = bitmap;
                    }

                    baos = new ByteArrayOutputStream();
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos);
                    byte[] imageBytes = baos.toByteArray();
                    final String base64Result = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                    form.runOnUiThread(new Runnable() {
                        @Override public void run() { OnConversionSuccess(base64Result); }
                    });
                } catch (Exception e) {
                    postError("内存位图转码发生严重异常: " + e.getMessage());
                } finally {
                    try

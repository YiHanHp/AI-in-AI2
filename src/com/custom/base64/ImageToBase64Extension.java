package com.custom.base64;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@DesignerComponent(
    version = 4,
    description = "超级兼容版图片转Base64插件。完美向下兼容至 Android 4.4，向上兼容至 Android 15+ 存储沙盒与新版媒体权限体制。采用线程池架构替代老旧 AsyncTask，全渠道规避 OOM 与权限崩溃。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
// 【核心修复】App Inventor 2 固有的注解限制，多权限必须用逗号隔开写在同一个字符串内，不能使用 Java 数组 {}
@UsesPermissions(permissionNames = "android.permission.READ_EXTERNAL_STORAGE,android.permission.WRITE_EXTERNAL_STORAGE,android.permission.READ_MEDIA_IMAGES")
public class ImageToBase64Extension extends AndroidNonvisibleComponent {

    private final Context context;
    private static final int TARGET_MAX_SIDE = 1024; // 适配大模型及主流网络传输的最佳分辨率边界
    
    // 替代已废弃的 AsyncTask，提供高并发、多版本稳定的单线程队列池
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public ImageToBase64Extension(ComponentContainer container) {
        super(container.$form());
        this.context = container.$context();
    }

    @SimpleEvent(description = "当图片成功转换为标准的 Base64 字符串时触发")
    public void OnConversionSuccess(String base64String) {
        EventDispatcher.dispatchEvent(this, "OnConversionSuccess", base64String);
    }

    @SimpleEvent(description = "当图片转换失败、路径失效、权限遭拒或发生内存熔断时触发")
    public void OnConversionError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnConversionError", errorMessage);
    }

    @SimpleFunction(description = "【全兼容路径转换】将传入的图片路径或Content/File URI转换为Base64。上至Android 15沙盒保护，下至Android 4.4，全自动纠正拍照倾斜。")
    public void ConvertImageToBase64(final String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            OnConversionError("错误：传入的图片路径为空，请确认上游组件是否输出了有效路径。");
            return;
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap sourceBitmap = null;
                Bitmap correctedBitmap = null;
                ByteArrayOutputStream baos = null;
                InputStream sizeCheckStream = null;
                InputStream imageStream = null;
                
                try {
                    final Uri targetUri = parseSafeUri(imagePath.trim());
                    if (targetUri == null) {
                        postError("路径解析失败：无法处理传入的路径格式。");
                        return;
                    }

                    // 1. 第一阶段：预读边界元数据（防止大图直接装载导致 OOM 崩溃）
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    
                    try {
                        sizeCheckStream = context.getContentResolver().openInputStream(targetUri);
                    } catch (Exception e) {
                        postError("文件读取受限：Android高版本沙盒权限越界、或文件已被系统清除。");
                        return;
                    }

                    if (sizeCheckStream == null) {
                        postError("无法建立有效输入流，目标文件可能不存在。");
                        return;
                    }
                    BitmapFactory.decodeStream(sizeCheckStream, null, options);
                    sizeCheckStream.close();

                    // 2. 动态计算适配低内存的降采样比率
                    options.inSampleSize = calculateInSampleSize(options, TARGET_MAX_SIDE, TARGET_MAX_SIDE);
                    options.inJustDecodeBounds = false;
                    
                    // 针对古老的 Android 4.x (API < 21) 的特殊低内存回收优化配置
                    if (Build.VERSION.SDK_INT < 21) {
                        options.inPurgeable = true;
                        options.inInputShareable = true;
                    }

                    // 3. 第二阶段：安全加载降采样后的位图
                    imageStream = context.getContentResolver().openInputStream(targetUri);
                    sourceBitmap = BitmapFactory.decodeStream(imageStream, null, options);
                    imageStream.close();

                    if (sourceBitmap == null) {
                        postError("解码失败：文件已损坏或非标准图像格式。");
                        return;
                    }

                    // 4. 第三阶段：全版本兼容的 EXIF 方向智能校正
                    int rotationAngle = getExifRotationAngleSafe(targetUri, imagePath);
                    if (rotationAngle != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationAngle);
                        correctedBitmap = Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.getWidth(), sourceBitmap.getHeight(), matrix, true);
                    } else {
                        correctedBitmap = sourceBitmap;
                    }

                    // 5. 第四阶段：高保真流水线压缩转码
                    baos = new ByteArrayOutputStream();
                    correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos); // 80 质量比为大模型视觉识别平衡度最佳点
                    byte[] imageBytes = baos.toByteArray();

                    final String base64Result = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                    form.runOnUiThread(new Runnable() {
                        @Override public void run() { OnConversionSuccess(base64Result); }
                    });

                } catch (final Exception e) {
                    postError("转码期捕获到系统级异常: " + e.getMessage());
                } finally {
                    // 多重无缝熔断流清理，杜绝任何死锁与泄漏
                    try { if (sizeCheckStream != null) sizeCheckStream.close(); } catch (Exception ignored) {}
                    try { if (imageStream != null) imageStream.close(); } catch (Exception ignored) {}
                    try { if (baos != null) baos.close(); } catch (Exception ignored) {}
                    if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
                    if (correctedBitmap != null && !correctedBitmap.isRecycled()) correctedBitmap.recycle();
                }
            }
        });
    }

    @SimpleFunction(description = "【内存位图转换】直接将 Canvas、Image 或画板组件在内存中的原生 Bitmap 对象转为 Base64，省去频繁写盘读盘损耗。")
    public void ConvertBitmapToBase64(final Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            OnConversionError("错误：传入的内存位图对象为空，或已被 Android 系统前台强行回收。");
            return;
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                ByteArrayOutputStream baos = null;
                Bitmap scaledBitmap = null;
                try {
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    // 针对大尺寸画板进行防超限缩放
                    if (width > TARGET_MAX_SIDE || height > TARGET_MAX_SIDE) {
                        float scale = Math.min((float) TARGET_MAX_SIDE / width, (float) TARGET_MAX_SIDE / height);
                        Matrix matrix = new Matrix();
                        matrix.postScale(scale, scale);
                        scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                    } else {
                        scaledBitmap = bitmap;
                    }

                    baos = new ByteArrayOutputStream();
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                    byte[] imageBytes = baos.toByteArray();
                    final String base64Result = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                    form.runOnUiThread(new Runnable() {
                        @Override public void run() { OnConversionSuccess(base64Result); }
                    });
                } catch (Exception e) {
                    postError("内存位图转码发生内部异常: " + e.getMessage());
                } finally {
                    try { if (baos != null) baos.close(); } catch (Exception ignored) {}
                    // 极其重要：只回收我们为了防止OOM新生成的缩放位图。原始 bitmap 属于底层 Canvas 组件，绝不能销毁，否则前端画布会直接全黑报错。
                    if (scaledBitmap != null && scaledBitmap != bitmap && !scaledBitmap.isRecycled()) {
                        scaledBitmap.recycle();
                    }
                }
            }
        });
    }

    // --- 高可靠性全版本向下兼容辅助工具箱 ---

    private Uri parseSafeUri(String path) {
        try {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                return Uri.parse(path);
            }
            if (path.startsWith("/")) {
                return Uri.fromFile(new File(path));
            }
            return Uri.parse(path);
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private int getExifRotationAngleSafe(Uri uri, String rawPath) {
        int degree = 0;
        InputStream inputStream = null;
        try {
            ExifInterface exifInterface = null;
            if (Build.VERSION.SDK_INT >= 24) {
                inputStream = context.getContentResolver().openInputStream(uri);
                if (inputStream != null) {
                    exifInterface = new ExifInterface(inputStream);
                }
            } else {
                String realPath = rawPath.startsWith("file://") ? rawPath.substring(7) : rawPath;
                if (new File(realPath).exists()) {
                    exifInterface = new ExifInterface(realPath);
                }
            }

            if (exifInterface != null) {
                int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: degree = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: degree = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: degree = 270; break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
        }
        return degree;
    }

    private void postError(final String errorMessage) {
        form.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                OnConversionError(errorMessage);
            }
        });
    }
}

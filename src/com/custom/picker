package com.custom.picker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;

@DesignerComponent(
    version = 3,
    description = "现代安全媒体选择器。完美适配 Android 4.4 至 Android 16+，优先采用系统免权限 Photo Picker 架构，并自动固化 URI 临时权限，与 Base64 插件无缝闭合。",
    category = ComponentCategory.EXTENSION,
    nonVisible = true
)
@SimpleObject(external = true)
public class ImagePickerExtension extends AndroidNonvisibleComponent implements ActivityResultListener {

    private final Context context;
    private final Activity activity;
    private int requestCode = 0;

    public ImagePickerExtension(ComponentContainer container) {
        super(container.$form());
        this.context = container.$context();
        this.activity = container.$context() instanceof Activity ? (Activity) container.$context() : null;
    }

    @SimpleEvent(description = "当用户成功选择图片时触发，返回的路径可直接传入 Base64 转换插件")
    public void OnImageSelected(final String imagePath) {
        EventDispatcher.dispatchEvent(this, "OnImageSelected", imagePath);
    }

    @SimpleEvent(description = "当用户取消选择，或选择器启动失败时触发")
    public void OnPickerError(final String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnPickerError", errorMessage);
    }

    @SimpleFunction(description = "【安全媒体选择器】拉起系统相册。全版本免动态权限申请，完美支持高版本沙盒与老版本媒体库。")
    public void OpenGallery() {
        if (activity == null) {
            OnPickerError("错误：无法获取当前的 Activity 上下文。");
            return;
        }

        if (requestCode == 0) {
            requestCode = form.registerForActivityResult(this);
        }

        try {
            Intent intent = null;

            // 1. 针对 Android 13 及以上系统，使用全新的标准照片选择器
            if (Build.VERSION.SDK_INT >= 33) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                intent.setType("image/*");
            } 
            // 2. 针对 Android 10 到 Android 12，尝试调用向下移植的系统照片选择器
            else if (Build.VERSION.SDK_INT >= 30) {
                intent = new Intent("android.provider.action.PICK_IMAGES");
                intent.setType("image/*");
            }

            if (intent != null && intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivityForResult(intent, requestCode);
                return;
            }

            // 3. 传统兜底方案
            Intent fallbackIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            if (fallbackIntent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivityForResult(fallbackIntent, requestCode);
            } else {
                Intent universalIntent = new Intent(Intent.ACTION_GET_CONTENT);
                universalIntent.setType("image/*");
                universalIntent.addCategory(Intent.CATEGORY_OPENABLE);
                activity.startActivityForResult(universalIntent, requestCode);
            }

        } catch (Exception e) {
            OnPickerError("系统相册拉起失败: " + e.getMessage());
        }
    }

    @Override
    public void resultReturned(int requestCode, int resultCode, Intent data) {
        if (requestCode == this.requestCode) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                final Uri selectedImageUri = data.getData();
                if (selectedImageUri != null) {
                    
                    // 【终极优化点】固化 URI 读取权限
                    // 解决异步线程池/跨组件调用时，因临时权限过期导致的 "Permission Denied" 崩溃
                    try {
                        int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        if (Build.VERSION.SDK_INT >= 19 && takeFlags != 0) {
                            context.getContentResolver().takePersistableUriPermission(selectedImageUri, takeFlags);
                        }
                    } catch (Exception ignored) {
                        // 部分传统旧设备或特定的 ACTION_PICK 不支持固化，直接忽略即可，不影响后续流程
                    }

                    form.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            OnImageSelected(selectedImageUri.toString());
                        }
                    });
                } else {
                    postErrorOnUI("选择失败：系统未能成功返回有效的图片资源句柄。");
                }
            } else {
                postErrorOnUI("用户取消了图片选择。");
            }
        }
    }

    private void postErrorOnUI(final String message) {
        form.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                OnPickerError(message);
            }
        });
    }
}

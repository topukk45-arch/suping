package com.baofu.suping;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    // 和记问同一个米黄，必须与网页里的页面底色一致。窗口根视图和 WebView 自身都钉成同一个色，
    // 启动过程中任一环节都不会露出系统默认白底。
    private static final int BG = Color.parseColor("#F7F0E6");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate() 之前注册
        registerPlugin(WallpaperPlugin.class);
        super.onCreate(savedInstanceState);

        try {
            View decor = getWindow().getDecorView();
            if (decor != null) decor.setBackgroundColor(BG);
            getWindow().setBackgroundDrawableResource(R.color.appWindowBackground);
        } catch (Exception ignored) {}

        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.setBackgroundColor(BG);
                View parent = (View) wv.getParent();
                if (parent != null) parent.setBackgroundColor(BG);
            }
        } catch (Exception ignored) {}
    }
}

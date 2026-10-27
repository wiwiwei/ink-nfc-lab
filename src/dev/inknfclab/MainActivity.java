package dev.inknfclab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;

public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int BACKGROUND = Color.rgb(16, 23, 29);
    private static final int SURFACE = Color.rgb(28, 39, 48);
    private static final int ACCENT = Color.rgb(113, 225, 197);
    private static final int TEXT = Color.rgb(233, 243, 247);
    private static final int PICK_IMAGE = 41;
    private final ExecutorService rootWork = Executors.newSingleThreadExecutor();
    private final ExecutorService tagWork = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWork = Executors.newSingleThreadExecutor();
    private final StringBuilder logText = new StringBuilder();
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private boolean logUpdatePending;
    private NfcAdapter nfc;
    private TextView state;
    private TextView device;
    private TextView logs;
    private TextView compatibilityState;
    private TextView nfcChipView;
    private ScrollView logScroll;
    private Button inspect;
    private Button probe;
    private Button normal;
    private Button chooseImage;
    private Button uploadImage;
    private Button rfChoice;
    private Button rfInspect;
    private Button modeChoice;
    private Button algorithmChoice;
    private Button connectionChoice;
    private Button stopButton;
    private LinearLayout debugPanel;
    private Button fitChoice;
    private Button ditherChoice;
    private Button rotateImage;
    private Button compareImage;
    private Button resetImage;
    private LinearLayout adjustments;
    private TextView previewCaption;
    private TextView imageSummary;
    private ProgressBar transferProgress;
    private SeekBar brightnessSlider;
    private SeekBar contrastSlider;
    private int rfDelta;
    private ImageView preview;
    private Uri selectedImage;
    private volatile ImageCodec.PreparedImage preparedImage;
    private volatile boolean uploadRequested;
    private volatile boolean cancelled;
    private boolean coverMode;
    private int imageMode;
    private int selectedAlgorithm;
    private boolean standardOnly;
    private boolean standardSession;
    private volatile NfcVendor.Kind nfcVendor = NfcVendor.Kind.UNKNOWN;
    private volatile String nfcEvidence = "尚未检查";
    private final Runnable standardTimeout = () -> {
        if (!standardSession) return;
        if (this.reading) stopSession();
        else {
            uploadRequested = false;
            state.setText("未发现可连接的驱动板");
            device.setText("标准 NFC 不发送 7-bit 7A。若板子必须先唤醒，需要对应机型的底层适配；调整贴合位置后可重试。");
            finishStandardSession();
        }
    };
    private int brightness;
    private int contrast;
    private int rotation;
    private double ditherStrength = .85;
    private boolean showOriginal;
    private boolean preparing;
    private volatile int imageGeneration;
    private volatile boolean refreshComplete;
    private volatile Process rootProcess;
    private volatile File nxpStopFile;
    private volatile Process compatibilityProcess;
    private volatile boolean compatibilityRunning;
    private int compatibilityRetries;
    private boolean readerAfterReady;
    private volatile IsoDep connection;
    private volatile boolean busy;
    private volatile boolean stopped;
    private volatile boolean resumed;
    private volatile boolean reading;
    private volatile boolean sessionFailed;
    private boolean listening;
    private File sessionLog;
    private volatile int tagCount;
    private volatile boolean d1Received;
    private final Runnable refreshCompatibility = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            startAutomaticCompatibility();
            logHandler.postDelayed(this, 120000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfc = NfcAdapter.getDefaultAdapter(this);
        sessionLog = new File(getFilesDir(), "last-session.txt");
        render();
        rootWork.execute(this::detectNfcVendor);
        log("Ink NFC Lab 0.5.1 / " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        log("支持四色选图、编码、认证、D3上传与刷新；只在用户确认后写屏");
    }

    private void render() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BACKGROUND);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));
        page.addView(content);
        TextView eyebrow = text("INK STUDIO  /  NFC", 11, ACCENT);
        eyebrow.setLetterSpacing(.15f);
        LinearLayout header = row(content);
        header.addView(eyebrow, new LinearLayout.LayoutParams(0, dp(48), 1));
        eyebrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button debug = button(header, "⋯", this::showDiagnostics);
        debug.setContentDescription("调试与连接设置");
        LinearLayout.LayoutParams debugLayout = (LinearLayout.LayoutParams) debug.getLayoutParams();
        debugLayout.width = dp(48);
        debugLayout.weight = 0;
        debug.setLayoutParams(debugLayout);
        debugPanel = new LinearLayout(this);
        debugPanel.setOrientation(LinearLayout.VERTICAL);
        debugPanel.setPadding(dp(14), dp(8), dp(14), dp(8));
        compatibilityState = text(usesRootBackend() ? "NFC 兼容服务正在准备…" : "标准 NFC · 不请求 Root", 11, ACCENT);
        compatibilityState.setPadding(0, 0, 0, dp(12));
        debugPanel.addView(compatibilityState);
        button(debugPanel, "设备支持信息", () -> new AlertDialog.Builder(this).setTitle("设备与连接能力")
                .setMessage(deviceReport()).setPositiveButton("关闭", null)
                .setNeutralButton("导出报告", (dialog, which) -> shareDeviceReport()).show());
        connectionChoice = button(debugPanel, "连接方式 · 自动", () -> {
            if (busy) return;
            if (pendingConfiguration()) {
                state.setText("请先恢复上次 Root 会话配置，再切换连接方式");
                return;
            }
            new AlertDialog.Builder(this).setTitle("连接方式")
                    .setItems(new String[]{"自动 · 按控制器选择连接方式", "标准 NFC · 不请求 Root"}, (dialog, which) -> {
                        standardOnly = which == 1;
                        connectionChoice.setText(standardOnly ? "连接方式 · 标准 NFC" : "连接方式 · 自动");
                        compatibilityState.setText(usesRootBackend() ? "Root 后端 · 下次会话生效" : "标准 NFC · 不请求 Root");
                        setBusy(false);
                    }).setNegativeButton("取消", null).show();
        });

        LinearLayout statusCard = card(content);
        content.removeView(statusCard);
        state = text("选择图片，预览后刷写", 15, ACCENT);
        statusCard.addView(state);
        device = text("找准线圈位置，刷屏期间保持贴合。", 12, 0xffa9bec9);
        device.setPadding(0, dp(6), 0, dp(10));
        statusCard.addView(device);
        nfcChipView = text("NFC 控制器 · 检测中", 11, ACCENT);
        statusCard.addView(nfcChipView);
        transferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(100);
        transferProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        statusCard.addView(transferProgress, new LinearLayout.LayoutParams(-1, dp(4)));

        LinearLayout imageCard = card(content);
        imageCard.addView(text("预览", 16, TEXT));
        previewCaption = text("552 × 768 · 黑 / 白 / 黄 / 红", 11, 0xffa9bec9);
        previewCaption.setPadding(0, dp(4), 0, dp(8));
        imageCard.addView(previewCaption);
        preview = new ImageView(this);
        preview.setBackgroundColor(Color.WHITE);
        preview.setImageDrawable(getDrawable(getResources().getIdentifier("preview_placeholder", "drawable", getPackageName())));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("墨水屏点阵预览，尚未选择图片");
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(-1, dp(270));
        previewLayout.topMargin = dp(4);
        imageCard.addView(preview, previewLayout);
        imageSummary = text("选择照片或插画开始创作。预览使用真实发送的四色像素；实屏颜色会有差异。", 11, 0xffa9bec9);
        imageSummary.setPadding(0, dp(10), 0, dp(6));
        imageCard.addView(imageSummary);
        chooseImage = button(imageCard, "选择图片", this::openImagePicker);
        LinearLayout editorPanel = new LinearLayout(this);
        editorPanel.setOrientation(LinearLayout.VERTICAL);
        editorPanel.setVisibility(View.GONE);
        button(imageCard, "画面设置 · 算法 / 构图 / 调色", () ->
                editorPanel.setVisibility(editorPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        imageCard.addView(editorPanel);
        algorithmChoice = button(editorPanel, "算法 · 经典 0.4（默认）", () -> new AlertDialog.Builder(this)
                .setTitle("独立算法对照 · 其他参数保持不变")
                .setItems(AlgorithmCatalog.choices(), (dialog, which) -> {
                    selectedAlgorithm = which;
                    updateImageControls();
                    prepareSelectedImage();
                }).setNegativeButton("取消", null).show());
        LinearLayout modes = row(editorPanel);
        modeChoice = button(modes, "模式 · 照片", this::chooseImageMode);
        ditherChoice = button(modes, "抖动 · 柔和", () -> new AlertDialog.Builder(this)
                .setTitle("细节与颗粒的平衡")
                .setItems(new String[]{"关闭 · 适合文字与线稿", "柔和 · 照片推荐", "细腻 · 更多灰阶，颗粒更明显"}, (dialog, which) -> {
                    ditherStrength = new double[]{0, .85, 1}[which];
                    updateImageControls();
                    prepareSelectedImage();
                }).setNegativeButton("取消", null).show());
        LinearLayout framing = row(editorPanel);
        fitChoice = button(framing, "构图 · 完整", () -> {
            coverMode = !coverMode;
            updateImageControls();
            prepareSelectedImage();
        });
        rotateImage = button(framing, "旋转 90°", () -> {
            rotation = (rotation + 90) % 360;
            prepareSelectedImage();
        });
        LinearLayout compare = row(editorPanel);
        compareImage = button(compare, "查看原图", () -> {
            showOriginal = !showOriginal;
            updatePreview();
        });
        button(compare, "画面微调", () -> adjustments.setVisibility(adjustments.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        adjustments = new LinearLayout(this);
        adjustments.setOrientation(LinearLayout.VERTICAL);
        adjustments.setVisibility(View.GONE);
        editorPanel.addView(adjustments);
        brightnessSlider = imageSlider(adjustments, "亮度", 80, 40, value -> brightness = value - 40);
        contrastSlider = imageSlider(adjustments, "对比度", 80, 30, value -> contrast = value - 30);
        resetImage = button(adjustments, "恢复画面默认参数", () -> {
            imageMode = ImageQuantizer.PHOTO;
            selectedAlgorithm = AlgorithmCatalog.CLASSIC;
            brightness = contrast = rotation = 0;
            coverMode = showOriginal = false;
            ditherStrength = .85;
            brightnessSlider.setProgress(40);
            contrastSlider.setProgress(30);
            updateImageControls();
            prepareSelectedImage();
        });
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(18), dp(10), dp(18), dp(14));
        uploadImage = button(dock, "选择图片，开始创作", () -> {
            if (preparedImage == null && !preparing && !busy) openImagePicker();
            else confirmUpload();
        });
        uploadImage.setBackground(rounded(ACCENT, 0));
        uploadImage.setTextColor(android.content.res.ColorStateList.valueOf(BACKGROUND));
        uploadImage.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stopButton = button(dock, "停止", () -> { if (busy) stopSession(); });
        LinearLayout.LayoutParams stopLayout = (LinearLayout.LayoutParams) stopButton.getLayoutParams();
        stopLayout.width = dp(64);
        stopLayout.weight = 0;
        stopButton.setLayoutParams(stopLayout);

        content.addView(statusCard);
        Button advanced = button(debugPanel, "连接诊断与实验设置  ＋", () -> {});
        LinearLayout actions = card(debugPanel);
        actions.setVisibility(View.GONE);
        advanced.setOnClickListener(view -> {
            boolean open = actions.getVisibility() != View.VISIBLE;
            actions.setVisibility(open ? View.VISIBLE : View.GONE);
            advanced.setText(open ? "连接诊断与实验设置  −" : "连接诊断与实验设置  ＋");
        });
        actions.addView(text("日常刷图无需调整 APC。供电异常时先调整线圈耦合位置。", 12, 0xffa9bec9));
        rfChoice = button(actions, "APC 实验档：原厂", () -> {
            String[] labels = {"原厂（不调整）", "APC -1", "APC +1", "APC +2"};
            int[] deltas = {0, -1, 1, 2};
            new AlertDialog.Builder(this).setTitle("APC 参数档，不是实测瓦数")
                    .setItems(labels, (dialog, which) -> {
                        rfDelta = deltas[which];
                        rfChoice.setText("APC 实验档：" + labels[which]);
                        log("APC 选择 " + labels[which] + "；仅下次7A探测/刷图生效，会话结束恢复原始参数；不保证功率增大");
                    }).setNegativeButton("取消", null).show();
        });
        rfInspect = button(actions, "读取当前 APC 参数", () -> startHelper("rfinspect"));
        inspect = button(actions, "1  检查 root 与 NFC 接口", () -> startHelper("inspect"));
        probe = button(actions, "2  7A 探测 · 15 秒", () -> startHelper("probe"));
        normal = button(actions, "普通寻卡 · 检查标签发现", () -> {
            startStandardSession(false);
        });
        button(actions, "停止 / 恢复轮询", () -> {
            if (busy) stopSession();
            else {
                disableReader();
                startHelper("restore");
            }
        });
        button(actions, "打开 NFC 设置", () -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        TextView hint = text("只有已验证 Root 后端需要超级用户授权，标准 NFC 不请求 Root。\n配置读回只证明接口接受；D1 成功才证明驱动板已连接。", 12, 0xffa9bec9);
        hint.setPadding(0, dp(12), 0, 0);
        actions.addView(hint);

        Button logToggle = button(debugPanel, "会话日志与导出  ＋", () -> {});
        LinearLayout logCard = card(debugPanel);
        logCard.setVisibility(View.GONE);
        logToggle.setOnClickListener(view -> {
            boolean open = logCard.getVisibility() != View.VISIBLE;
            logCard.setVisibility(open ? View.VISIBLE : View.GONE);
            logToggle.setText(open ? "会话日志与导出  −" : "会话日志与导出  ＋");
        });
        logCard.addView(text("会话日志", 16, TEXT));
        logScroll = new ScrollView(this);
        logs = text("", 11, 0xffb8ced9);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextIsSelectable(true);
        logs.setPadding(0, dp(12), 0, 0);
        logScroll.addView(logs);
        logCard.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(210)));
        button(logCard, "分享会话日志", this::shareLog);
        button(logCard, "分享底层收发日志", this::shareTrace);
        TextView attribution = text("协议来源与许可", 12, ACCENT);
        attribution.setPadding(dp(4), dp(4), 0, dp(12));
        attribution.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("协议来源与许可")
                .setMessage("D1 命令及解析参考 kur0neko/huawei-epd-pn532\nhttps://gitee.com/kur0neko/huawei-epd-pn532\n\nRequired Notice: Copyright (c) 2026 kur0neko\n\nPolyForm Noncommercial 1.0.0\nhttps://polyformproject.org/licenses/noncommercial/1.0.0/")
                .setPositiveButton("关闭", null).show());
        debugPanel.addView(attribution);
        shell.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addView(dock);
        setContentView(shell);
        setBusy(false);
    }

    private boolean enableReader() {
        if (!resumed || nfc == null || !nfc.isEnabled()) {
            state.setText("请先开启 NFC");
            log("NFC 不可用或页面尚未在前台");
            return false;
        }
        Bundle options = new Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        nfc.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options);
        listening = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        return true;
    }

    private void disableReader() {
        listening = false;
        if (nfc != null) nfc.disableReaderMode(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        IsoDep active = connection;
        if (active != null) new Thread(() -> { try { active.close(); } catch (Exception ignored) {} }, "ink-nfc-close").start();
    }

    private boolean usesRootBackend() {
        return DeviceSupport.useRoot(Build.DEVICE, Build.VERSION.SDK_INT, nfc != null, standardOnly);
    }

    private boolean pendingConfiguration() {
        return new File(getFilesDir(), "polling.snapshot").exists()
                || new File(getFilesDir(), "polling.snapshot.rf").exists();
    }

    private void detectNfcVendor() {
        if (nfc == null) {
            runOnUiThread(() -> nfcChipView.setText("NFC 控制器 · 本机无 NFC"));
            return;
        }
        String path = "";
        try { path = getPackageManager().getApplicationInfo("com.android.nfc", 0).sourceDir; }
        catch (Exception ignored) {}
        StringBuilder properties = new StringBuilder();
        for (String key : new String[]{"ro.strongbox.model", "ro.vendor.nfc.chip", "ro.boot.nfc", "ro.vendor.nfc.model", "persist.sys.nfc.chip_name"}) {
            try {
                Process process = new ProcessBuilder("getprop", key).start();
                if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroy(); continue; }
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String value = reader.readLine();
                if (value != null && !value.isEmpty()) properties.append(key).append('=').append(value).append(' ');
            } catch (Exception ignored) {}
        }
        NfcVendor.Kind result = NfcVendor.identify(path, properties.toString());
        if (result == NfcVendor.Kind.UNKNOWN && DeviceSupport.verifiedRootProfile(Build.DEVICE, Build.VERSION.SDK_INT))
            result = NfcVendor.Kind.ST;
        nfcVendor = result;
        nfcEvidence = "NFC 包=" + path + "；属性=" + properties;
        NfcVendor.Kind shown = result;
        runOnUiThread(() -> {
            if (nfcChipView == null || isDestroyed()) return;
            nfcChipView.setText("NFC 控制器 · " + (shown == NfcVendor.Kind.NXP ? "NXP · ADB / Root"
                    : shown == NfcVendor.Kind.THN31 ? "THN31 · Root"
                    : shown == NfcVendor.Kind.ST ? "ST · 需要 Root" : "未能可靠识别 · 可手动选择"));
        });
    }

    private void showDiagnostics() {
        if (debugPanel.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) debugPanel.getParent()).removeView(debugPanel);
        ScrollView panel = new ScrollView(this);
        panel.addView(debugPanel);
        new AlertDialog.Builder(this).setTitle("调试与连接设置").setView(panel).setPositiveButton("关闭", null).show();
    }

    private String deviceReport() {
        return "Ink NFC Lab 0.5.1\n品牌：" + Build.MANUFACTURER + "\n型号：" + Build.MODEL
                + "\n代号：" + Build.DEVICE + "\nAndroid：" + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nABI：" + Arrays.toString(Build.SUPPORTED_ABIS) + "\nNFC：" + (nfc != null ? "有" : "无")
                + "\n控制器：" + nfcVendor + "（" + nfcEvidence + "）"
                + "\n当前路径：" + (usesRootBackend() ? "已验证 ST Root 配置" : "按控制器选择")
                + "\n\n" + DeviceSupport.summary(Build.DEVICE, Build.VERSION.SDK_INT, nfc != null)
                + "\n\n标准读卡不等于已能发射 7-bit 7A。未经适配的设备不会自动注入补丁或改射频。";
    }

    private void shareDeviceReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, deviceReport());
        startActivity(Intent.createChooser(send, "导出设备能力报告"));
    }

    private void startStandardSession(boolean upload) {
        if (busy || preparing) return;
        if (pendingConfiguration()) {
            uploadRequested = false;
            state.setText("存在未恢复的配置 · 请先从调试入口恢复");
            return;
        }
        if (!resumed || nfc == null || !nfc.isEnabled()) {
            uploadRequested = false;
            state.setText(nfc == null ? "此设备没有 NFC · 可继续处理图片" : "请先解锁并开启 NFC");
            return;
        }
        standardSession = true;
        busy = true;
        stopped = cancelled = sessionFailed = refreshComplete = false;
        d1Received = false;
        tagCount = 0;
        uploadRequested = upload;
        setBusy(true);
        transferProgress.setProgress(0);
        transferProgress.setIndeterminate(true);
        state.setText("标准 NFC · 等待驱动板");
        device.setText("不请求 Root、不发送 7A。需系统先发现 ISO-DEP 驱动板；如无响应，并不代表支持专用唤醒。");
        if (upload) uploadImage.setText("等待连接驱动板…");
        log("STANDARD_SESSION · upload=" + upload + " / 不执行 Root 或私有 NCI");
        try {
            if (!enableReader()) { finishStandardSession(); return; }
        } catch (Exception error) {
            sessionFailed = true;
            state.setText("标准 NFC 启动失败");
            log("STANDARD_ERROR · " + error);
            finishStandardSession();
            return;
        }
        logHandler.postDelayed(standardTimeout, upload ? 180000 : 15000);
    }

    private void finishStandardSession() {
        logHandler.removeCallbacks(standardTimeout);
        standardSession = false;
        uploadRequested = false;
        busy = false;
        disableReader();
        transferProgress.setIndeterminate(false);
        if (stopped) state.setText("已停止 · 未修改系统配置");
        setBusy(false);
        uploadImage.setText(preparedImage == null ? "选择图片，开始创作" : refreshComplete ? "再次刷入这幅画面" : "刷入这幅画面");
        log("STANDARD_SESSION_END · confirmed=" + refreshComplete + " / failed=" + sessionFailed);
    }

    private String helperCommand(String mode) {
        return "CLASSPATH=" + quote(getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin dev.inknfclab.RootProbe " + mode
                + " " + quote(new File(getFilesDir(), "polling.snapshot").getAbsolutePath())
                + " " + quote(getApplicationInfo().sourceDir)
                + " " + ((mode.equals("probe") || mode.equals("upload")) ? rfDelta : 0);
    }

    private void startAutomaticCompatibility() {
        if (!usesRootBackend() || busy || compatibilityRunning) return;
        compatibilityRunning = true;
        rootWork.execute(() -> {
            try {
                if (!resumed) return;
                Process process = new ProcessBuilder("su", "-c", helperCommand("compat")).redirectErrorStream(true).start();
                compatibilityProcess = process;
                if (!resumed) requestStop(process);
                boolean ready = false;
                try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        log("AUTO_COMPAT · " + line);
                        try {
                            if (new JSONObject(line).optString("event").equals("COMPAT_READY")) ready = true;
                        } catch (Exception ignored) {}
                    }
                }
                boolean confirmed = ready && process.waitFor() == 0;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    compatibilityState.setText(confirmed
                            ? "MIFARE 兼容已开启 · 不限 UID" : "MIFARE 兼容未就绪 · 查看日志");
                    if (confirmed) compatibilityRetries = 0;
                    else if (resumed && compatibilityRetries++ == 0) {
                        logHandler.removeCallbacks(refreshCompatibility);
                        logHandler.postDelayed(refreshCompatibility, 3000);
                    }
                });
            } catch (Exception error) {
                log("AUTO_COMPAT_FAILED · " + error);
                runOnUiThread(() -> {
                    if (!isDestroyed()) compatibilityState.setText("MIFARE 兼容启动失败 · 查看日志");
                });
            } finally {
                compatibilityProcess = null;
                compatibilityRunning = false;
            }
        });
    }

    private void startHelper(String mode) {
        if (busy || preparing) return;
        if (!usesRootBackend()) {
            state.setText("此机型未开放 Root 私有命令");
            log("ROOT_BLOCKED · " + DeviceSupport.summary(Build.DEVICE, Build.VERSION.SDK_INT, nfc != null));
            return;
        }
        readerAfterReady = mode.equals("probe") || mode.equals("upload");
        uploadRequested = mode.equals("upload");
        busy = true;
        stopped = false;
        cancelled = false;
        sessionFailed = false;
        refreshComplete = false;
        transferProgress.setIndeterminate(readerAfterReady);
        transferProgress.setProgress(0);
        tagCount = 0;
        d1Received = false;
        setBusy(true);
        if (mode.equals("upload")) uploadImage.setText("正在连接驱动板…");
        state.setText("等待 root / 检查接口");
        log("启动辅助进程：" + mode);
        rootWork.execute(() -> {
            try {
                String command = helperCommand(mode);
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                rootProcess = process;
                if (stopped) requestStop(process);
                Thread output = new Thread(() -> {
                    try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null) helperLine(line);
                    } catch (Exception error) { log("日志流关闭：" + error.getMessage()); }
                }, "ink-nfc-output");
                output.start();
                if (!process.waitFor(mode.equals("upload") ? 230 : 90, TimeUnit.SECONDS)) {
                    requestStop(process);
                    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
                    log("辅助进程超时；重新运行恢复入口以核对快照");
                } else {
                    int result = process.exitValue();
                    log("辅助进程返回 " + result);
                    if (result != 0) runOnUiThread(() -> state.setText("验证未通过 · 查看日志与恢复结果"));
                }
                output.join(1500);
            } catch (Exception error) {
                log("root 启动失败：" + error);
                runOnUiThread(() -> state.setText("root 启动失败"));
            }
            finally {
                rootProcess = null;
                busy = false;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    transferProgress.setIndeterminate(false);
                    if (preparedImage != null) uploadImage.setText(refreshComplete ? "再次刷入这幅画面" : "刷入这幅画面");
                    disableReader();
                });
            }
        });
    }

    private void helperLine(String line) {
        try {
            JSONObject data = new JSONObject(line);
            String event = data.getString("event");
            String message = data.getString("message");
            if (event.equals("ERROR") || event.equals("RECOVERY_FAILED")) sessionFailed = true;
            log(event + " · " + message);
            runOnUiThread(() -> {
                if (event.equals("READY")) {
                    if (readerAfterReady) {
                        state.setText("MIFARE 兼容就绪 · 等待驱动板");
                        if (!enableReader()) {
                            uploadRequested = false;
                            sessionFailed = true;
                            requestStop(rootProcess);
                            state.setText("NFC 读卡启动失败 · 已请求恢复");
                        }
                    } else state.setText("7A 配置接受 · 等待驱动板");
                }
                if (event.equals("CHECKED")) state.setText("NCI 查询成功");
                if (event.equals("MIFARE_COMPAT")) compatibilityState.setText("MIFARE 兼容已开启 · 不限 UID");
                if (event.equals("CANCELLED")) state.setText("会话已取消");
                if (!reading && !d1Received && event.equals("HAL_NCI") && message.contains("RAW=6103")) state.setText("发现目标 · 正在连接");
                if (!reading && !d1Received && event.equals("HAL_NCI") && message.contains("RAW=6105")) state.setText("已连接 · 正在读取板子信息");
                if (event.equals("RESTORED")) {
                    transferProgress.setIndeterminate(false);
                    state.setText(sessionFailed ? "操作未完成 · 手机配置已恢复"
                        : stopped ? "已停止 · 手机配置已恢复"
                        : refreshComplete ? "刷屏成功 · 手机配置已恢复"
                        : d1Received ? "驱动板已连接 · 手机配置已恢复"
                        : tagCount > 0 ? "已发现标签 · 未收到 D1 · 已恢复" : "未发现标签 · 配置已恢复");
                }
                if (event.equals("TRACE_SUMMARY") && !d1Received) device.setText(message + "\n标签发现 " + tagCount + " 次；D1 回复 " + (d1Received ? "已收到" : "未收到")
                        + "\n原始记录见“分享底层收发日志”。");
                if (event.equals("ERROR") || event.equals("RECOVERY_FAILED")) state.setText("接口验证未通过 · 查看日志");
            });
        } catch (Exception ignored) { log(line); }
    }

    private void stopSession() {
        stopped = true;
        cancelled = true;
        uploadRequested = false;
        Process process = rootProcess;
        File stopMarker = nxpStopFile;
        if (stopMarker != null) try { stopMarker.createNewFile(); } catch (Exception error) { log("停止标记失败：" + error); }
        if (process != null) requestStop(process);
        disableReader();
        state.setText(standardSession ? "正在停止标准 NFC 会话" : "正在停止并恢复");
        log(standardSession ? "已停止标准 NFC 请求，不修改系统配置" : "已发送停止请求，等待辅助进程恢复原配置");
        if (standardSession && !reading) finishStandardSession();
    }

    private static void requestStop(Process process) {
        try {
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    @Override public void onTagDiscovered(Tag tag) {
        if (reading || !listening || refreshComplete) return;
        reading = true;
        tagCount++;
        tagWork.execute(() -> {
            IsoDep link = null;
            boolean uploadMode = uploadRequested;
            boolean standard = standardSession;
            try {
                log("TAG · UID=" + Protocol.hex(tag.getId()) + " / " + Arrays.toString(tag.getTechList()));
                link = IsoDep.get(tag);
                if (link == null) throw new IllegalStateException("标签没有 ISO-DEP 技术，无法发送 D1");
                connection = link;
                link.connect();
                link.setTimeout(3000);
                log("ISO-DEP 已连接 / maxTransceive=" + link.getMaxTransceiveLength());
                log("D1 TX " + Protocol.hex(Protocol.READ_INFO));
                byte[] response = link.transceive(Protocol.READ_INFO);
                d1Received = true;
                log("D1 RX " + Protocol.hex(response));
                Protocol.DeviceInfo info = Protocol.parseDeviceInfo(response, tag.getId());
                String information = Protocol.deviceInfo(response, tag.getId());
                if (uploadMode) {
                    if (!info.supportsImageTransfer()) throw new IllegalStateException("屏幕型号、压缩能力或 UID 校验不匹配，拒绝写入");
                    ImageCodec.PreparedImage frame = preparedImage;
                    if (frame == null) throw new IllegalStateException("图像预览尚未准备完成");
                    runOnUiThread(() -> {
                        state.setText("设备校验通过 · 正在传图");
                        device.setText(information + "\n连接通过，开始认证与上传；请保持贴合并勿移开。");
                    });
                    log("目标与图像配置校验通过；开始上传 " + frame.packed.length + " 字节，APDU不会自动重放");
                    ImageTransfer.send(link, frame.packed, new ImageTransfer.Progress() {
                        @Override public void update(String message, int completed, int total) {
                            runOnUiThread(() -> {
                                boolean finished = message.equals("屏幕刷新完成");
                                boolean refreshing = completed == total && message.contains("刷新") && !finished;
                                transferProgress.setIndeterminate(refreshing);
                                if (!refreshing) transferProgress.setProgress(finished ? 100 : completed * 85 / Math.max(1, total));
                                uploadImage.setText(refreshing ? "屏幕刷新中 · 请勿移开"
                                        : finished ? "刷新完成" : "传图中 · " + completed * 100 / Math.max(1, total) + "%");
                                state.setText(message);
                                device.setText(refreshing ? "图片已传完，正在等待屏幕刷新确认。请继续保持贴合，不要移开。"
                                        : "上传数据包 " + completed + " / " + total + " · 请保持线圈位置不变");
                            });
                        }
                        @Override public boolean cancelled() { return cancelled || isDestroyed(); }
                        @Override public void trace(String message) { log(message); }
                    });
                    uploadRequested = false;
                    refreshComplete = true;
                    runOnUiThread(() -> {
                        state.setText("屏幕刷新完成");
                        device.setText("板端已返回 009000，确认整幅画面刷新完成。现在可以移开手机。\n" + information);
                    });
                    log("屏幕刷新已由 DE 返回 009000 确认");
                    requestStop(rootProcess);
                } else {
                    runOnUiThread(() -> {
                        state.setText("驱动板信息读取成功");
                        device.setText(information);
                    });
                    log(information.replace('\n', ' '));
                }
            } catch (Exception error) {
                sessionFailed = true;
                uploadRequested = false;
                if (uploadMode) requestStop(rootProcess);
                log("标签读取失败：" + error);
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                runOnUiThread(() -> {
                    state.setText("操作未完成");
                    transferProgress.setIndeterminate(false);
                    device.setText(message + (message.contains("68CA") ? "\n请优先调整线圈耦合位置，保持稳定贴合后重试。"
                            : d1Received ? "\n如果已开始传输，驱动板可能只收到部分图像。" : ""));
                });
            } finally {
                if (link != null) try { link.close(); } catch (Exception ignored) {}
                connection = null;
                reading = false;
                if (standard) runOnUiThread(this::finishStandardSession);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        compatibilityRetries = 0;
        logHandler.removeCallbacks(refreshCompatibility);
        logHandler.postDelayed(refreshCompatibility, 1000);
    }

    @Override protected void onPause() {
        resumed = false;
        logHandler.removeCallbacks(refreshCompatibility);
        if (compatibilityProcess != null) requestStop(compatibilityProcess);
        if (busy) stopSession();
        else disableReader();
        super.onPause();
    }

    @Override protected void onDestroy() {
        logHandler.removeCallbacks(standardTimeout);
        logHandler.removeCallbacks(refreshCompatibility);
        if (compatibilityProcess != null) requestStop(compatibilityProcess);
        if (rootProcess != null) requestStop(rootProcess);
        rootWork.shutdown();
        tagWork.shutdown();
        imageWork.shutdownNow();
        super.onDestroy();
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedImage = data.getData();
            rotation = 0;
            showOriginal = false;
            prepareSelectedImage();
        }
    }

    private void prepareSelectedImage() {
        Uri image = selectedImage;
        if (image == null || busy) return;
        int generation = ++imageGeneration;
        boolean cover = coverMode;
        int algorithm = selectedAlgorithm;
        int angle = rotation, mode = imageMode, lightness = brightness, gain = contrast;
        double strength = ditherStrength;
        preparing = true;
        preparedImage = null;
        setBusy(false);
        state.setText("正在处理并量化图片");
        uploadImage.setText("正在生成四色预览…");
        imageWork.execute(() -> {
            if (generation != imageGeneration || isDestroyed()) return;
            Bitmap source = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (java.io.InputStream input = getContentResolver().openInputStream(image)) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 100000000L)
                    throw new IllegalArgumentException("图片尺寸无效或过大");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth / options.inSampleSize, bounds.outHeight / options.inSampleSize) > 4096)
                    options.inSampleSize *= 2;
                if (Build.VERSION.SDK_INT >= 28) {
                    final int sampleSize = options.inSampleSize;
                    source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), image), (decoder, info, encoded) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        decoder.setTargetSampleSize(sampleSize);
                    });
                } else {
                    try (java.io.InputStream input = getContentResolver().openInputStream(image)) {
                        source = BitmapFactory.decodeStream(input, null, options);
                    }
                }
                long started = android.os.SystemClock.elapsedRealtime();
                ImageCodec.PreparedImage result = ImageCodec.prepare(source, cover, angle, mode, lightness, gain, strength, algorithm);
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                if (source != null) source.recycle();
                runOnUiThread(() -> {
                    if (generation != imageGeneration || isDestroyed()) {
                        result.preview.recycle();
                        result.originalPreview.recycle();
                        return;
                    }
                    preparedImage = result;
                    preparing = false;
                    showOriginal = false;
                    updatePreview();
                    setBusy(false);
                    uploadImage.setText("刷入这幅画面");
                    state.setText("预览就绪 · 尚未写入驱动板");
                    device.setText("满意后贴近驱动板，点击底部刷屏。刷新期间请保持线圈耦合位置不变。");
                    imageSummary.setText(AlgorithmCatalog.name(algorithm) + " · " + modeName(mode) + " · " + (cover ? "填满裁切" : "完整显示")
                            + " · " + angle + "° · " + elapsed + " ms\n四色点阵为实际发送内容；实屏颜色、对比度取决于面板。 ");
                    log("图片编码完成 · algorithm=" + algorithm + "/" + AlgorithmCatalog.name(algorithm)
                            + " / packed=" + result.packed.length + " / mode=" + mode + " / cover=" + cover
                            + " / rotation=" + angle + " / brightness=" + lightness + " / contrast=" + gain + " / dither=" + strength + " / elapsed=" + elapsed);
                });
            } catch (Exception error) {
                if (source != null && !source.isRecycled()) source.recycle();
                runOnUiThread(() -> {
                    if (generation != imageGeneration || isDestroyed()) return;
                    preparing = false;
                    setBusy(false);
                    uploadImage.setText("请重新选择图片");
                    state.setText("图片处理失败");
                    device.setText(error.getMessage() == null ? error.toString() : error.getMessage());
                });
            }
        });
    }

    private void confirmUpload() {
        if (busy || preparing || preparedImage == null) {
            state.setText("请先选择图片");
            return;
        }
        if (!standardOnly) {
            if (nfcVendor == NfcVendor.Kind.NXP) { chooseNxpBackend(); return; }
            if (nfcVendor == NfcVendor.Kind.THN31) { chooseThn31Backend(); return; }
            if (nfcVendor == NfcVendor.Kind.ST) { chooseStBackend(); return; }
            new AlertDialog.Builder(this).setTitle("NFC 控制器未能可靠识别")
                    .setMessage("不会仅凭系统中存在配置文件判断厂商。可尝试 NXP Q-Poll，或使用电脑 PN532/PCR532 方案；未经验证的 ST 机型不能自动套用小米 17 补丁。")
                    .setItems(new String[]{"尝试 NXP 路线", "标准 ISO-DEP（不唤醒）", "电脑 PN532/PCR532 项目"},
                            (dialog, which) -> {
                                if (which == 0) chooseNxpBackend();
                                else if (which == 1) confirmStandardUpload();
                                else openPn532Project();
                            }).show();
            return;
        }
        confirmStandardUpload();
    }

    private void chooseStBackend() {
        String detail = usesRootBackend() ? "已验证小米 17 / ST54L Root 路线。" : "当前 ST 机型尚未验证 Root 兼容；不能直接套用小米 17 的底层补丁。";
        new AlertDialog.Builder(this).setTitle("ST 控制器")
                .setMessage(detail + "\n如不具备 Root 或机型尚未适配，可改用电脑 PN532/PCR532。")
                .setPositiveButton(usesRootBackend() ? "使用 Root 刷写" : "了解", (dialog, which) -> {
                    if (usesRootBackend()) confirmRootUpload();
                })
                .setNeutralButton("电脑方案", (dialog, which) -> openPn532Project())
                .setNegativeButton("取消", null).show();
    }

    private void chooseNxpBackend() {
        new AlertDialog.Builder(this).setTitle("NXP Q-Poll 刷写")
                .setMessage("ADB Shell 不需要 Root，但需在电脑执行一次命令；Root 可由 App 直接启动。两条路线均会发送 NXP 私有 QTag 命令，成功发现驱动板后才发送 D1、图像与刷新指令。尚未在本机型实测，请保持贴合并留意恢复结果。")
                .setPositiveButton("ADB Shell", (dialog, which) -> prepareNxpAdb())
                .setNeutralButton("Root", (dialog, which) -> startNxpRoot())
                .setNegativeButton("取消", null).show();
    }

    private void chooseThn31Backend() {
        new AlertDialog.Builder(this).setTitle("THN31 Root 刷写")
                .setMessage("THN31 Root 路线不限制手机型号或 Android 版本，运行前会检查所需系统接口。将覆盖屏幕画面；请保持手机亮屏解锁及屏幕贴合。也可先仅读取 D1，不写图像。")
                .setPositiveButton("确认覆盖并刷写", (dialog, which) -> startRootBackend(true, false))
                .setNeutralButton("仅读 D1", (dialog, which) -> startRootBackend(true, true))
                .setNegativeButton("取消", null).show();
    }

    private File prepareNxpImage() throws Exception {
        return prepareBackendImage(false);
    }

    private File prepareBackendImage(boolean thn31) throws Exception {
        ImageCodec.PreparedImage frame = preparedImage;
        if (frame == null) throw new IllegalStateException("请先准备图片");
        File folder = thn31 ? getFilesDir() : getExternalFilesDir(null);
        if (folder == null) throw new IllegalStateException("外部应用目录不可用，无法交给 ADB Shell");
        File image = new File(folder, thn31 ? "thn31-image.bin" : "nxp-image.bin");
        try (FileOutputStream output = new FileOutputStream(image)) { output.write(frame.packed); }
        File stop = new File(folder, thn31 ? "thn31-stop" : "nxp-stop");
        if (stop.exists() && !stop.delete()) throw new IllegalStateException("无法清除上次停止标记");
        nxpStopFile = stop;
        return image;
    }

    private void prepareNxpAdb() {
        try {
            File image = prepareNxpImage();
            String stop = nxpStopFile.getAbsolutePath();
            String command = "adb shell 'apk=$(pm path dev.inknfclab | head -n 1); apk=${apk#package:}; "
                    + "CLASSPATH=\"$apk\" /system/bin/app_process /system/bin dev.inknfclab.NxpQPollWorker "
                    + image.getAbsolutePath() + " " + stop + "'";
            state.setText("NXP 图像已准备 · 等待 ADB Shell");
            device.setText("在电脑终端执行弹窗中的命令。App 不会误报 ADB 刷屏成功；请以终端的 COMPLETE / END 为准。");
            new AlertDialog.Builder(this).setTitle("在电脑执行 ADB 命令")
                    .setMessage(command + "\n\n也可在源码仓库运行 tools/nxp-adb-refresh.sh。要中止可在电脑执行 adb shell touch '" + stop + "'。ADB Shell 需能读取 App 外部目录；如果被系统拒绝，请使用 Root 或电脑 PN532。")
                    .setPositiveButton("复制命令", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("NXP ADB 刷屏", command));
                    }).setNegativeButton("关闭", null).show();
            log("NXP_ADB_READY · " + image.getAbsolutePath());
        } catch (Exception error) {
            state.setText("ADB 图像准备失败");
            device.setText(error.getMessage());
            log("NXP_ADB_ERROR · " + error);
        }
    }

    private void startNxpRoot() {
        startRootBackend(false, false);
    }

    private void startRootBackend(boolean thn31, boolean readOnly) {
        if (busy) return;
        final String label = thn31 ? "THN31" : "NXP";
        try {
            File image = prepareBackendImage(thn31);
            String command = "CLASSPATH='" + getPackageCodePath().replace("'", "'\\''")
                    + "' " + (thn31 ? "/system/bin/nsenter -t 1 -m " : "")
                    + "/system/bin/app_process /system/bin "
                    + (thn31 ? "dev.inknfclab.thn31.Thn31Worker" : "dev.inknfclab.NxpQPollWorker") + " '"
                    + (readOnly ? "--read" : image.getAbsolutePath().replace("'", "'\\''")) + "' '"
                    + nxpStopFile.getAbsolutePath().replace("'", "'\\''") + "'";
            busy = true;
            stopped = cancelled = false;
            setBusy(true);
            state.setText(label + " Root · 等待驱动板");
            rootWork.execute(() -> {
                try {
                    Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                    rootProcess = process;
                    Thread watchdog = new Thread(() -> {
                        try {
                            if (!process.waitFor(330, TimeUnit.SECONDS)) {
                                File marker = nxpStopFile;
                                if (marker != null) marker.createNewFile();
                                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
                            }
                        } catch (Exception error) { log(label + "_WATCHDOG · " + error); }
                    }, "ink-" + label.toLowerCase(Locale.ROOT) + "-watchdog");
                    watchdog.setDaemon(true);
                    watchdog.start();
                    try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            log(line);
                            String event = line;
                            runOnUiThread(() -> {
                                if (event.startsWith("THN31|COMPLETE|")) state.setText(readOnly ? "THN31 D1 校验通过" : "THN31 刷屏完成 · 请目视确认");
                                else if (event.startsWith("THN31|ERROR|")) { state.setText("THN31 会话失败"); device.setText(event); }
                                else if (event.startsWith("THN31|READY|")) state.setText("THN31 · 请保持贴合");
                                else if (event.startsWith("THN31|PROGRESS|")) state.setText(event.substring(15));
                                else if (event.startsWith("NXP|COMPLETE|")) state.setText("NXP 刷屏完成");
                                else if (event.startsWith("NXP|ERROR|")) { state.setText("NXP 刷写失败"); device.setText(event); }
                                else if (event.startsWith("NXP|READY|")) state.setText("NXP QTag 已接受 · 等待驱动板");
                                else if (event.startsWith("NXP|PROGRESS|")) state.setText(event.substring(13));
                            });
                        }
                    }
                    if (!process.waitFor(180, TimeUnit.SECONDS)) {
                        nxpStopFile.createNewFile();
                        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
                    }
                    if (process.exitValue() != 0) runOnUiThread(() -> state.setText(label + " 会话未完成 · 请查看日志"));
                } catch (Exception error) {
                    log(label + "_ROOT_ERROR · " + error);
                    runOnUiThread(() -> { state.setText(label + " Root 启动失败"); device.setText(error.toString()); });
                } finally {
                    rootProcess = null;
                    nxpStopFile = null;
                    busy = false;
                    runOnUiThread(() -> { if (!isDestroyed()) setBusy(false); });
                }
            });
        } catch (Exception error) {
            state.setText(label + " 图像准备失败");
            device.setText(error.getMessage());
        }
    }

    private void openPn532Project() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/solar2ain/ink-nfc-fmsc-by-pn532")));
    }

    private void confirmRootUpload() {
        new AlertDialog.Builder(this).setTitle("确认覆盖墨水屏")
                .setMessage("使用已验证的 ST Root 路线刷入当前预览。请保持线圈贴合直至刷新确认。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认刷图", (dialog, which) -> startHelper("upload")).show();
    }

    private void confirmStandardUpload() {
        new AlertDialog.Builder(this).setTitle("确认覆盖墨水屏")
                .setMessage("当前算法：" + AlgorithmCatalog.name(preparedImage.algorithm)
                        + "\n连接方式：标准 NFC（不能保证 7A 唤醒）"
                        + "\n将用当前四色点阵覆盖墨水屏，约476个数据包，传完后仍需等待屏幕刷新确认。\n\n请贴在已验证的线圈位置，期间保持不动。原图对照仅用于查看，不会作为发送数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认刷图", (dialog, which) -> {
                    uploadRequested = true;
                    state.setText("等待标准 NFC 标签");
                    startStandardSession(true);
                }).show();
    }

    private String modeName(int mode) {
        return new String[]{"照片", "插画", "黑白"}[mode];
    }

    private void chooseImageMode() {
        new AlertDialog.Builder(this).setTitle("选择画面风格")
                .setItems(new String[]{"照片 · 柔和抖动，保留层次", "插画 · 纯色边缘，适合海报文字", "黑白 · 保留明暗，不使用红黄"}, (dialog, which) -> {
                    imageMode = which;
                    ditherStrength = which == ImageQuantizer.ARTWORK ? 0 : which == ImageQuantizer.MONO ? 1 : .85;
                    updateImageControls();
                    prepareSelectedImage();
                }).setNegativeButton("取消", null).show();
    }

    private void updateImageControls() {
        algorithmChoice.setText("算法 · " + AlgorithmCatalog.name(selectedAlgorithm));
        modeChoice.setText("模式 · " + modeName(imageMode));
        fitChoice.setText(coverMode ? "构图 · 填满" : "构图 · 完整");
        ditherChoice.setText(selectedAlgorithm == AlgorithmCatalog.CLEAN ? "抖动 · 此算法关闭"
                : "抖动 · " + (ditherStrength == 0 ? "关闭" : ditherStrength == 1 ? "细腻" : "柔和"));
        ditherChoice.setEnabled(!busy && selectedAlgorithm != AlgorithmCatalog.CLEAN);
    }

    private void updatePreview() {
        ImageCodec.PreparedImage frame = preparedImage;
        if (frame == null) return;
        preview.setImageBitmap(showOriginal ? frame.originalPreview : frame.preview);
        preview.setContentDescription(showOriginal ? "原图构图对照，不是发送内容" : "即将发送的552×768四色点阵");
        previewCaption.setText(AlgorithmCatalog.name(frame.algorithm) + (showOriginal
                ? " · 原图对照，不是发送内容" : " · 四色点阵，与发送数据一致"));
        compareImage.setText(showOriginal ? "返回墨水屏预览" : "查看原图");
    }

    private SeekBar imageSlider(LinearLayout parent, String name, int maximum, int initial,
                                java.util.function.IntConsumer change) {
        TextView label = text(name + " · 0", 12, 0xffa9bec9);
        label.setPadding(0, dp(10), 0, 0);
        parent.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setMax(maximum);
        slider.setProgress(initial);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(name + " · " + (value - initial > 0 ? "+" : "") + (value - initial));
                change.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { prepareSelectedImage(); }
        });
        return slider;
    }

    private synchronized void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message + "\n";
        logText.append(line);
        if (logText.length() > 40000) logText.delete(0, logText.length() - 32000);
        try (FileOutputStream output = new FileOutputStream(sessionLog, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        if (!logUpdatePending) {
            logUpdatePending = true;
            logHandler.postDelayed(this::renderLog, 200);
        }
    }

    private void renderLog() {
        String visible;
        synchronized (this) {
            logUpdatePending = false;
            if (isDestroyed()) return;
            visible = logText.substring(Math.max(0, logText.length() - 12000));
        }
        logs.setText(visible);
        if (logScroll.isShown()) logScroll.post(() -> logScroll.scrollTo(0, logs.getHeight()));
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Ink NFC Lab 会话日志");
        synchronized (this) { send.putExtra(Intent.EXTRA_TEXT, logText.toString()); }
        startActivity(Intent.createChooser(send, "分享日志"));
    }

    private void shareTrace() {
        File trace = new File(getFilesDir(), "polling.snapshot.trace.txt");
        try {
            if (busy) throw new IllegalStateException("请等待探测结束后分享");
            String raw = new String(Files.readAllBytes(trace.toPath()), StandardCharsets.UTF_8);
            if (raw.length() > 160000) raw = "仅分享末尾 160000 字符；完整文件保存在 App 私有目录。\n" + raw.substring(raw.length() - 160000);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Ink NFC Lab ST HAL 原始日志");
            send.putExtra(Intent.EXTRA_TEXT, raw);
            startActivity(Intent.createChooser(send, "分享底层日志（可能含附近标签数据）"));
        } catch (Exception error) { log("底层日志分享失败：" + error.getMessage()); }
    }

    private void setBusy(boolean active) {
        active = active || busy;
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
        connectionChoice.setEnabled(!active);
        rfChoice.setEnabled(!active && usesRootBackend());
        rfInspect.setEnabled(!active && !preparing && usesRootBackend());
        inspect.setEnabled(!active && !preparing && usesRootBackend());
        probe.setEnabled(!active && !preparing && usesRootBackend());
        normal.setEnabled(!active && !preparing);
        chooseImage.setEnabled(!active);
        modeChoice.setEnabled(!active);
        algorithmChoice.setEnabled(!active);
        fitChoice.setEnabled(!active);
        ditherChoice.setEnabled(!active && selectedAlgorithm != AlgorithmCatalog.CLEAN);
        rotateImage.setEnabled(!active);
        compareImage.setEnabled(!active && !preparing && preparedImage != null);
        resetImage.setEnabled(!active);
        brightnessSlider.setEnabled(!active);
        contrastSlider.setEnabled(!active);
        uploadImage.setEnabled(!active && !preparing);
        uploadImage.setAlpha(uploadImage.isEnabled() ? 1 : .45f);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(rounded(SURFACE, 0xff30424c));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(14);
        parent.addView(card, layout);
        return card;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(0xff25343d, 0xff3c515e));
        button.setMinWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams layout = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, dp(48), 1) : new LinearLayout.LayoutParams(-1, dp(48));
        layout.topMargin = dp(6);
        layout.rightMargin = parent.getOrientation() == LinearLayout.HORIZONTAL ? dp(5) : 0;
        parent.addView(button, layout);
        return button;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(14));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1);
        return text;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}

package com.nura.ld36diagnostic;

import android.app.Activity;
import android.content.res.Configuration;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NURA LD36 Diagnostic v0.1: passive AOA receive-only capture.
 * No MediaProjection, no privileged APIs, no root and NO outgoing protocol data.
 */
public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.nura.ld36diagnostic.USB_PERMISSION";
    private static final int REQ_EXPORT_TEXT = 4001;
    private static final int REQ_PROJECTION = 4004;
    private static final long TX_BIN_LIMIT = 32L * 1024 * 1024;
    private static final int REQ_EXPORT_RAW = 4002;
    private static final int RAW_CAPTURE_LIMIT = 8 * 1024 * 1024;
    private static final int TEXT_CAPTURE_LIMIT = 3 * 1024 * 1024;
    private static final int PREVIEW_LIMIT = 4096;

    private static final int BG = Color.rgb(16, 25, 35);
    private static final int PANEL = Color.rgb(29, 42, 54);
    private static final int INK = Color.rgb(235, 243, 247);
    private static final int MUTED = Color.rgb(161, 183, 197);
    private static final int ACCENT = Color.rgb(88, 223, 214);
    private static final int WARNING = Color.rgb(255, 196, 103);

    private UsbManager usbManager;
    private UsbAccessory accessory;
    private ParcelFileDescriptor descriptor;
    private InputStream input;
    private FileOutputStream usbOutput;
    private final Object sendLock = new Object();
    private volatile boolean videoModeRequested;
    private boolean fillTvSelected;
    private boolean directTvSelected;
    private boolean autoOriginalSelected;
    private boolean vdatAngleSelected;
    private boolean invertedAngleSelected;
    private boolean landscapeStartSelected;
    private boolean nativeOrientationStartSelected;
    private boolean manualAngleTestSelected;
    private boolean nativeAspectTestSelected;
    private boolean safeAutoHandoffSelected;
    private boolean nativePortraitAspectTestSelected;
    private boolean dualNativeSafeSelected;
    private boolean liveNativeSelected;
    private boolean fixedCanvasSelected;
    private boolean smoothCanvasSelected;
    private volatile boolean initialProfilePortrait;
    private volatile boolean handoffPending;
    private volatile int handoffTargetSession;
    private volatile boolean handoffTargetPortrait;
    private volatile int manualVdatAngle = 0;
    private volatile long fpsWindowStartMs;
    private volatile long fpsWindowPackets;
    private volatile long fpsWindowBytes;
    private volatile int profileInitialEncoderWidth;
    private volatile int profileInitialEncoderHeight;
    private volatile int vdatAngleDegrees;
    private volatile int vdatAngleChanges;
    private volatile long vdatAngleFrames0, vdatAngleFrames90, vdatAngleFrames180, vdatAngleFrames270;
    private volatile long vdatAngleConfigs, vdatAngleIdrs;
    private volatile int vdatAngleAwaitFirstPacket = -1;
    private volatile boolean videoStarted;
    private volatile int videoMagic;
    private Intent projectionConsent;
    private int projectionResult;
    private long videoFrames;
    private long videoBytes;
    private boolean txLimitWarned;
    private File txFile;
    private boolean activeScanRequested = false;
    private boolean activeScan = false;
    private Thread captureThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int generation = 0;
    private long totalBytes = 0;
    private long chunks = 0;
    private File textFile;
    private File rawFile;
    private final StringBuilder visibleLog = new StringBuilder();
    private TextView statusView;
    private TextView deviceView;
    private TextView metricsView;
    private TextView logView;
    private Button connectButton;
    private Button stopButton;
    private static final long AUTO_SCAN_MS = 60_000L;
    private boolean autoScanRequested = false;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbAccessory grantedAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (granted && grantedAccessory != null && usbManager.hasPermission(grantedAccessory)) {
                    accessory = grantedAccessory;
                    appendLog("Permissão AOA concedida pelo usuário.");
                    openAccessory();
                } else {
                    setStatus("Permissão USB negada", WARNING);
                    appendLog("Permissão AOA não concedida ou acessório indisponível.");
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                appendLog("Evento: acessório USB desconectado.");
                stopCapture();
                discoverAccessory();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbEvents, filter);
        }
        appendLog("NURA LD36 Diagnostic v2.0.0: detecção de orientação e troca segura de sessão.");
        appendLog("Modo passivo: somente RX. Nenhum comando será enviado ao dongle.");
        appendLog("Feche o aplicativo Mirror original antes de conectar o LD36.");
        discoverAccessory();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        appendLog("Evento de conexão AOA recebido pelo Android.");
        discoverAccessory();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!running.get()) discoverAccessory();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        appendLog("Android rotacionou: " + (config.orientation == Configuration.ORIENTATION_LANDSCAPE
            ? "HORIZONTAL" : "VERTICAL") +
            (directTvSelected ? ". TV DIRETO: compositor do Android mantém surface fixa." :
                ". Solicitando atualizacao da superficie."));
        if (videoStarted) {
            Intent refresh = new Intent(this, MirrorProjectionService.class);
            refresh.setAction(MirrorProjectionService.ACTION_REFRESH);
            try { startService(refresh); }
            catch (RuntimeException e) { appendLog("Falha ao atualizar superficie: " + e.getMessage()); }
        }
    }

    @Override protected void onDestroy() {
        stopCapture();
        unregisterReceiver(usbEvents);
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(18), dp(23), dp(18), dp(28));
        root.setOrientation(LinearLayout.VERTICAL);
        rootScroll.addView(root);
        setContentView(rootScroll);

        TextView brand = text("NURA  /  LAB", 13, ACCENT, Typeface.BOLD);
        brand.setLetterSpacing(0.17f);
        root.addView(brand);
        TextView title = text("LD36 Diagnostic", 28, INK, Typeface.BOLD);
        title.setPadding(0, dp(7), 0, dp(5));
        root.addView(title);
        TextView subtitle = text("VERSÃO 2.5.0 • GIRO SUAVE EXPERIMENTAL", 13, ACCENT, Typeface.BOLD);
        root.addView(subtitle);

        LinearLayout statusCard = columnCard();
        addToRoot(root, statusCard, 18);
        statusCard.addView(text("STATUS DO ADAPTADOR", 11, MUTED, Typeface.BOLD));
        statusView = text("Aguardando LD36", 19, WARNING, Typeface.BOLD);
        statusView.setPadding(0, dp(8), 0, dp(6));
        statusCard.addView(statusView);
        deviceView = text("Nenhum acessório AOA detectado", 12, MUTED, Typeface.NORMAL);
        statusCard.addView(deviceView);

        LinearLayout commands = new LinearLayout(this);
        commands.setOrientation(LinearLayout.HORIZONTAL);
        addToRoot(root, commands, 14);
        Button scanButton = button("Detectar", PANEL, INK);
        connectButton = button("Conectar RX", ACCENT, BG);
        stopButton = button("Parar", PANEL, INK);
        Button autoButton = button("▶ VARREDURA PASSIVA (60s)", ACCENT, BG);
        Button activeButton = button("▶ NEGOCIAR PROTOCOLO (60s)", PANEL, INK);
        Button videoButton = button("▶ ESPELHAR VÍDEO (TESTE 90s)", ACCENT, BG);
        stopButton.setEnabled(false);
        addButton(commands, scanButton);
        addButton(commands, connectButton);
        addButton(commands, stopButton);
        addToRoot(root, autoButton, 14);
        addToRoot(root, activeButton, 8);
        TextView fitLabel = text("AJUSTE NA TV", 12, ACCENT, Typeface.BOLD);
        addToRoot(root, fitLabel, 16);
        Spinner fitPicker = new Spinner(this);
        String[] fitting = {
            "Original v0.4 — captura direta antiga",
            "GPU v0.9 — diagnóstico manual (distorção conhecida)",
            "TV DIRETO v1.0 — sem imagem (diagnóstico)",
            "AUTO ORIGINAL v1.1 — troca codec (congela, evitar)",
            "VDAT ANGLE v1.4 — giro anterior (referência)",
            "VDAT INVERTIDO v1.5 — inverter orientação na TV",
            "PAISAGEM NA PARTIDA v1.6 — 1280x720 desde o início",
            "ORIENTAÇÃO NATURAL v1.7 — sem forçar giro do aplicativo",
            "VDAT 0° / 90° v1.8 — comparar rotação sem reiniciar vídeo",
            "PROPORÇÃO NATIVA v1.9 — H264 20:9 + VDAT 90°",
            "AUTO SEGURO v2.0 — vertical/horizontal por sessão",
            "RETRATO NATIVO v2.1 — testar largura 20:9",
            "AUTO NATIVO v2.2 — perfis aprovados + giro seguro",
            "GIRO CONTÍNUO v2.3 — NÃO USAR (travou em teste)",
            "CANVAS FIXO v2.4 — render GPU, H264 estável (experimental)",
            "GIRO SUAVE v2.5 — segurar quadro durante troca de proporção"
        };
        ArrayAdapter<String> fitAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, fitting);
        fitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fitPicker.setAdapter(fitAdapter);
        fitPicker.setBackgroundColor(PANEL);
        // New mode is opt-in: known-working Original remains safe initial selection.
        int mode = getPreferences(MODE_PRIVATE).getInt("capture_mode_v25",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v24",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v23",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v22",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v21",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v20",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v19",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v18",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v17",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v16",
            getPreferences(MODE_PRIVATE).getInt("capture_mode_v15",
                getPreferences(MODE_PRIVATE).getInt("capture_mode_v13", 0))))))))))));
        if (mode == 13) mode = 12; // Never restore crash-prone v2.3 on launch
        if (mode < 0 || mode > 15) mode = 0;
        fillTvSelected = mode == 1;
        directTvSelected = mode == 2;
        autoOriginalSelected = mode == 3;
        vdatAngleSelected = mode >= 4 && mode <= 15;
        invertedAngleSelected = mode == 5 || mode == 6 || mode == 7;
        landscapeStartSelected = mode == 6;
        nativeOrientationStartSelected = mode >= 7 && mode <= 15;
        manualAngleTestSelected = mode == 8;
        nativeAspectTestSelected = mode == 9;
        safeAutoHandoffSelected = mode == 10;
        nativePortraitAspectTestSelected = mode == 11;
        dualNativeSafeSelected = mode == 12;
        liveNativeSelected = false; // physically failed v2.3: disabled
        fixedCanvasSelected = mode == 14 || mode == 15;
        smoothCanvasSelected = mode == 15;
        fitPicker.setSelection(mode);
        fitPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean gpu = position == 1;
                boolean direct = position == 2;
                boolean auto = position == 3;
                if(position == 13) {
                    appendLog("v2.3 BLOQUEADA: travamento confirmado no teste físico. Escolha v2.2 ou canvas v2.4.");
                    fitPicker.setSelection(12);
                    return;
                }
                boolean angle = position >= 4 && position <= 15;
                boolean inverted = position == 5 || position == 6 || position == 7;
                boolean landscape = position == 6;
                boolean nativeOrientation = position >= 7 && position <= 15;
                boolean manualAngle = position == 8;
                boolean nativeAspect = position == 9;
                boolean safeAuto = position == 10;
                boolean nativePortrait = position == 11;
                boolean dualNative = position == 12;
                boolean liveNative = false;
                boolean fixedCanvas = position == 14 || position == 15;
                boolean smoothCanvas = position == 15;
                boolean changed = fillTvSelected != gpu ||
                    directTvSelected != direct ||
                    autoOriginalSelected != auto || vdatAngleSelected != angle ||
                    invertedAngleSelected != inverted || landscapeStartSelected != landscape ||
                    nativeOrientationStartSelected != nativeOrientation ||
                    manualAngleTestSelected != manualAngle || nativeAspectTestSelected != nativeAspect ||
                    safeAutoHandoffSelected != safeAuto ||
                    nativePortraitAspectTestSelected != nativePortrait ||
                    dualNativeSafeSelected != dualNative ||
                    liveNativeSelected != liveNative ||
                    fixedCanvasSelected != fixedCanvas ||
                    smoothCanvasSelected != smoothCanvas;
                fillTvSelected = gpu;
                directTvSelected = direct;
                autoOriginalSelected = auto;
                vdatAngleSelected = angle;
                invertedAngleSelected = inverted;
                landscapeStartSelected = landscape;
                nativeOrientationStartSelected = nativeOrientation;
                manualAngleTestSelected = manualAngle;
                nativeAspectTestSelected = nativeAspect;
                safeAutoHandoffSelected = safeAuto;
                nativePortraitAspectTestSelected = nativePortrait;
                dualNativeSafeSelected = dualNative;
                liveNativeSelected = liveNative;
                fixedCanvasSelected = fixedCanvas;
                smoothCanvasSelected = smoothCanvas;
                getPreferences(MODE_PRIVATE).edit().putInt("capture_mode_v25", position).apply();
                if (nativeOrientation && !running.get()) {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    appendLog("v1.7: orientação do aplicativo liberada. "+
                        "O codificador usará apenas a orientação física no início.");
                }
                if (changed) appendLog("Captura selecionada: " +
                    (smoothCanvas ? "v2.5 GIRO SUAVE: segurar quadro anterior até GPU estabilizar" :
                    (fixedCanvas ? "v2.4 CANVAS FIXO: mantém H264/USB e redimensiona só a captura na GPU" :
                    (liveNative ? "v2.3 LIVE DESABILITADO" :
                    (dualNative ? "v2.2 AUTO NATIVO: retrato 576x1280, paisagem 1280x576; novo consentimento após giro" :
                    (nativePortrait ? "v2.1 RETRATO NATIVO - H264 proporcional sem escala dupla" :
                    (safeAuto ? "v2.0 AUTO SEGURO - seleciona perfil inicial e pede nova permissao ao girar" :
                    (nativeAspect ? "v1.9 PROPORCAO NATIVA - VDAT 90, H264 altura proporcional" :
                    (manualAngle ? "v1.8 ANGULO MANUAL - video permanece no mesmo codec; toque 0/90" :
                    (nativeOrientation ? "v1.7 NATURAL - escolhe codec pelo display REAL no início; não força rotação" :
                    (landscape ? "PAISAGEM NA PARTIDA v1.6 - 1280x720 inicial, sem troca ao vivo" :
                    (inverted ? "VDAT INVERTIDO v1.5 - 0/90 trocados" :
                    (angle ? "VDAT ANGLE v1.4 - mapeamento anterior" :
                        (auto ? "AUTO ORIGINAL v1.1 (sujeito a congelar)" :
                            (direct ? "TV DIRETO v1.0 (sem imagem no teste)" :
                                (gpu ? "GPU experimental anterior" : "Original v0.4"))))))))))))))) +
                    ". Valido apenas na proxima sessao.");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        addToRoot(root, fitPicker, 7);
        addToRoot(root, videoButton, 12);
        Button testAngle0 = button("VDAT 0° (v1.8)", PANEL, INK);
        Button testAngle90 = button("VDAT 90° (v1.8)", PANEL, INK);
        LinearLayout angleButtons = new LinearLayout(this);
        angleButtons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(angleButtons, testAngle0);
        addButton(angleButtons, testAngle90);
        addToRoot(root, angleButtons, 8);
        testAngle0.setOnClickListener(v -> chooseManualVdatAngle(0));
        testAngle90.setOnClickListener(v -> chooseManualVdatAngle(90));
        Button rotateButton = button("↻ GIRAR IMAGEM DA TV 90°", PANEL, INK);
        addToRoot(root, rotateButton, 8);
        Button resetGeometryButton = button("REDEFINIR PROPORÇÃO / GIRO 0°", PANEL, INK);
        addToRoot(root, resetGeometryButton, 8);
        resetGeometryButton.setOnClickListener(v -> {
            if (!running.get() || !videoStarted || !fillTvSelected) {
                appendLog("Redefinir: inicie antes o modo Rotacao experimental.");
                return;
            }
            Intent reset = new Intent(this, MirrorProjectionService.class);
            reset.setAction(MirrorProjectionService.ACTION_RESET);
            try {
                startService(reset);
                appendLog("Solicitado RESET de proporcao e giro manual.");
            } catch (RuntimeException e) {
                appendLog("Erro ao redefinir geometria: " + e.getMessage());
            }
        });
        rotateButton.setOnClickListener(v -> {
            if (!running.get() || !videoStarted || !fillTvSelected) {
                appendLog("Giro 90: inicie antes o modo Rotacao experimental.");
                return;
            }
            Intent rotate = new Intent(this, MirrorProjectionService.class);
            rotate.setAction(MirrorProjectionService.ACTION_ROTATE);
            try {
                startService(rotate);
                appendLog("Solicitado giro 90 na textura da TV, sem reiniciar USB.");
            } catch (RuntimeException e) {
                appendLog("Erro no ajuste de orientacao: "+e.getMessage());
            }
        });
        videoButton.setOnClickListener(v -> requestVideoConsent());
        scanButton.setOnClickListener(v -> discoverAccessory());
        connectButton.setOnClickListener(v -> connectWithPermission());
        stopButton.setOnClickListener(v -> stopCapture());
        activeButton.setOnClickListener(v -> {
            videoModeRequested = false;
            if (running.get()) {
                appendLog("Encerre a captura anterior antes de iniciar a negociação.");
                return;
            }
            activeScanRequested = true;
            autoScanRequested = true;
            connectWithPermission();
        });
        autoButton.setOnClickListener(v -> {
            videoModeRequested = false;
            activeScanRequested = false;
            if (running.get()) {
                appendLog("Já existe uma captura ativa. Pare-a antes da varredura automática.");
                return;
            }
            // Set before requesting permission: openAccessory can be synchronous.
            autoScanRequested = true;
            connectWithPermission();
        });

        LinearLayout metricsCard = columnCard();
        addToRoot(root, metricsCard, 14);
        metricsCard.addView(text("CAPTURA", 11, MUTED, Typeface.BOLD));
        metricsView = text("0 bytes  •  0 leituras USB", 15, INK, Typeface.BOLD);
        metricsView.setPadding(0, dp(8), 0, 0);
        metricsCard.addView(metricsView);
        metricsCard.addView(text("Os arquivos RX são limitados a 8 MiB por sessão.", 11, MUTED, Typeface.NORMAL));

        LinearLayout logCard = columnCard();
        addToRoot(root, logCard, 14);
        logCard.addView(text("MONITOR DE PROTOCOLO (RX)", 11, MUTED, Typeface.BOLD));
        logView = text("", 11, INK, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(12), 0, dp(8));
        logCard.addView(logView);

        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        addToRoot(root, exportRow, 14);
        Button exportText = button("Exportar TXT", PANEL, INK);
        Button exportBin = button("Exportar BIN", PANEL, INK);
        addButton(exportRow, exportText);
        addButton(exportRow, exportBin);
        Button exportTx = button("Exportar TX", PANEL, INK);
        addToRoot(root, exportTx, 8);
        exportTx.setOnClickListener(v -> requestExportTx());
        exportText.setOnClickListener(v -> requestExport(false));
        exportBin.setOnClickListener(v -> requestExport(true));

        TextView note = text("v2.5 GIRO SUAVE (EXPERIMENTAL): base v2.4, aprovada para espelhamento contínuo mas com encolhimento temporário durante o giro. A GPU segura brevemente o último quadro correto antes da mudança de proporção e só libera o novo conteúdo após atualizar a geometria, sem trocar H264, SPS/PPS, canal AOA ou MediaProjection. Encoder fixo 1280x576 VDAT90. Imagem vertical pode ter barras diferentes da v2.1. Se houver travamento ou imagem deformada, use CANVAS FIXO v2.4. O modo problemático v2.3 continua bloqueado. Áudio indisponível.", 12, MUTED, Typeface.NORMAL);
        note.setPadding(0, dp(17), 0, 0);
        root.addView(note);
    }

    private void discoverAccessory() {
        if (usbManager == null) {
            setStatus("USB indisponível", WARNING);
            return;
        }
        UsbAccessory[] accessories = usbManager.getAccessoryList();
        accessory = null;
        if (accessories != null && accessories.length > 0) {
            for (UsbAccessory item : accessories) {
                if ("onebit".equalsIgnoreCase(item.getManufacturer())
                        && "sagetech.onebit.onlymirror".equalsIgnoreCase(item.getModel())) {
                    accessory = item;
                    break;
                }
            }
            if (accessory == null) accessory = accessories[0];
        }
        if (accessory != null) {
            String manufacturer = accessory.getManufacturer();
            String model = accessory.getModel();
            String version = accessory.getVersion();
            deviceView.setText("Fabricante: " + safe(manufacturer)
                    + "\nModelo: " + safe(model) + "\nVersão: " + safe(version));
            if (!running.get()) setStatus("Acessório AOA detectado", ACCENT);
            appendLog("AOA encontrado: " + safe(manufacturer) + " / " + safe(model)
                    + " / " + safe(version));
        } else {
            Map<String, UsbDevice> devices = usbManager.getDeviceList();
            StringBuilder details = new StringBuilder("Nenhum UsbAccessory AOA disponível.");
            if (!devices.isEmpty()) {
                details.append("\nDispositivos USB host visíveis:");
                for (UsbDevice dev : devices.values()) {
                    details.append(String.format(Locale.US, "\nVID:%04X PID:%04X",
                            dev.getVendorId(), dev.getProductId()));
                    details.append(" class=").append(dev.getDeviceClass())
                            .append(" subclasses=").append(dev.getDeviceSubclass())
                            .append(" interfaces=").append(dev.getInterfaceCount());
                }
            }
            deviceView.setText(details.toString());
            if (!running.get()) setStatus("Aguardando LD36", WARNING);
            appendLog("Nenhum acessório AOA. Verifique o cabo, energia e se o Mirror está fechado.");
        }
        connectButton.setEnabled(accessory != null && !running.get());
        stopButton.setEnabled(running.get());
    }

    private void chooseManualVdatAngle(int degrees) {
        if (!manualAngleTestSelected || !videoStarted || !running.get()) {
            appendLog("v1.8: selecione VDAT 0° / 90° e inicie espelhamento para mudar o angulo.");
            return;
        }
        int previous = manualVdatAngle;
        manualVdatAngle = degrees;
        vdatAngleDegrees = degrees;
        if (previous != degrees) vdatAngleChanges++;
        vdatAngleAwaitFirstPacket = degrees;
        appendLog("v1.8 ANGULO MANUAL: "+previous+" -> "+degrees+
            " graus. Codec fixo="+profileInitialEncoderWidth+"x"+profileInitialEncoderHeight+
            "; USB e MediaCodec inalterados; verificacao visual necessaria.");
        // Request an IDR after changing metadata; do not recreate the codec.
        Intent refresh = new Intent(this, MirrorProjectionService.class);
        refresh.setAction(MirrorProjectionService.ACTION_REFRESH);
        try { startService(refresh); }
        catch (RuntimeException e) { appendLog("v1.8: falha solicitando IDR: "+e.getMessage()); }
    }

    // Round to a hardware-friendly 16-pixel boundary; keep source aspect ratio.
    // This does not crop: a 2400x1080 source maps to 1280x576 exactly.
    private static int nativeLandscapeHeight(int sourceW, int sourceH) {
        if (sourceW <= sourceH || sourceW < 320 || sourceH < 320) return 720;
        int proposed = Math.round((1280f * sourceH / sourceW) / 16f) * 16;
        return Math.max(320, Math.min(720, proposed));
    }

    // H.264 portrait scaled from the actual Android 1080x2400 physical display:
    // 1280px output height -> 576px output width (both multiples of 16).
    // The previous 720x1280 VirtualDisplay has a wider aspect ratio than
    // the screen and may add a first set of lateral pillarboxing.
    private static int nativePortraitWidth(int sourceW, int sourceH) {
        if (sourceW >= sourceH || sourceW < 320 || sourceH < 320) return 720;
        int proposed = Math.round((1280f * sourceW / sourceH) / 16f) * 16;
        return Math.max(320, Math.min(720, proposed));
    }

    // Android 14+ MediaProjection consent is single-use. Automatic live codec
    // recreation is unsafe here (v1.1 froze) and reusing a token is invalid.
    // Therefore a physical orientation switch ends the old capture and
    // requires a fresh user tap to begin the other, independently negotiated profile.
    private void scheduleSafeOrientationHandoff(final int session, final boolean targetPortrait) {
        if (!(safeAutoHandoffSelected || dualNativeSafeSelected) || handoffPending || !videoStarted || !running.get()) return;
        if (initialProfilePortrait == targetPortrait) return;
        handoffPending = true;
        handoffTargetSession = session;
        handoffTargetPortrait = targetPortrait;
        appendLog((dualNativeSafeSelected ? "v2.2 AUTO NATIVO" : "v2.0 AUTO SEGURO")+
            ": mudança de orientação detectada. "+
            "Confirmando estado estável antes de parar o perfil antigo.");
        mainHandler.postDelayed(() -> {
            if (!handoffPending || handoffTargetSession != generation ||
                session != generation || !running.get() || !videoStarted ||
                !(safeAutoHandoffSelected || dualNativeSafeSelected)) {
                handoffPending = false;
                return;
            }
            android.graphics.Point size = new android.graphics.Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            boolean stillPortrait = size.y >= size.x;
            handoffPending = false;
            if (stillPortrait != handoffTargetPortrait ||
                stillPortrait == initialProfilePortrait) {
                appendLog("AUTO GIRO: estado atual divergiu do evento inicial; captura preservada.");
                return;
            }
            appendLog((dualNativeSafeSelected ? "v2.2 AUTO NATIVO" : "v2.0 AUTO SEGURO")+
                ": perfil atual "+
                (initialProfilePortrait ? "VERTICAL" : "HORIZONTAL")+
                " não corresponde ao Android "+(stillPortrait ? "VERTICAL" : "HORIZONTAL")+
                ". Encerrando sessão sem trocar MediaCodec em execução.");
            stopCapture();
            setStatus("Nova orientação: toque ESPELHAR VÍDEO", WARNING);
            appendLog("v2.2 AÇÃO NECESSÁRIA: volte ao app e toque ESPELHAR VÍDEO "+
                "para autorizar nova captura no perfil "+
                (stillPortrait ? (dualNativeSafeSelected ? "VERTICAL 576x1280" : "VERTICAL 720x1280") : "HORIZONTAL 1280x576")+
                ". Android exige nova permissão; não reutilizamos token nem forçamos USB.");
        }, 850);
    }

    private void requestVideoConsent() {
        if (running.get()) {
            appendLog("Encerre primeiro a sessão atual.");
            return;
        }
        if (landscapeStartSelected) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            appendLog("PAISAGEM v1.6: rotacao horizontal solicitada ANTES da permissao.");
            mainHandler.postDelayed(() -> {
                android.graphics.Point size=new android.graphics.Point();
                getWindowManager().getDefaultDisplay().getRealSize(size);
                if (!landscapeStartSelected || running.get()) return;
                if(size.x<=size.y) {
                    appendLog("PAISAGEM v1.6: tela continua vertical "+
                        size.x+"x"+size.y+"; sem iniciar.");
                    return;
                }
                appendLog("PAISAGEM v1.6: display confirmado "+size.x+"x"+size.y+
                    "; iniciando em perfil 1280x720.");
                requestProjectionConsent();
            },900);
            return;
        }
        if (nativeOrientationStartSelected) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            appendLog("v1.7: sem rotação forçada. Para teste HORIZONTAL, "+
                "gire o celular antes de conceder a permissão de captura; "+
                "se a tela física estiver vertical, o teste iniciará vertical.");
        }
        requestProjectionConsent();
    }

    private void requestProjectionConsent() {
        try {
            android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjection indisponivel");
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
            appendLog("Solicitada autorizacao de captura de tela do Android.");
        } catch (Exception e) {
            appendLog("Falha solicitando captura: " + e.getMessage());
        }
    }

    private void startVideoAfterStar(int session, int magic, int width, int height) {
        runOnUiThread(() -> {
            if (generation != session || !running.get() || !videoModeRequested) return;
            // A physical dongle may renegotiate SYNC/STAR; update the session magic.
            videoMagic = magic;
            if (videoStarted) return;
            if (projectionConsent == null) {
                appendLog("Sem permissao de captura: video nao iniciado.");
                return;
            }
            videoStarted = true;
            videoMagic = magic;
            try {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                // Preserve screen aspect ratio; H.264 encoder dimensions aligned in service.
                android.graphics.Point displaySize = new android.graphics.Point();
                android.view.Display screen = getWindowManager().getDefaultDisplay();
                screen.getRealSize(displaySize);
                int actualW = displaySize.x > 0 ? displaySize.x : metrics.widthPixels;
                int actualH = displaySize.y > 0 ? displaySize.y : metrics.heightPixels;
                boolean portrait = actualH >= actualW;
                if (nativePortraitAspectTestSelected && !portrait) {
                    appendLog("v2.1 RETRATO NATIVO: fonte horizontal. Volte ao retrato ANTES de conceder a captura.");
                    stopCapture();
                    return;
                }
                if (nativeAspectTestSelected && portrait) {
                    appendLog("v1.9: fonte vertical. Para isolar geometria horizontal "+
                        "inicie o espelhamento com o Android realmente em paisagem.");
                    stopCapture();
                    return;
                }
                if (nativeOrientationStartSelected) {
                    appendLog("v1.7 PERFIL NATURAL: display real="+actualW+"x"+actualH+
                        " -> codec inicial="+(portrait ? "720x1280" : "1280x720")+
                        "; sem mudar codec ao girar no meio da sessão.");
                }
                if (landscapeStartSelected && portrait) {
                    appendLog("PAISAGEM v1.6: orientacao vertical antes do STAR; "+
                        "cancelando sem iniciar encoder.");
                    stopCapture();
                    return;
                }
                int encoderW = fixedCanvasSelected ? 1280 :
                    ((nativePortraitAspectTestSelected || ((dualNativeSafeSelected || liveNativeSelected) && portrait)) ?
                    nativePortraitWidth(actualW,actualH) :
                    ((fillTvSelected || directTvSelected) ? 1280 : (portrait ? 720 : 1280)));
                int encoderH = fixedCanvasSelected ? 576 :
                    (nativeAspectTestSelected || ((safeAutoHandoffSelected || dualNativeSafeSelected || liveNativeSelected) && !portrait))
                    ? nativeLandscapeHeight(actualW, actualH) :
                    ((fillTvSelected || directTvSelected) ? 720 : (portrait ? 1280 : 720));
                Intent intent = new Intent(this, MirrorProjectionService.class);
                intent.setAction(MirrorProjectionService.ACTION_START);
                intent.putExtra(MirrorProjectionService.EXTRA_RESULT, projectionResult);
                intent.putExtra(MirrorProjectionService.EXTRA_DATA, projectionConsent);
                intent.putExtra(MirrorProjectionService.EXTRA_WIDTH, encoderW);
                intent.putExtra(MirrorProjectionService.EXTRA_HEIGHT, encoderH);
                intent.putExtra(MirrorProjectionService.EXTRA_DPI, metrics.densityDpi);
                intent.putExtra(MirrorProjectionService.EXTRA_SOURCE_WIDTH, actualW);
                intent.putExtra(MirrorProjectionService.EXTRA_SOURCE_HEIGHT, actualH);
                intent.putExtra(MirrorProjectionService.EXTRA_CROP, fillTvSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_DIRECT_TV, directTvSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_AUTO_ORIGINAL, autoOriginalSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_VDAT_ANGLE, vdatAngleSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_LIVE_NATIVE, liveNativeSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_FIXED_CANVAS, fixedCanvasSelected);
                intent.putExtra(MirrorProjectionService.EXTRA_SMOOTH_CANVAS, smoothCanvasSelected);
                vdatAngleDegrees=(nativeAspectTestSelected || safeAutoHandoffSelected ||
                    nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) ? 90 : 0;
                manualVdatAngle=(nativeAspectTestSelected || safeAutoHandoffSelected ||
                    nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) ? 90 : 0;
                initialProfilePortrait=portrait;
                handoffPending=false;
                profileInitialEncoderWidth=encoderW;
                profileInitialEncoderHeight=encoderH;
                fpsWindowStartMs=0L;
                fpsWindowPackets=0L;
                fpsWindowBytes=0L;
                vdatAngleChanges=0;
                vdatAngleFrames0=0;vdatAngleFrames90=0;
                vdatAngleFrames180=0;vdatAngleFrames270=0;
                vdatAngleConfigs=0;vdatAngleIdrs=0;
                vdatAngleAwaitFirstPacket=(nativeAspectTestSelected || safeAutoHandoffSelected || nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) ? 90 : -1;
                if(smoothCanvasSelected) appendLog("v2.5 GIRO SUAVE INICIO: H264 FIXO 1280x576 VDAT=90; "+
                    "GPU segura ultimo quadro antes de nova geometria, sem trocar codec, USB ou MediaProjection.");
                if(fixedCanvasSelected) appendLog("v2.4 CANVAS FIXO INICIO: fonte="+actualW+"x"+actualH+
                    " H264 CONSTANTE 1280x576, VDAT=90, SurfaceTexture GPU sem crop. Modo diagnóstico: vertical pode ter barras.");
                if(liveNativeSelected) appendLog("v2.3 LIVE INICIO: codec="+encoderW+"x"+encoderH+
                    " VDAT90; troca dinamica experimental na MESMA sessao. USB permanece aberto; decoder TV pode rejeitar o novo SPS/PPS.");
                if (dualNativeSafeSelected) appendLog("v2.2 AUTO NATIVO INICIO: Android="+actualW+"x"+actualH+
                    " H264="+encoderW+"x"+encoderH+" VDAT=90; perfil validado "+
                    (portrait ? "VERTICAL v2.1" : "HORIZONTAL v1.9")+
                    ". Ao girar, para e solicita NOVA autorizacao.");
                if (nativePortraitAspectTestSelected) appendLog("v2.1 RETRATO PROPORCAO: fonte="+actualW+"x"+actualH+
                    " H264="+encoderW+"x"+encoderH+" VDAT=90. Sem GPU, sem crop, codec fixo. Referencia v2.0: 720x1280.");
                if (safeAutoHandoffSelected) appendLog("v2.0 AUTO SEGURO INÍCIO: perfil="+
                    (portrait ? "VERTICAL 720x1280 (v1.5)" : "HORIZONTAL 1280x"+encoderH+" (v1.9)")+
                    " VDAT=90. Em nova orientação encerra, exige novo consentimento.");
                if (nativeAspectTestSelected)
                    appendLog("v1.9 PROPORCAO: fonte="+actualW+"x"+actualH+
                        " H264="+encoderW+"x"+encoderH+
                        " VDAT=90; diferenca relativa proporcao="+
                        String.format(Locale.US,"%.3f%%",
                            100.0*((double)encoderW/encoderH/((double)actualW/actualH)-1.0))+
                        "; MediaCodec fixo por sessao, sem crop.");
                appendLog("Tela real: " + actualW + "x" + actualH + " -> encoder "
                    + encoderW + "x" + encoderH
                    + (smoothCanvasSelected ? " | v2.5 GIRO SUAVE 1280x576 VDAT 90" :
                      (fixedCanvasSelected ? " | v2.4 CANVAS FIXO 1280x576 VDAT 90" :
                      (liveNativeSelected ? " | v2.3 LIVE EXPERIMENTAL VDAT 90" :
                      (dualNativeSafeSelected ? " | v2.2 AUTO NATIVO + VDAT 90" :
                      (nativePortraitAspectTestSelected ? " | v2.1 RETRATO PROPORCIONAL + VDAT 90" :
                      (safeAutoHandoffSelected ? " | v2.0 PERFIL AUTO SEGURO + VDAT 90" :
                      (nativeAspectTestSelected ? " | v1.9 ASPECTO ORIGINAL + VDAT 90" :
                      (manualAngleTestSelected ? " | v1.8 VDAT MANUAL 0/90 - CODEC FIXO" :
                      (nativeOrientationStartSelected ? " | v1.7 PERFIL NATURAL - SEM GIRO FORÇADO" :
                      (landscapeStartSelected ? " | v1.6 PAISAGEM 1280x720 NA PARTIDA, SEM RECONFIGURAR" :
                        (vdatAngleSelected ? " | VDAT ANGLE - CODEC ORIGINAL FIXO" :
                        (autoOriginalSelected ? " | AUTO ORIGINAL v1.1 - CODIFICADOR DINAMICO" :
                            (directTvSelected ? " | TV DIRETO v1.0 (experimental falhou)" :
                                (fillTvSelected ? " | GPU ANTIGA" : " | ORIGINAL v0.4")))))))))))))));
                projectionConsent = null; // MediaProjection consent token must be used once.
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                else startService(intent);
                appendLog("STAR fisico recebido: iniciando captura de video com autorizacao.");
            } catch (Exception e) {
                videoStarted = false;
                appendLog("Falha iniciando servico H264: " + e.getMessage());
            }
        });
    }

    private void connectWithPermission() {
        if (running.get()) return;
        if (accessory == null) {
            discoverAccessory();
            if (accessory == null) { autoScanRequested = false; return; }
        }
        if (usbManager.hasPermission(accessory)) {
            openAccessory();
            return;
        }
        try {
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, intent, flags);
            usbManager.requestPermission(accessory, pending);
            setStatus("Aguardando autorização USB", WARNING);
            appendLog("Solicitada autorização AOA ao Android.");
        } catch (RuntimeException e) {
            appendLog("Falha ao pedir permissão: " + e.getMessage());
        }
    }

    private void openAccessory() {
        if (accessory == null || running.get()) return;
        try {
            ParcelFileDescriptor pfd = usbManager.openAccessory(accessory);
            if (pfd == null) {
                setStatus("Não foi possível abrir AOA", WARNING);
                appendLog("openAccessory retornou null. Verifique permissão e aplicativos concorrentes.");
                return;
            }
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
            descriptor = pfd;
            input = in;
            usbOutput = out;
            activeScan = activeScanRequested;
            activeScanRequested = false;
            videoStarted = false;
            videoFrames = 0;
            videoBytes = 0;
            txLimitWarned = false;
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            textFile = new File(dir, "NURA_LD36_" + stamp + ".txt");
            rawFile = new File(dir, "NURA_LD36_RX_" + stamp + ".bin");
            txFile = new File(dir, "NURA_LD36_TX_" + stamp + ".bin");
            textFile.createNewFile();
            rawFile.createNewFile();
            txFile.createNewFile();
            totalBytes = 0;
            chunks = 0;
            metricsView.setText("0 bytes  •  0 leituras USB");
            int session = ++generation;
            running.set(true);
            if (autoScanRequested) scheduleAutoStop(session);
            setStatus("AOA aberto • capturando RX", ACCENT);
            connectButton.setEnabled(false);
            stopButton.setEnabled(true);
            appendLog("Canal AOA aberto. Sessão " + stamp + ". Aguardando dados RX...");
            appendLog(activeScan ? "Modo ATIVO: eco heartbeat e respostas SYNC/STAR conhecidas." :
                "Modo PASSIVO: nenhum TX.");
            captureThread = new Thread(() -> captureLoop(session, in, rawFile, out, txFile, activeScan), "ld36-rx");
            captureThread.start();
        } catch (Exception e) {
            setStatus("Erro ao abrir acessório", WARNING);
            appendLog("Erro de conexão: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            closeResources();
        }
    }

    private void scheduleAutoStop(int session) {
        autoScanRequested = false;
        appendLog(videoModeRequested ? "Teste de video: janela maxima de 90 segundos." :
                  "Varredura de protocolo durante 60 segundos.");
        mainHandler.postDelayed(() -> {
            if (generation == session && running.get()) {
                appendLog("Varredura automática concluída após 60 segundos.");
                stopCapture();
            }
        }, videoModeRequested ? 90_000L : AUTO_SCAN_MS);
    }

    private void captureLoop(int session, FileInputStream in, File targetRaw,
                             FileOutputStream output, File targetTx, boolean active) {
        StreamProbe probe = new StreamProbe();
        if (active && videoModeRequested) {
            MirrorProjectionService.setReceiver(new MirrorProjectionService.VideoSink() {
                @Override public void onVideo(byte[] avc, boolean config, boolean keyframe, long millis) throws IOException {
                    if (session != generation || !running.get() || !videoStarted) throw new IOException("Sessao USB encerrada");
                    if (!VideoPacket.isAnnexB(avc)) {
                        logFromWorker(session, "H264: quadro sem cabecalho Annex-B, descartado.");
                        return;
                    }
                    byte[] packet = VideoPacket.make(videoMagic, millis,
                        vdatAngleSelected ? vdatAngleDegrees : 0, avc);
                    transmitUsb(packet, config ? "VDAT_SPS_PPS" : keyframe ? "VDAT_IDR" : "VDAT_P", session, output, targetTx, true);
                    if(vdatAngleSelected) {
                        final int transmittedAngle=vdatAngleDegrees;
                        if(config) vdatAngleConfigs++;
                        else {
                            if(transmittedAngle==90)vdatAngleFrames90++;
                            else if(transmittedAngle==180)vdatAngleFrames180++;
                            else if(transmittedAngle==270)vdatAngleFrames270++;
                            else vdatAngleFrames0++;
                        }
                        if(keyframe)vdatAngleIdrs++;
                        if(vdatAngleAwaitFirstPacket==transmittedAngle) {
                            vdatAngleAwaitFirstPacket=-1;
                            logFromWorker(session,"VDAT ANGLE CONFIRMACAO TX: primeiro pacote com angulo "+
                                transmittedAngle+" enviado, tipo="+(config ? "SPS/PPS" : keyframe ? "IDR" : "P")+
                                "; nao comprova exibicao na TV.");
                        }
                        long count=vdatAngleFrames0+vdatAngleFrames90+
                            vdatAngleFrames180+vdatAngleFrames270;
                        if(!config && count>0 && count%300==0)
                            logFromWorker(session,"VDAT ANGLE CONTADORES: frames0="+vdatAngleFrames0+
                                " frames90="+vdatAngleFrames90+
                                " frames180="+vdatAngleFrames180+
                                " frames270="+vdatAngleFrames270+
                                " idr="+vdatAngleIdrs+" transicoes="+vdatAngleChanges);
                    }
                }
                @Override public void onStatus(String message) {
                    logFromWorker(session, message);
                }
                @Override public void onOrientation(int degrees,int width,int height) {
                    if(session!=generation || !videoStarted || !vdatAngleSelected) return;
                    if(fixedCanvasSelected) {
                        vdatAngleDegrees=90;
                        logFromWorker(session,"v2.4 CANVAS: Android="+degrees+" src="+width+"x"+height+
                            " H264 fixo="+profileInitialEncoderWidth+"x"+profileInitialEncoderHeight+
                            " VDAT=90; NÃO interromper AOA");
                        return;
                    }
                    if(liveNativeSelected) {
                        vdatAngleDegrees=90;
                        logFromWorker(session,"v2.3 LIVE FONTE: Android="+degrees+
                            " "+width+"x"+height+"; Surface/MediaCodec trocados no serviço, USB mantido.");
                        return;
                    }
                    if (dualNativeSafeSelected) {
                        vdatAngleDegrees=90;
                        boolean currentPortrait = height >= width;
                        logFromWorker(session,"v2.2 AUTO NATIVO: Android="+degrees+
                            " src="+width+"x"+height+" perfilAtivo="+
                            (initialProfilePortrait ? "VERTICAL 576x1280" : "HORIZONTAL 1280x576")+
                            " VDAT=90, sem codec switch");
                        if (currentPortrait != initialProfilePortrait)
                            runOnUiThread(() -> scheduleSafeOrientationHandoff(session,currentPortrait));
                        return;
                    }
                    if (nativePortraitAspectTestSelected) {
                        vdatAngleDegrees=90;
                        logFromWorker(session,"v2.1 RETRATO FONTE: Android="+degrees+
                            " fonte="+width+"x"+height+" codec="+profileInitialEncoderWidth+
                            "x"+profileInitialEncoderHeight+". Não girar no experimento.");
                        return;
                    }
                    if (safeAutoHandoffSelected) {
                        // Both physically confirmed start profiles use 90°:
                        // portrait v1.5 (720x1280), landscape v1.9 (1280x576).
                        // Do not switch encoder/Surface while capturing.
                        vdatAngleDegrees=90;
                        logFromWorker(session,"v2.0 AUTO: fonte Android="+degrees+
                            " graus, "+width+"x"+height+", sessão inicial="+
                            (initialProfilePortrait ? "RETRATO" : "PAISAGEM")+
                            ", VDAT permanece 90; sem redimensionamento dinâmico.");
                        if ((height >= width) != initialProfilePortrait)
                            runOnUiThread(() -> scheduleSafeOrientationHandoff(session,height >= width));
                        return;
                    }
                    if (nativeAspectTestSelected) {
                        // On this isolated test keep the confirmed 90° metadata fixed.
                        // A mid-session orientation change is intentionally not reconfigured.
                        vdatAngleDegrees=90;
                        logFromWorker(session,"v1.9 ORIENTACAO FONTE: "+degrees+
                            " graus; manter VDAT=90 codec="+profileInitialEncoderWidth+
                            "x"+profileInitialEncoderHeight+
                            ". Se girou fisicamente, interrompa e reinicie o teste.");
                        return;
                    }
                    if (manualAngleTestSelected) {
                        logFromWorker(session,"v1.8 ANDROID ORIENTACAO="+degrees+
                            " fonte="+width+"x"+height+"; VDAT MANUAL="+manualVdatAngle+
                            "; escolha do usuario prevalece.");
                        vdatAngleDegrees=manualVdatAngle;
                        return;
                    }
                    final int outgoingAngle = invertedAngleSelected ?
                        ((degrees + 90) % 180 + (degrees / 180) * 180) : degrees;
                    vdatAngleDegrees=outgoingAngle;
                    vdatAngleChanges++;
                    vdatAngleAwaitFirstPacket=outgoingAngle;
                    logFromWorker(session,"VDAT ANGLE PACKET: android="+degrees+
                        " graus; enviado="+outgoingAngle+
                        " graus, invertido="+invertedAngleSelected+
                        ", fonte="+width+"x"+height+
                        "; transporte e MediaCodec originais inalterados.");
                }
            });
        }
        ActiveProtocolSession activeSession = active ? new ActiveProtocolSession(new ActiveProtocolSession.Sink() {
            @Override public void send(byte[] data, String label) throws IOException {
                transmitUsb(data, label, session, output, targetTx, false);
            }
            @Override public void note(String message) {
                logFromWorker(session, message);
            }
            @Override public void onStartMirror(int width, int height, int magic) {
                if (videoModeRequested) startVideoAfterStar(session, magic, width, height);
            }
        }) : null;
        if (activeSession != null && videoModeRequested) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            boolean portrait = metrics.heightPixels >= metrics.widthPixels;
            if (nativeAspectTestSelected || safeAutoHandoffSelected || nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) {
                android.graphics.Point actual = new android.graphics.Point();
                getWindowManager().getDefaultDisplay().getRealSize(actual);
                boolean starPortrait = actual.y >= actual.x;
                int w = (nativePortraitAspectTestSelected || ((dualNativeSafeSelected || liveNativeSelected) && starPortrait)) ?
                    nativePortraitWidth(actual.x,actual.y) :
                    (starPortrait ? 720 : 1280);
                int h = starPortrait ? 1280 : nativeLandscapeHeight(actual.x,actual.y);
                if(fixedCanvasSelected) {w=1280;h=576;}
                activeSession.setVideoOutput(w,h);
                logFromWorker(session, (fixedCanvasSelected ? "v2.4" : (liveNativeSelected ? "v2.3" : (dualNativeSafeSelected ? "v2.2" : (nativePortraitAspectTestSelected ? "v2.1" : (safeAutoHandoffSelected ? "v2.0" : "v1.9")))))+
                    " STAR PERFIL: H264="+w+"x"+h+
                    " conforme Android no início; MediaCodec fixo durante sessão.");
            } else {
                activeSession.setVideoOutput(fillTvSelected ? 1280 : (portrait ? 720 : 1280),
                    fillTvSelected ? 720 : (portrait ? 1280 : 720));
            }
        }
        long bytes = 0;
        long reads = 0;
        boolean rawLimitLogged = false;
        byte[] buffer = new byte[16384];
        try (FileOutputStream raw = new FileOutputStream(targetRaw)) {
            while (running.get() && generation == session) {
                int count = in.read(buffer);
                if (count < 0) {
                    logFromWorker(session, "Fim do fluxo USB (EOF).");
                    break;
                }
                if (count == 0) continue;
                reads++;
                bytes += count;
                probe.add(buffer, count);
                if (activeSession != null) activeSession.accept(buffer, count);
                if (bytes - count < RAW_CAPTURE_LIMIT) {
                    int canWrite = (int) Math.min(count, RAW_CAPTURE_LIMIT - (bytes - count));
                    raw.write(buffer, 0, canWrite);
                } else if (!rawLimitLogged) {
                    rawLimitLogged = true;
                    logFromWorker(session, "Limite de captura BIN (8 MiB) atingido; RX continua sem salvar bytes extras.");
                }
                if (reads <= 32 || reads % 25 == 0) {
                    logFromWorker(session, probe.report());
                }
                final long byteCopy = bytes;
                final long readCopy = reads;
                // First 32 reads are verbose; later reads are sampled in the text log.
                if (reads <= 32 || reads % 25 == 0) {
                    final int preview = reads <= 32 ? PREVIEW_LIMIT : 128;
                    byte[] snapshot = Arrays.copyOf(buffer, count);
                    String detail = ProtocolInspector.inspect(snapshot, count, preview);
                    logFromWorker(session, "RX #" + readCopy + " bytes=" + count + " | " + detail);
                }
                runOnUiThread(() -> {
                    if (session != generation) return;
                    totalBytes = byteCopy;
                    chunks = readCopy;
                    metricsView.setText(totalBytes + " bytes  •  " + chunks + " leituras USB");
                });
            }
        } catch (IOException e) {
            if (running.get() && session == generation) {
                logFromWorker(session, "Leitura USB encerrada: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            logFromWorker(session, "Falha de leitura: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            String summary = probe.report()
                + (activeSession == null ? " | Modo passivo." : " | " + activeSession.summary())
                + " | H264 frames=" + videoFrames + " videoBytesUSB=" + videoBytes;
            // Write summary directly to the session log even if generation changed on stop.
            appendSessionSummary(summary);
            runOnUiThread(() -> {
                if (session != generation) return;
                running.set(false);
                stopVideoService();
                closeResources();
                setStatus("Captura encerrada", WARNING);
                connectButton.setEnabled(accessory != null);
                stopButton.setEnabled(false);
                appendLog("Captura finalizada; dados salvos no armazenamento do aplicativo.");
            });
        }
    }

    private void transmitUsb(byte[] data, String label, int session,
                             FileOutputStream output, File targetTx, boolean video) throws IOException {
        synchronized (sendLock) {
            if (!running.get() || session != generation) throw new IOException("USB nao conectado");
            output.write(data);
            output.flush();
            if (targetTx.length() + data.length <= TX_BIN_LIMIT) {
                try (FileOutputStream tx = new FileOutputStream(targetTx, true)) {
                    tx.write(data);
                }
            } else if (!txLimitWarned) {
                txLimitWarned = true;
                logFromWorker(session, "Limite 32MiB arquivo TX atingido; transmissao USB continua.");
            }
            if (video) {
                videoFrames++;
                videoBytes += data.length;
                if ((manualAngleTestSelected || nativeAspectTestSelected || safeAutoHandoffSelected || nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) && !label.equals("VDAT_SPS_PPS")) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (fpsWindowStartMs == 0L) fpsWindowStartMs = now;
                    fpsWindowPackets++;
                    fpsWindowBytes += data.length;
                    long elapsed = now - fpsWindowStartMs;
                    if (elapsed >= 5000L) {
                        final double fps = fpsWindowPackets * 1000.0 / elapsed;
                        final double kbps = fpsWindowBytes * 8.0 / elapsed;
                        logFromWorker(session, String.format(Locale.US,
                            "v1.8/v1.9 TAXA TX: %.2f quadros/s, %.1f kbit/s, intervalo=%dms, angle=%d, codec=%dx%d",
                            fps,kbps,elapsed,manualVdatAngle,
                            profileInitialEncoderWidth,profileInitialEncoderHeight));
                        fpsWindowStartMs = now;
                        fpsWindowPackets = 0;
                        fpsWindowBytes = 0;
                    }
                }
                if (videoFrames <= 4 || videoFrames % 60 == 0 || label.equals("VDAT_IDR")) {
                    logFromWorker(session, "TX " + label + " len=" + data.length
                        + " totalH264=" + videoFrames + " bytesUSB=" + videoBytes);
                }
            } else {
                logFromWorker(session, "TX " + label + " (" + data.length + " B) HEX="
                    + ProtocolInspector.toHex(data, 0, Math.min(data.length, 48)));
            }
        }
    }

    private void stopVideoService() {
        MirrorProjectionService.setReceiver(null);
        if (videoStarted) {
            try {
                Intent stop = new Intent(this, MirrorProjectionService.class);
                stop.setAction(MirrorProjectionService.ACTION_STOP);
                startService(stop);
            } catch (RuntimeException e) {
                appendLog("Parada do serviço de vídeo: " + e.getMessage());
            }
        }
        videoStarted = false;
    }

    private void appendSessionSummary(String summary) {
        File destination = textFile;
        if (destination == null) return;
        try (FileOutputStream out = new FileOutputStream(destination, true)) {
            out.write(("[FINAL] " + summary + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private void stopCapture() {
        autoScanRequested = false;
        activeScanRequested = false;
        videoModeRequested = false;
        projectionConsent = null;
        handoffPending=false;
        if ((manualAngleTestSelected || nativeAspectTestSelected || safeAutoHandoffSelected || nativePortraitAspectTestSelected || dualNativeSafeSelected || liveNativeSelected || fixedCanvasSelected) && videoStarted) {
            appendLog("v1.8 FIM: angle manual="+manualVdatAngle+
                " codec="+profileInitialEncoderWidth+"x"+profileInitialEncoderHeight+
                " pacotes="+videoFrames+" dadosUSB="+videoBytes);
        }
        if (vdatAngleSelected && videoStarted) {
            appendLog("VDAT ANGLE RESUMO: frames0="+vdatAngleFrames0+
                " frames90="+vdatAngleFrames90+
                " frames180="+vdatAngleFrames180+
                " frames270="+vdatAngleFrames270+
                " configs="+vdatAngleConfigs+" idr="+vdatAngleIdrs+
                " mudancas="+vdatAngleChanges+
                "; contagem local, nao comprova exibicao na TV.");
        }
        stopVideoService();
        if (!running.get() && descriptor == null) return;
        running.set(false);
        generation++;
        closeResources();
        setStatus("Captura parada", WARNING);
        connectButton.setEnabled(accessory != null);
        stopButton.setEnabled(false);
        appendLog("Captura interrompida pelo usuário.");
    }

    private void closeResources() {
        synchronized (sendLock) {
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            try { if (usbOutput != null) usbOutput.close(); } catch (IOException ignored) { }
            usbOutput = null;
        }
        try { if (descriptor != null) descriptor.close(); } catch (IOException ignored) { }
        input = null;
        descriptor = null;
    }

    private void requestExportTx() {
        if (txFile == null || !txFile.exists()) {
            appendLog("Ainda não existe captura TX.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, txFile.getName());
        startActivityForResult(intent, 4003);
    }

    private void requestExport(boolean raw) {
        File target = raw ? rawFile : textFile;
        if (target == null || !target.exists()) {
            appendLog("Nenhuma captura disponível para exportar. Conecte e inicie uma sessão.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(raw ? "application/octet-stream" : "text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, target.getName());
        startActivityForResult(intent, raw ? REQ_EXPORT_RAW : REQ_EXPORT_TEXT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                appendLog("Captura de tela nao autorizada.");
                return;
            }
            projectionResult = resultCode;
            projectionConsent = data;
            videoModeRequested = true;
            activeScanRequested = true;
            autoScanRequested = true;
            appendLog("MediaProjection autorizada; abrindo LD36 para iniciar negociacao.");
            connectWithPermission();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        boolean raw = requestCode == REQ_EXPORT_RAW;
        if (!raw && requestCode != REQ_EXPORT_TEXT && requestCode != 4003) return;
        File source = requestCode == 4003 ? txFile : (raw ? rawFile : textFile);
        Uri destination = data.getData();
        if (source == null) return;
        new Thread(() -> {
            try (InputStream src = new FileInputStream(source);
                 OutputStream dst = getContentResolver().openOutputStream(destination, "w")) {
                if (dst == null) throw new IOException("Destino de exportação indisponível");
                byte[] block = new byte[8192];
                int count;
                while ((count = src.read(block)) >= 0) {
                    if (count > 0) dst.write(block, 0, count);
                }
                runOnUiThread(() -> appendLog("Arquivo exportado: " + source.getName()));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("Falha ao exportar: " + e.getMessage()));
            }
        }, "ld36-export").start();
    }

    private void logFromWorker(int session, String message) {
        runOnUiThread(() -> {
            if (session == generation) appendLog(message);
        });
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "[" + time + "] " + message + "\n";
        visibleLog.append(entry);
        if (visibleLog.length() > 40000) {
            visibleLog.delete(0, Math.max(0, visibleLog.length() - 28000));
        }
        if (logView != null) logView.setText(visibleLog.toString());
        if (textFile != null && textFile.length() < TEXT_CAPTURE_LIMIT) {
            try (FileOutputStream log = new FileOutputStream(textFile, true)) {
                log.write(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    private void setStatus(String message, int color) {
        if (statusView != null) {
            statusView.setText(message);
            statusView.setTextColor(color);
        }
    }

    private static String safe(String s) { return s == null ? "?" : s; }

    private LinearLayout columnCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(round(PANEL));
        return card;
    }

    private GradientDrawable round(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private TextView text(String value, int sizeSp, int color, int weight) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        v.setTypeface(weight == Typeface.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, weight);
        v.setGravity(Gravity.START);
        return v;
    }

    private Button button(String value, int background, int foreground) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(foreground);
        b.setBackground(round(background));
        b.setMinHeight(dp(44));
        b.setPadding(dp(4), dp(7), dp(4), dp(7));
        return b;
    }

    private void addToRoot(LinearLayout parent, View view, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        parent.addView(view, lp);
    }

    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.rightMargin = dp(7);
        row.addView(button, lp);
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}

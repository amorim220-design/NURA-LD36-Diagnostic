package com.nura.ld36diagnostic;

import android.app.Activity;
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
        appendLog("NURA LD36 Diagnostic v0.4.0: teste de video H264 por MediaProjection.");
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
        TextView subtitle = text("VERSÃO 0.4.0 • VÍDEO USB EXPERIMENTAL", 13, ACCENT, Typeface.BOLD);
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
        addToRoot(root, videoButton, 8);
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

        TextView note = text("VÍDEO EXPERIMENTAL: o Android pedirá autorização para capturar sua tela e mostrará notificação durante o teste. Não captura áudio e não intercepta o Mirror original. Feche o Mirror antes de começar; toque em Parar para encerrar.", 12, MUTED, Typeface.NORMAL);
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

    private void requestVideoConsent() {
        if (running.get()) {
            appendLog("Encerre primeiro a sessão atual.");
            return;
        }
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
            if (generation != session || !running.get() || !videoModeRequested || videoStarted) return;
            if (projectionConsent == null) {
                appendLog("Sem permissao de captura: video nao iniciado.");
                return;
            }
            videoStarted = true;
            videoMagic = magic;
            try {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                // Preserve screen aspect ratio; H.264 encoder dimensions aligned in service.
                int actualW = metrics.widthPixels;
                int actualH = metrics.heightPixels;
                boolean portrait = actualH >= actualW;
                int encoderW = portrait ? 720 : 1280;
                int encoderH = portrait ? 1280 : 720;
                Intent intent = new Intent(this, MirrorProjectionService.class);
                intent.setAction(MirrorProjectionService.ACTION_START);
                intent.putExtra(MirrorProjectionService.EXTRA_RESULT, projectionResult);
                intent.putExtra(MirrorProjectionService.EXTRA_DATA, projectionConsent);
                intent.putExtra(MirrorProjectionService.EXTRA_WIDTH, encoderW);
                intent.putExtra(MirrorProjectionService.EXTRA_HEIGHT, encoderH);
                intent.putExtra(MirrorProjectionService.EXTRA_DPI, metrics.densityDpi);
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
                    byte[] packet = VideoPacket.make(videoMagic, millis, 0, avc);
                    transmitUsb(packet, config ? "VDAT_SPS_PPS" : keyframe ? "VDAT_IDR" : "VDAT_P", session, output, targetTx, true);
                }
                @Override public void onStatus(String message) {
                    logFromWorker(session, message);
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

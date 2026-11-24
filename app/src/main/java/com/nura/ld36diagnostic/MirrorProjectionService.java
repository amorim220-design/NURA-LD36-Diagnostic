package com.nura.ld36diagnostic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.graphics.Point;
import android.view.Display;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-authorized screen capture. Emits H.264 Annex-B byte arrays, never records to disk.
 * Requires an active foreground notification while capturing.
 */
public final class MirrorProjectionService extends Service {
    public static final String ACTION_START = "com.nura.ld36diagnostic.START_VIDEO";
    public static final String ACTION_STOP = "com.nura.ld36diagnostic.STOP_VIDEO";
    public static final String ACTION_REFRESH = "com.nura.ld36diagnostic.REFRESH_DISPLAY";
    public static final String ACTION_ROTATE = "com.nura.ld36diagnostic.ROTATE_OUTPUT";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI = "dpi";
    public static final String EXTRA_SOURCE_WIDTH = "source_width";
    public static final String EXTRA_SOURCE_HEIGHT = "source_height";
    public static final String EXTRA_CROP = "center_crop";
    private static final int NOTIFICATION_ID = 36;
    private static final String CHANNEL = "ld36_projection";
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private MediaProjection projection;
    private MediaCodec codec;
    private Surface inputSurface;
    private VirtualDisplay display;
    private ScreenCropRenderer cropRenderer;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener orientationListener;
    private int dpiValue;
    private volatile int lastCaptureWidth;
    private volatile int lastCaptureHeight;
    private volatile int correctionDegrees;
    private volatile int successfulRebinds;
    private volatile int failedRebinds;
    private Thread worker;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface VideoSink {
        void onVideo(byte[] avc, boolean config, boolean keyframe, long millis) throws IOException;
        void onStatus(String message);
    }

    private static volatile VideoSink receiver;

    public static void setReceiver(VideoSink sink) { receiver = sink; }
    private static void note(String text) {
        VideoSink sink = receiver;
        if (sink != null) sink.onStatus(text);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_ROTATE.equals(intent.getAction())) {
            if (capturing.get() && cropRenderer != null) {
                correctionDegrees = (correctionDegrees + 90) % 360;
                cropRenderer.setCorrectionDegrees(correctionDegrees);
                note("ORIENTACAO MANUAL: " + correctionDegrees +
                    " graus; src=" + lastCaptureWidth + "x" + lastCaptureHeight +
                    " encoder=1280x720. Verifique a TV.");
                requestKeyframe();
                handler.postDelayed(() -> {
                    if (cropRenderer != null && capturing.get()) {
                        note("GPU APOS AJUSTE: " + cropRenderer.getMatrixDiagnostic());
                    }
                }, 350);
            } else {
                note("Giro manual so disponivel durante espelhamento experimental.");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(intent.getAction())) {
            if (capturing.get()) {
                handler.postDelayed(() -> refreshScreenDimensions("atividade Android"), 350);
                handler.postDelayed(() -> refreshScreenDimensions("atividade Android retry"), 1100);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || capturing.get()) return START_NOT_STICKY;
        try {
            foreground();
            int result = intent.getIntExtra(EXTRA_RESULT, 0);
            Intent consent = intent.getParcelableExtra(EXTRA_DATA);
            if (consent == null) throw new IOException("MediaProjection: permissao ausente");
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(result, consent);
            if (projection == null) throw new IOException("Android recusou MediaProjection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onCapturedContentResize(int width,int height) {
                    updateCaptureDimensions(width,height,"MediaProjection");
                }
                @Override public void onStop() {
                    note("Captura revogada pelo Android.");
                    stopAll();
                    stopSelf();
                }
            }, handler);
            int width = Math.max(320, Math.min(1920, intent.getIntExtra(EXTRA_WIDTH, 720)));
            int height = Math.max(320, Math.min(1920, intent.getIntExtra(EXTRA_HEIGHT, 1280)));
            int dpi = Math.max(120, intent.getIntExtra(EXTRA_DPI, 320));
            // Align to 16, which avoids several hardware encoder issues.
            width = (width + 15) & ~15;
            height = (height + 15) & ~15;
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();
            boolean crop = intent.getBooleanExtra(EXTRA_CROP, false);
            dpiValue=dpi;
            int sourceWidth = intent.getIntExtra(EXTRA_SOURCE_WIDTH, width);
            int sourceHeight = intent.getIntExtra(EXTRA_SOURCE_HEIGHT, height);
            if (crop && sourceWidth >= 320 && sourceHeight >= 320 &&
                    sourceWidth <= 4096 && sourceHeight <= 4096) {
                // Encoder stays 1280x720; display captures at native screen size.
                // GPU performs proportional center crop, preserving pixel geometry.
                cropRenderer = new ScreenCropRenderer(inputSurface, sourceWidth, sourceHeight, width, height);
                display = projection.createVirtualDisplay("NURA-LD36", sourceWidth, sourceHeight, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    cropRenderer.getCaptureSurface(), null, handler);
                lastCaptureWidth=sourceWidth;
                lastCaptureHeight=sourceHeight;
                note("Rotacao automatica: fonte " + sourceWidth + "x" + sourceHeight +
                    " -> TV " + width + "x" + height + " (sem cortes).");
                displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                orientationListener=new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int id) { }
                    @Override public void onDisplayRemoved(int id) { }
                    @Override public void onDisplayChanged(int id) {
                        if (id != Display.DEFAULT_DISPLAY) return;
                        refreshScreenDimensions("rotacao Android");
                    }
                };
                if(displayManager!=null)displayManager.registerDisplayListener(orientationListener,handler);
            } else {
                display = projection.createVirtualDisplay("NURA-LD36", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, handler);
                note("Modo Original 0.4: captura direta preservada.");
            }
            capturing.set(true);
            note("Captura H264 iniciada " + width + "x" + height + " 30fps; enviando VDAT.");
            if (cropRenderer != null) {
                note("Orientacao v0.8: UV manual=0, SurfaceTexture matriz preservada; " +
                    "toque Girar 90 se a imagem ficar de lado.");
                handler.postDelayed(() -> refreshScreenDimensions("verificacao inicial"), 1200);
                handler.postDelayed(() -> {
                    if (cropRenderer != null && capturing.get()) {
                        note("GPU DIAGNOSTICO: " + cropRenderer.getMatrixDiagnostic());
                    }
                }, 2200);
            }
            worker = new Thread(this::encodeLoop, "nura-h264");
            worker.start();
        } catch (Exception ex) {
            note("Falha ao iniciar video: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            stopAll();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void requestKeyframe() {
        MediaCodec current=codec;
        if (current == null || !capturing.get()) return;
        try {
            Bundle params=new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);
            current.setParameters(params);
        } catch (Exception e) {
            note("Solicitacao IDR falhou: " + e.getMessage());
        }
    }

    private void refreshScreenDimensions(String reason) {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return;
        Display current = manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (current == null) return;
        Point p = new Point();
        current.getRealSize(p);
        updateCaptureDimensions(p.x, p.y, reason);
    }

    private void updateCaptureDimensions(int width,int height,String reason) {
        handler.post(() -> {
            if (!capturing.get() || cropRenderer == null || display == null) return;
            if(width < 240 || height < 240 || width > 4096 || height > 4096)return;
            if(width == lastCaptureWidth && height == lastCaptureHeight)return;
            int oldW = lastCaptureWidth, oldH = lastCaptureHeight;
            Surface captureSurface = cropRenderer.getCaptureSurface();
            try {
                // Detach the old buffer before updating its size. Android's
                // recommended resize flow is resize + setSurface, not resize alone.
                display.setSurface(null);
                if (!cropRenderer.updateCapturedSize(width, height)) {
                    throw new IllegalStateException("EGL resize timed out");
                }
                display.resize(width, height, dpiValue);
                display.setSurface(captureSurface);
                lastCaptureWidth = width;
                lastCaptureHeight = height;
                successfulRebinds++;
                note("REBOUND #" + successfulRebinds + " " + reason + ": "
                    + oldW + "x" + oldH + " -> " + width + "x" + height
                    + " surface=reattach ok; encoder=1280x720.");
                // Ask encoder for an IDR after a potentially changed source layout.
                handler.postDelayed(() -> {
                    MediaCodec active = codec;
                    if (!capturing.get() || active == null) return;
                    requestKeyframe();
                    if (cropRenderer != null)
                        note("GPU APOS REBOUND: " + cropRenderer.getMatrixDiagnostic());
                }, 400);
            } catch (Exception ex) {
                failedRebinds++;
                note("REBOUND FALHOU #" + failedRebinds + ": " + ex.getMessage());
                try {
                    display.setSurface(captureSurface);
                    note("Superficie original reativada apos falha.");
                } catch (Exception recovery) {
                    note("Superficie nao recuperada: " + recovery.getMessage());
                }
            }
        });
    }

    private void foreground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "NURA Espelhamento USB", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setContentTitle("NURA LD36 - captura de tela")
            .setContentText("Transmitindo video pelo adaptador USB. Volte ao app para parar.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] lastConfig = null;
        int frames = 0;
        try {
            while (capturing.get()) {
                MediaCodec c = codec;
                if (c == null) break;
                int index = c.dequeueOutputBuffer(info, 100000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = c.getOutputFormat();
                    byte[] sps = bufferToBytes(format.getByteBuffer("csd-0"));
                    byte[] pps = bufferToBytes(format.getByteBuffer("csd-1"));
                    lastConfig = concat(sps, pps);
                    if (lastConfig.length > 0) send(lastConfig, true, false);
                    continue;
                }
                if (index < 0) continue;
                try {
                    ByteBuffer bb = c.getOutputBuffer(index);
                    if (bb == null || info.size <= 0) continue;
                    bb.position(info.offset);
                    bb.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    bb.get(data);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (config) {
                        lastConfig = data;
                        // If output format provided CSD already, avoid duplicate config.
                    } else {
                        send(data, false, keyframe);
                        frames++;
                        if (frames % 90 == 0) note("H264 enviado: " + frames + " quadros.");
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } finally {
                    c.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception ex) {
            if (capturing.get()) note("Erro de codificacao/envio VDAT: " + ex.getMessage());
        } finally {
            handler.post(() -> {
                stopAll();
                stopSelf();
            });
        }
    }

    private void send(byte[] avc, boolean config, boolean keyframe) throws IOException {
        if (!capturing.get()) return;
        VideoSink sink = receiver;
        if (sink == null) throw new IOException("Transporte USB indisponivel");
        if (avc == null || avc.length == 0) return;
        if (avc.length > 1024*1024) {
            throw new IOException("Quadro maior que limite de seguranca");
        }
        sink.onVideo(avc, config, keyframe, System.currentTimeMillis());
    }

    private static byte[] bufferToBytes(ByteBuffer bb) {
        if (bb == null) return new byte[0];
        ByteBuffer clone = bb.duplicate();
        byte[] copy = new byte[clone.remaining()];
        clone.get(copy);
        return copy;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a,0,c,0,a.length);
        System.arraycopy(b,0,c,a.length,b.length);
        return c;
    }

    private synchronized void stopAll() {
        capturing.set(false);
        note("Rebinds: sucesso=" + successfulRebinds + ", falhas=" + failedRebinds);
        if(displayManager!=null && orientationListener!=null) {
            try {displayManager.unregisterDisplayListener(orientationListener);}
            catch(Exception ignored) { }
            orientationListener=null;displayManager=null;
        }
        if (display != null) { display.release(); display = null; }
        if (cropRenderer != null) { cropRenderer.close(); cropRenderer = null; }
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }
            codec = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            try { old.stop(); } catch (Exception ignored) { }
        }
        stopForeground(true);
    }

    @Override public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}

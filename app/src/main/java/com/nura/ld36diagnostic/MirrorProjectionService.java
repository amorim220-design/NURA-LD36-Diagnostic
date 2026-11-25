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
    public static final String ACTION_RESET = "com.nura.ld36diagnostic.RESET_OUTPUT_GEOMETRY";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI = "dpi";
    public static final String EXTRA_SOURCE_WIDTH = "source_width";
    public static final String EXTRA_SOURCE_HEIGHT = "source_height";
    public static final String EXTRA_CROP = "center_crop";
    /** Single native Android projection scaling into the TV resolution. No secondary GL. */
    public static final String EXTRA_DIRECT_TV = "direct_tv";
    public static final String EXTRA_AUTO_ORIGINAL = "auto_original";
    public static final String EXTRA_VDAT_ANGLE = "vdat_angle";
    public static final String EXTRA_LIVE_NATIVE = "live_native_rotation";
    public static final String EXTRA_FIXED_CANVAS = "fixed_canvas_rotation";
    public static final String EXTRA_FAST_CANVAS = "fast_canvas_rotation";
    public static final String EXTRA_PORTRAIT_ROTATE = "portrait_gpu_rotate";
    public static final String EXTRA_PORTRAIT_UPRIGHT = "portrait_gpu_upright";
    public static final String EXTRA_PORTRAIT_ASPECT = "portrait_gpu_aspect";
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
    private volatile boolean directTvMode;
    private volatile boolean autoOriginalMode;
    private volatile boolean vdatAngleMode;
    private volatile boolean liveNativeMode;
    private volatile boolean fixedCanvasMode;
    private volatile boolean fastCanvasMode;
    private volatile boolean portraitRotateMode;
    private volatile boolean portraitUprightMode;
    private volatile boolean portraitAspectMode;
    private int pendingCanvasWidth, pendingCanvasHeight;
    private volatile int canvasResizeGeneration;
    private volatile boolean canvasResizeInFlight;
    private int nativeSwitchEpoch;
    private boolean nativePortraitAtStart;
    private volatile int lastRotationDegrees = -1;
    private volatile boolean switchingEncoder;
    private volatile boolean switchingInProgress;
    private volatile int encoderWidth, encoderHeight;
    private volatile int orientationEvents;
    private volatile int codecSwitches;
    private Thread worker;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface VideoSink {
        void onVideo(byte[] avc, boolean config, boolean keyframe, long millis) throws IOException;
        void onStatus(String message);
        default void onOrientation(int rotationDegrees,int sourceWidth,int sourceHeight) { }
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
        if (ACTION_RESET.equals(intent.getAction())) {
            correctionDegrees=0;
            if (capturing.get() && cropRenderer != null) {
                cropRenderer.setCorrectionDegrees(0);
                note("GEOMETRY RESET: orientacao manual=0; aguarde quadro novo.");
                requestKeyframe();
                handler.postDelayed(() -> {
                    if (cropRenderer != null && capturing.get())
                        note("GPU RESET CONFIRMADO: " + cropRenderer.getMatrixDiagnostic());
                }, 350);
            }
            return START_NOT_STICKY;
        }
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
                    // For TV-direct the codec Surface is fixed 1280x720. Keep
                    // Android's own compositing: resizing from this callback
                    // would break the TV aspect ratio.
                    if (directTvMode || autoOriginalMode || vdatAngleMode) {
                        note((directTvMode ? "TV DIRETO CONTEUDO: " : "AUTO ORIGINAL CONTEUDO: ")+
                            width+"x"+height+
                            " encoder="+encoderWidth+"x"+encoderHeight);
                        if (vdatAngleMode) updateCaptureDimensions(width,height,
                            "MediaProjection angle probe");
                    } else {
                        updateCaptureDimensions(width,height,"MediaProjection");
                    }
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
            directTvMode = intent.getBooleanExtra(EXTRA_DIRECT_TV, false);
            autoOriginalMode = intent.getBooleanExtra(EXTRA_AUTO_ORIGINAL, false);
            vdatAngleMode = intent.getBooleanExtra(EXTRA_VDAT_ANGLE, false);
            liveNativeMode = intent.getBooleanExtra(EXTRA_LIVE_NATIVE, false);
            fixedCanvasMode = intent.getBooleanExtra(EXTRA_FIXED_CANVAS, false);
            fastCanvasMode = fixedCanvasMode && intent.getBooleanExtra(EXTRA_FAST_CANVAS, false);
            portraitRotateMode = fastCanvasMode && intent.getBooleanExtra(EXTRA_PORTRAIT_ROTATE, false);
            portraitUprightMode = portraitRotateMode && intent.getBooleanExtra(EXTRA_PORTRAIT_UPRIGHT, false);
            portraitAspectMode = portraitUprightMode && intent.getBooleanExtra(EXTRA_PORTRAIT_ASPECT, false);
            pendingCanvasWidth=0;pendingCanvasHeight=0;
            canvasResizeGeneration++;
            canvasResizeInFlight=false;
            nativeSwitchEpoch++;
            nativePortraitAtStart = height > width;
            lastRotationDegrees = -1;
            encoderWidth=width; encoderHeight=height;
            switchingEncoder=false;switchingInProgress=false;
            codecSwitches=0;orientationEvents=0;
            dpiValue=dpi;
            int sourceWidth = intent.getIntExtra(EXTRA_SOURCE_WIDTH, width);
            int sourceHeight = intent.getIntExtra(EXTRA_SOURCE_HEIGHT, height);
            if (directTvMode) {
                // Android owns both the screen-to-TV scaling and rotation. Do not
                // sample an already-fitted VirtualDisplay frame and fit it AGAIN.
                // The codec/USB sink remains a stable 1280x720 surface.
                display = projection.createVirtualDisplay("NURA-LD36-TV-DIRECT",
                    width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, handler);
                lastCaptureWidth = sourceWidth;
                lastCaptureHeight = sourceHeight;
                displayManager = (DisplayManager)getSystemService(DISPLAY_SERVICE);
                orientationListener = new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int id) { }
                    @Override public void onDisplayRemoved(int id) { }
                    @Override public void onDisplayChanged(int id) {
                        if(id != Display.DEFAULT_DISPLAY) return;
                        refreshScreenDimensions("display fisico (direto)");
                    }
                };
                if(displayManager != null)
                    displayManager.registerDisplayListener(orientationListener,handler);
                note("TV DIRETO v1.0: MediaProjection -> MediaCodec 1280x720; " +
                    "Android compoe proporcao, sem OpenGL nem redimensionamento duplicado.");
            } else if ((crop || fixedCanvasMode) && sourceWidth >= 320 && sourceHeight >= 320 &&
                    sourceWidth <= 4096 && sourceHeight <= 4096) {
                // Video codec, decoder SPS/PPS and AOA stay FIXED for entire session.
                // GPU compositor keeps aspect ratio on a changing source geometry.
                cropRenderer = new ScreenCropRenderer(inputSurface, sourceWidth, sourceHeight, width, height);
                if(portraitAspectMode) {
                    // A trial width calibration for upright portrait.
                    // v2.9 upright uses full-width=1.0 and stretches on the TV.
                    cropRenderer.setPortraitWidthScale(0.35f);
                    note("v3.0 RETRATO PROPORCIONAL: quad width 35% quando GPU=270/retrato; "+
                        "paisagem mantida 100%; H264="+width+"x"+height+
                        ", sessao USB/MediaProjection intacta");
                }
                if(portraitRotateMode && sourceHeight>sourceWidth) {
                    int portraitGpuAngle=portraitUprightMode ? 270 : 90;
                    cropRenderer.setCorrectionDegrees(portraitGpuAngle);
                    note((portraitUprightMode ? "v2.9 RETRATO PROPORCIONAL" : "v2.7 PORTRAIT ROTATE")+
                        " INICIO: GPU="+portraitGpuAngle+", src="+sourceWidth+"x"+sourceHeight+
                        " -> H264="+width+"x"+height+
                        "; orientação metadata VDAT definida pela Activity");
                }
                display = projection.createVirtualDisplay("NURA-LD36", sourceWidth, sourceHeight, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    cropRenderer.getCaptureSurface(), null, handler);
                lastCaptureWidth=sourceWidth;
                lastCaptureHeight=sourceHeight;
                note((fixedCanvasMode ? "v2.4 CANVAS FIXO" : "GPU anterior")+
                    ": fonte " + sourceWidth + "x" + sourceHeight +
                    " -> H264 FIXO " + width + "x" + height + " (sem crop).");
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
                if (autoOriginalMode) {
                    lastCaptureWidth=sourceWidth;lastCaptureHeight=sourceHeight;
                    displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                    orientationListener=new DisplayManager.DisplayListener() {
                        @Override public void onDisplayAdded(int id) { }
                        @Override public void onDisplayRemoved(int id) { }
                        @Override public void onDisplayChanged(int id) {
                            if (id==Display.DEFAULT_DISPLAY)
                                refreshScreenDimensions("display listener");
                        }
                    };
                    if(displayManager!=null)
                        displayManager.registerDisplayListener(orientationListener,handler);
                    note("AUTO ORIGINAL v1.1: captura DIRETA v0.4 inicial em " +
                        width+"x"+height+"; nova orientacao tentara trocar codec SPS/PPS.");
                } else if (vdatAngleMode) {
                    lastCaptureWidth=sourceWidth;lastCaptureHeight=sourceHeight;
                    displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                    orientationListener=new DisplayManager.DisplayListener() {
                        @Override public void onDisplayAdded(int id) { }
                        @Override public void onDisplayRemoved(int id) { }
                        @Override public void onDisplayChanged(int id) {
                            if (id==Display.DEFAULT_DISPLAY)
                                refreshScreenDimensions("VDAT display listener");
                        }
                    };
                    if(displayManager!=null)
                        displayManager.registerDisplayListener(orientationListener,handler);
                    note("VDAT ANGLE v1.3: codec original fixo "+width+"x"+height+
                        "; sem Surface resize, sem MediaCodec novo; metadado em teste.");
                } else {
                    note("Modo Original 0.4: captura direta preservada.");
                }
            }
            capturing.set(true);
            note("Captura H264 iniciada " + width + "x" + height + " 30fps; enviando VDAT.");
            if (directTvMode) {
                handler.postDelayed(() -> refreshScreenDimensions("verificacao inicial TV direto"), 1400);
            }
            if (autoOriginalMode) {
                handler.postDelayed(() -> refreshScreenDimensions("verificacao inicial AUTO ORIGINAL"), 1400);
            }
            if (liveNativeMode) note("v2.3 LIVE EXPERIMENTAL: mesma sessao USB/MediaProjection; novo SPS/PPS pode nao ser aceito pela TV.");
            if (vdatAngleMode) {
                handler.post(() -> refreshScreenDimensions("VDAT ANGLE inicial"));
                handler.postDelayed(() -> refreshScreenDimensions("VDAT ANGLE verificacao"),1200);
            }
            if (cropRenderer != null && !fixedCanvasMode) {
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
            if (!capturing.get() || display == null) return;
            if(width < 240 || height < 240 || width > 4096 || height > 4096)return;
            if (fixedCanvasMode) {
                // Unlike v2.3, never stop/start MediaCodec or change its
                // encoded geometry/SPS/PPS. Android capture -> GPU -> FIXED H264.
                if (width==lastCaptureWidth && height==lastCaptureHeight) return;
                if (fastCanvasMode && width==pendingCanvasWidth &&
                    height==pendingCanvasHeight) return; // coalesce duplicate listeners
                if (fastCanvasMode) {
                    pendingCanvasWidth=width;pendingCanvasHeight=height;
                }
                final int sequence=++canvasResizeGeneration;
                final int targetW=width,targetH=height;
                note("v2.4 CANVAS: detectou fonte "+width+"x"+height+
                    " via "+reason+"; mantendo encoder="+encoderWidth+"x"+encoderHeight+
                    " USB e MediaProjection abertos");
                handler.postDelayed(() -> {
                    if(sequence!=canvasResizeGeneration || !capturing.get() ||
                        !fixedCanvasMode || cropRenderer==null || display==null) return;
                    if(canvasResizeInFlight) {
                        // A resize of an earlier geometry is in flight.
                        // Recheck this latest target after its completion.
                        if(fastCanvasMode)
                            handler.postDelayed(() -> refreshScreenDimensions("v2.6 resize em andamento"),160);
                        return;
                    }
                    DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                    Display physical=dm==null ? null:dm.getDisplay(Display.DEFAULT_DISPLAY);
                    Point actual=new Point();
                    if(physical==null)return;
                    physical.getRealSize(actual);
                    if(actual.x!=targetW || actual.y!=targetH) {
                        note("v2.6 CANVAS: dimensoes transitórias, aguardando estado físico");
                        if(fastCanvasMode) {
                            pendingCanvasWidth=0;pendingCanvasHeight=0;
                        }
                        refreshScreenDimensions("v2.6 rechecagem");
                        return;
                    }
                    canvasResizeInFlight=true;
                    final ScreenCropRenderer renderer=cropRenderer;
                    final VirtualDisplay vd=display;
                    final Surface capture=renderer.getCaptureSurface();
                    // setDefaultBufferSize can wait on EGL. NEVER block UI here.
                    new Thread(() -> {
                        final int gpuDegrees = (portraitRotateMode && targetH>targetW) ?
                            (portraitUprightMode ? 270 : 90) : 0;
                        boolean ready=renderer.updateCapturedSize(targetW,targetH,gpuDegrees);
                        handler.post(() -> {
                            try {
                                if(!capturing.get() || sequence!=canvasResizeGeneration ||
                                    display!=vd || cropRenderer!=renderer) return;
                                if(!ready) {
                                    note("v2.4 CANVAS: GPU timeout; dimensões anteriores preservadas");
                                    return;
                                }
                                // Existing VirtualDisplay: resize only the
                                // source side, not the codec output dimensions.
                                vd.resize(targetW,targetH,dpiValue);
                                lastCaptureWidth=targetW;lastCaptureHeight=targetH;
                                requestKeyframe();
                                note((portraitAspectMode ? "v3.0 RETRATO QUAD CALIBRADO" :
                                    (portraitUprightMode ? "v2.9 RETRATO PROPORCIONAL" :
                                    (portraitRotateMode ? "v2.7 PORTRAIT GPU RECONFIGURADO" :
                                    (fastCanvasMode ? "v2.6 CANVAS RECONFIGURADO" : "v2.4 CANVAS RECONFIGURADO"))))+
                                    ": "+targetW+"x"+targetH+
                                    " dentro de H264 FIXO="+encoderWidth+"x"+encoderHeight+
                                    " | "+renderer.getMatrixDiagnostic());
                            } catch(Exception ex) {
                                note("v2.4 CANVAS ERRO: "+ex.getClass().getSimpleName()+
                                    " "+ex.getMessage()+"; pare teste se a imagem congelar");
                            } finally {
                                canvasResizeInFlight=false;
                                if(fastCanvasMode) {
                                    pendingCanvasWidth=0;pendingCanvasHeight=0;
                                    // Next orientation may have arrived during resize;
                                    // catch up without affecting MediaCodec or USB.
                                    handler.postDelayed(() ->
                                        refreshScreenDimensions("v2.6 verificacao apos resize"),120);
                                }
                            }
                        });
                    },"nura-v24-gpu-resize").start();
                },fastCanvasMode ? 90 : 450);
                return;
            }
            if (liveNativeMode) {
                // Experimental same-projection reconfiguration. No USB reconnect,
                // no new MediaProjection token. This MAY NOT be accepted by the
                // dongle decoder without re-negotiating STAR.
                lastCaptureWidth=width;
                lastCaptureHeight=height;
                DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                Display physical=dm==null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
                int degrees=physical==null ? 0 : physical.getRotation()*90;
                int desiredW = width >= height ? 1280 :
                    Math.max(320,Math.min(720,Math.round((1280f*width/height)/16f)*16));
                int desiredH = width >= height ?
                    Math.max(320,Math.min(720,Math.round((1280f*height/width)/16f)*16)) : 1280;
                VideoSink sink=receiver;
                if(sink!=null)sink.onOrientation(degrees,width,height);
                if(desiredW==encoderWidth && desiredH==encoderHeight) {
                    note("v2.3 LIVE: fonte="+width+"x"+height+
                        " codec ja corresponde="+encoderWidth+"x"+encoderHeight);
                    requestKeyframe();
                    return;
                }
                final int epoch=++nativeSwitchEpoch;
                note("v2.3 LIVE: mudanca "+reason+" origem="+width+"x"+height+
                    " codec="+encoderWidth+"x"+encoderHeight+" alvo="+desiredW+"x"+desiredH+
                    " (aguardando estabilizar 450ms, AOA preservado)");
                handler.postDelayed(() -> {
                    if(!capturing.get() || !liveNativeMode || epoch!=nativeSwitchEpoch ||
                        switchingInProgress) return;
                    Point physicalSize=new Point();
                    DisplayManager m=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                    Display current=m==null ? null : m.getDisplay(Display.DEFAULT_DISPLAY);
                    if(current==null)return;
                    current.getRealSize(physicalSize);
                    if((physicalSize.y > physicalSize.x)!=(desiredH > desiredW)) {
                        note("v2.3 LIVE: giro transitório; codec preservado.");
                        return;
                    }
                    note("v2.3 LIVE: tentativa de nova configuracao H264 sem encerrar AOA.");
                    switchOriginalCodec(desiredW,desiredH);
                },450);
                return;
            }
            if (vdatAngleMode) {
                // The original H264 decoder configuration remains untouched.
                // Only the documented 32-bit VDAT angle metadata is tested.
                DisplayManager manager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
                Display actual=manager==null ? null:manager.getDisplay(Display.DEFAULT_DISPLAY);
                int degrees=0;
                if(actual!=null) {
                    switch(actual.getRotation()) {
                        case Surface.ROTATION_90: degrees=90; break;
                        case Surface.ROTATION_180: degrees=180; break;
                        case Surface.ROTATION_270: degrees=270; break;
                        default: degrees=0;
                    }
                }
                boolean changed=degrees!=lastRotationDegrees;
                lastCaptureWidth=width; lastCaptureHeight=height;
                if(changed) {
                    lastRotationDegrees=degrees;
                    VideoSink sink=receiver;
                    if(sink!=null)sink.onOrientation(degrees,width,height);
                    note("VDAT ANGLE ATUALIZADO ("+reason+"): "+degrees+
                        " graus, src="+width+"x"+height+
                        " codec FIXO="+encoderWidth+"x"+encoderHeight+
                        " sem trocar SPS/PPS, sem rebind.");
                    requestKeyframe();
                }
                return;
            }
            if(width == lastCaptureWidth && height == lastCaptureHeight)return;
            if (autoOriginalMode) {
                int oldW=lastCaptureWidth, oldH=lastCaptureHeight;
                lastCaptureWidth=width; lastCaptureHeight=height;
                orientationEvents++;
                note("AUTO ORIGINAL EVENTO #"+orientationEvents+" "+reason+
                    " fonte "+oldW+"x"+oldH+" -> "+width+"x"+height+
                    " codec="+encoderWidth+"x"+encoderHeight);
                int desiredW=width>=height ? 1280 : 720;
                int desiredH=width>=height ? 720 : 1280;
                if(desiredW!=encoderWidth || desiredH!=encoderHeight)
                    switchOriginalCodec(desiredW,desiredH);
                else requestKeyframe();
                return;
            }
            if (directTvMode) {
                int oldW=lastCaptureWidth, oldH=lastCaptureHeight;
                lastCaptureWidth=width; lastCaptureHeight=height;
                note("TV DIRETO ROTACAO ("+reason+"): fonte "+oldW+"x"+oldH+
                    " -> "+width+"x"+height+
                    "; Surface encoder=1280x720 INALTERADA, Android faz fit nativo.");
                // Android composes new source into the same fixed 16:9 display.
                // Never resize or rebind the codec input Surface here.
                handler.postDelayed(this::requestKeyframe, 250);
                return;
            }
            if (cropRenderer == null) return;
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
                correctionDegrees=0;
                note("RESET EM ROTACAO FISICA: orientacao manual=0; quadrante preservado.");
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

    /**
     * Opt-in experiment: replace only the MediaCodec input Surface of the
     * existing VirtualDisplay. Reusing the MediaProjection token to create
     * another VirtualDisplay can fail on recent Android releases.
     *
     * No STAR renegotiation is proven. A TV black screen after SPS/PPS change
     * is diagnostic of receiver compatibility, not evidence that Android
     * failed to produce frames.
     */
    private void switchOriginalCodec(int newW,int newH) {
        if (switchingInProgress || !capturing.get()) return;
        switchingInProgress=true;
        switchingEncoder=true; // let the old encoder drain loop exit before stop()
        final MediaCodec previous=codec;
        final Surface previousSurface=inputSurface;
        final VirtualDisplay vd=display;
        final Thread previousWorker=worker;
        final int previousW=encoderWidth,previousH=encoderHeight;
        note((liveNativeMode ? "v2.3 LIVE TROCA" : "AUTO ORIGINAL TROCA")+": preparando "+previousW+"x"+previousH+
            " -> "+newW+"x"+newH+
            "; LD36 ainda nao confirmou suporte a SPS/PPS dinamico.");
        new Thread(() -> {
            MediaCodec next=null;
            Surface nextSurface=null;
            boolean transferred=false;
            try {
                if (previousWorker!=null) {
                    previousWorker.join(2000);
                    if(previousWorker.isAlive())
                        throw new IllegalStateException("timeout aguardando encoder anterior");
                }
                if(!capturing.get() || vd==null)
                    throw new IllegalStateException("captura foi encerrada");
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",newW,newH);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE,4000000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE,30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
                next=MediaCodec.createEncoderByType("video/avc");
                next.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                nextSurface=next.createInputSurface();
                next.start();
                if(!capturing.get())throw new IllegalStateException("captura encerrada");
                vd.setSurface(null);
                vd.resize(newW,newH,dpiValue);
                vd.setSurface(nextSurface);
                transferred=true;
                codec=next;
                inputSurface=nextSurface;
                encoderWidth=newW; encoderHeight=newH;
                codecSwitches++;
                // Starting fresh encodeLoop will resend new codec SPS/PPS/IDR.
                switchingEncoder=false;
                worker=new Thread(this::encodeLoop,"nura-h264-auto-"+codecSwitches);
                worker.start();
                note("AUTO ORIGINAL TROCA OK #"+codecSwitches+" codec="+newW+
                    "x"+newH+"; esperando nova configuracao H264; TV ainda precisa de teste.");
                if(previous!=null) {
                    try{previous.stop();}catch(Exception ignored){}
                    try{previous.release();}catch(Exception ignored){}
                }
                if(previousSurface!=null)previousSurface.release();
            } catch(Exception e) {
                note("AUTO ORIGINAL TROCA FALHOU: "+e.getMessage());
                if (transferred) {
                    note("AUTO ORIGINAL: Surface nova ja anexada; encerre e reinicie captura.");
                    handler.post(this::stopAll);
                } else {
                    // If the previous encoder worker is STILL alive (possibly
                    // blocked on a USB write), NEVER start a second reader on
                    // the same MediaCodec. The old Surface was not transferred.
                    codec=previous;
                    inputSurface=previousSurface;
                    switchingEncoder=false;
                    if (capturing.get() && previousWorker != null
                            && previousWorker.isAlive()) {
                        worker=previousWorker;
                        note("AUTO ORIGINAL TIMEOUT SEGURO: worker anterior ainda vivo; "+
                            "sem segundo leitor ou rebind. "+
                            "Reinicie sessao antes de tentar nova rotacao.");
                    } else if (capturing.get()) {
                        worker=new Thread(this::encodeLoop,"nura-h264-auto-recovery");
                        worker.start();
                        note("AUTO ORIGINAL RECUPERACAO: worker anterior terminou; "+
                            "leitura retomada no codec original.");
                    }
                }
                if(next!=null && !transferred) {
                    try{next.stop();}catch(Exception ignored){}
                    try{next.release();}catch(Exception ignored){}
                }
                if(nextSurface!=null && !transferred)nextSurface.release();
            } finally {
                switchingInProgress=false;
            }
        },"nura-switch-original-codec").start();
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
            while (capturing.get() && !switchingEncoder) {
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
            if (!switchingEncoder) {
                handler.post(() -> {
                    if (capturing.get()) {
                        stopAll();
                        stopSelf();
                    }
                });
            }
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
        nativeSwitchEpoch++;
        canvasResizeGeneration++;
        canvasResizeInFlight=false;
        pendingCanvasWidth=0;pendingCanvasHeight=0;
        note("Rebinds: sucesso=" + successfulRebinds + ", falhas=" + failedRebinds+
            " | AUTO ORIGINAL mudancas="+codecSwitches+" eventos="+orientationEvents);
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

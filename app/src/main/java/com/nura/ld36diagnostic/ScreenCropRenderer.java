package com.nura.ld36diagnostic;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GPU-only screen compositor: captures real display to a SurfaceTexture and
 * fits the entire changing screen inside a fixed encoder surface without cropping.
 * EGL stays on one HandlerThread; never read back frames through the CPU.
 */
public final class ScreenCropRenderer implements AutoCloseable {
    private final HandlerThread thread = new HandlerThread("NURA-LD36-GPU");
    private Handler handler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture sourceTexture;
    private Surface sourceSurface;
    private int textureId;
    private int program;
    private int attributePosition, attributeUv, uniformTexture, uniformMatrix;
    private final float[] textureMatrix = new float[16];
    private final java.nio.FloatBuffer pos = asBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
    private volatile int displayWidth,displayHeight;
    private volatile int correctionDegrees;
    // Extra portrait letterbox calibration; 1.0 leaves the proven v2.9 geometry unchanged.
    private volatile float portraitWidthScale = 1.0f;
    private volatile boolean matrixLogged;
    private volatile String matrixDiagnostic = "matriz ainda nao recebida";
    private volatile String geometryDiagnostic = "geometria ainda nao calculada";
    private java.nio.FloatBuffer uv;
    private final Surface encoderSurface;
    private final int sourceWidth, sourceHeight, encodedWidth, encodedHeight;
    private volatile boolean closed;
    private volatile int rendered;

    private static java.nio.FloatBuffer asBuffer(float[] values) {
        java.nio.ByteBuffer b=java.nio.ByteBuffer.allocateDirect(values.length*4)
            .order(java.nio.ByteOrder.nativeOrder());
        java.nio.FloatBuffer f=b.asFloatBuffer();
        f.put(values).position(0);
        return f;
    }

    public ScreenCropRenderer(Surface destination, int sourceW, int sourceH,
                              int outputW, int outputH) throws Exception {
        encoderSurface=destination;
        sourceWidth=sourceW;sourceHeight=sourceH;
        displayWidth=sourceW;displayHeight=sourceH;
        encodedWidth=outputW;encodedHeight=outputH;
        thread.start();
        handler=new Handler(thread.getLooper());
        CountDownLatch latch=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        handler.post(() -> {
            try { init(); }
            catch(Throwable t) { error.set(t); cleanup(); }
            finally { latch.countDown(); }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("EGL startup timeout");
        }
        if (error.get()!=null) {
            close();
            throw new IllegalStateException("EGL initialization failed",error.get());
        }
    }

    public Surface getCaptureSurface() { return sourceSurface; }

    /**
     * Resize the consumer buffer BEFORE VirtualDisplay.resize/setSurface.
     * Synchronous to avoid an old-size buffer being rebound during rotation.
     */
    public boolean updateCapturedSize(int width, int height) {
        return updateCapturedSize(width,height,0);
    }

    /**
     * Atomically change the source buffer geometry and its UV orientation.
     * Fixes portrait content being downscaled into a narrow vertical stripe
     * before the dongle applies its own VDAT 90° orientation.
     *
     * On v2.7 portrait 1080x2400 -> rotated 2400x1080 -> H264 1280x576
     * instead of portrait 1080x2400 -> 259x576 narrow letterbox.
     */
    public boolean updateCapturedSize(int width, int height, int degrees) {
        final int normalized=RotationGeometry.normalize(degrees);
        if (closed || width < 240 || height < 240 || width > 4096 || height > 4096) return false;
        if (width == displayWidth && height == displayHeight && normalized==correctionDegrees) return true;
        Handler h = handler;
        if (h == null) return false;
        CountDownLatch ready = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean updated = new java.util.concurrent.atomic.AtomicBoolean(false);
        h.post(() -> {
            try {
                if (closed || sourceTexture == null) return;
                sourceTexture.setDefaultBufferSize(width, height);
                // A prior manual correction applies to the previous screen
                // orientation; do not carry it into a newly rotated buffer.
                correctionDegrees=normalized;
                displayWidth = width;
                displayHeight = height;
                updateGeometry(width,height);
                matrixLogged = false;
                updated.set(true);
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(1500, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return updated.get();
    }
    /** Explicit correction for phones whose frames arrive with mismatched orientation. */
    public void setCorrectionDegrees(int degrees) {
        final int normalized=RotationGeometry.normalize(degrees);
        Handler h=handler;
        if (h==null || closed) return;
        h.post(() -> {
            if (closed || sourceTexture == null) return;
            correctionDegrees=normalized;
            updateGeometry(displayWidth,displayHeight);
            matrixLogged=false;
            android.util.Log.i("NURA-LD36","UV correction="+normalized+
                " source="+displayWidth+"x"+displayHeight+
                " output="+encodedWidth+"x"+encodedHeight);
        });
    }

    /**
     * Diagnostic aspect adjustment for vertical-to-landscape LD36 transport.
     * Changes ONLY the rendered quad width, never the source texture, codec
     * dimensions, SurfaceTexture or USB protocol. Safe to invoke at startup.
     * Calibration must be verified on the physical TV.
     */
    public void setPortraitWidthScale(float scale) {
        if(Float.isNaN(scale) || scale < 0.20f || scale > 1.0f) return;
        Handler h=handler;
        if(h==null || closed) return;
        h.post(() -> {
            if(closed) return;
            portraitWidthScale=scale;
            updateGeometry(displayWidth,displayHeight);
            matrixLogged=false;
            android.util.Log.i("NURA-LD36","v3.0 PORTRAIT LETTERBOX scale="+scale+
                " portrait="+(displayHeight>displayWidth)+
                " gpu="+correctionDegrees);
        });
    }

    public int getCorrectionDegrees() { return correctionDegrees; }
    public String getMatrixDiagnostic() {
        return geometryDiagnostic + " | " + matrixDiagnostic;
    }

    private void updateGeometry(int width,int height) {
        float[] points=RotationGeometry.quad(width,height,encodedWidth,encodedHeight,correctionDegrees);
        // The v2.9 profile was physically upright but stretched on the TV.
        // Limit width only for portrait; landscape remains exactly v2.6.
        // Shrinking this quad paints black *side* bars in the encoded frame,
        // without altering its 1280x576 dimensions or pausing rendering.
        float effectiveWidthScale=(height>width && correctionDegrees==270)
            ? portraitWidthScale : 1.0f;
        if(effectiveWidthScale<1.0f) {
            for(int i=0;i<points.length;i+=2)
                points[i] *= effectiveWidthScale;
        }
        int fittedW = correctionDegrees % 180 == 0 ? width : height;
        int fittedH = correctionDegrees % 180 == 0 ? height : width;
        geometryDiagnostic = "GEOMETRY realGPU="+correctionDegrees+
            " src="+width+"x"+height+" oriented="+fittedW+"x"+fittedH+
            " encoder="+encodedWidth+"x"+encodedHeight+
            " quad="+points[2]+"x"+points[5]+
            " portraitScale="+effectiveWidthScale+
            " aspect="+String.format(java.util.Locale.US,"%.4f",(double)fittedW/fittedH);
        pos.clear();
        pos.put(points);
        pos.position(0);
        float[] uvs=RotationGeometry.uv(correctionDegrees);
        if (uv==null) uv=asBuffer(uvs);
        else { uv.clear();uv.put(uvs);uv.position(0); }
    }

    public int getRenderedFrames() { return rendered; }

    private void init() {
        eglDisplay=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(eglDisplay==EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("eglGetDisplay");
        int[] vers=new int[2];
        check(EGL14.eglInitialize(eglDisplay,vers,0,vers,1),"eglInitialize");
        int[] attrib={
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            0x3142,1,EGL14.EGL_NONE
        };
        EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
        check(EGL14.eglChooseConfig(eglDisplay,attrib,0,configs,0,1,n,0)&&n[0]>0,"eglChooseConfig");
        eglContext=EGL14.eglCreateContext(eglDisplay,configs[0],EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        if(eglContext==EGL14.EGL_NO_CONTEXT)throw new IllegalStateException("eglCreateContext");
        eglSurface=EGL14.eglCreateWindowSurface(eglDisplay,configs[0],encoderSurface,
            new int[]{EGL14.EGL_NONE},0);
        if(eglSurface==EGL14.EGL_NO_SURFACE)throw new IllegalStateException("eglCreateWindowSurface");
        check(EGL14.eglMakeCurrent(eglDisplay,eglSurface,eglSurface,eglContext),"eglMakeCurrent");
        int[] tex=new int[1];GLES20.glGenTextures(1,tex,0);textureId=tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        sourceTexture=new SurfaceTexture(textureId);
        sourceTexture.setDefaultBufferSize(sourceWidth,sourceHeight);
        sourceSurface=new Surface(sourceTexture);
        String vertex="attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;"+
            "uniform mat4 uTex; void main(){gl_Position=aPos;"+
            "vUv=(uTex*vec4(aUv,0.0,1.0)).xy;}";
        String fragment="#extension GL_OES_EGL_image_external : require\n"+
            "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uImage;"+
            "void main(){gl_FragColor=texture2D(uImage,vUv);}";
        int vs=shader(GLES20.GL_VERTEX_SHADER,vertex),fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        program=GLES20.glCreateProgram();
        GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);
        GLES20.glLinkProgram(program);
        int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
        if(ok[0]!=GLES20.GL_TRUE)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        attributePosition=GLES20.glGetAttribLocation(program,"aPos");
        attributeUv=GLES20.glGetAttribLocation(program,"aUv");
        uniformMatrix=GLES20.glGetUniformLocation(program,"uTex");
        uniformTexture=GLES20.glGetUniformLocation(program,"uImage");
        // No crop. Black bars are intentional if handset and TV ratios differ.
        updateGeometry(sourceWidth,sourceHeight);
        GLES20.glViewport(0,0,encodedWidth,encodedHeight);
        sourceTexture.setOnFrameAvailableListener(st -> {
            if(!closed && handler!=null)handler.post(this::render);
        },handler);
    }

    private static int shader(int type,String src) {
        int id=GLES20.glCreateShader(type);
        GLES20.glShaderSource(id,src);GLES20.glCompileShader(id);
        int[] ok=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]!=GLES20.GL_TRUE) {
            String log=GLES20.glGetShaderInfoLog(id);GLES20.glDeleteShader(id);
            throw new IllegalStateException("GL compile "+log);
        }
        return id;
    }
    private static void check(boolean ok,String operation) {
        if(!ok)throw new IllegalStateException(operation);
    }

    private void render() {
        if(closed || sourceTexture==null)return;
        try {
            sourceTexture.updateTexImage();
            sourceTexture.getTransformMatrix(textureMatrix);
            if (!matrixLogged) {
                matrixLogged = true;
                matrixDiagnostic = "SurfaceTexture UV rot="+correctionDegrees+
                    " src="+displayWidth+"x"+displayHeight+
                    " mat=["+textureMatrix[0]+","+textureMatrix[1]+","+
                    textureMatrix[4]+","+textureMatrix[5]+","+
                    textureMatrix[12]+","+textureMatrix[13]+"]";
                android.util.Log.i("NURA-LD36",matrixDiagnostic);
            }
            GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glUniform1i(uniformTexture,0);
            GLES20.glUniformMatrix4fv(uniformMatrix,1,false,textureMatrix,0);
            pos.position(0);uv.position(0);
            GLES20.glEnableVertexAttribArray(attributePosition);
            GLES20.glVertexAttribPointer(attributePosition,2,GLES20.GL_FLOAT,false,0,pos);
            GLES20.glEnableVertexAttribArray(attributeUv);
            GLES20.glVertexAttribPointer(attributeUv,2,GLES20.GL_FLOAT,false,0,uv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(attributePosition);
            GLES20.glDisableVertexAttribArray(attributeUv);
            EGLExt.eglPresentationTimeANDROID(eglDisplay,eglSurface,System.nanoTime());
            check(EGL14.eglSwapBuffers(eglDisplay,eglSurface),"eglSwapBuffers");
            rendered++;
        } catch(Exception e) {
            android.util.Log.e("NURA-LD36","GPU frame render failed",e);
        }
    }
    private void cleanup() {
        if(sourceTexture!=null) {sourceTexture.setOnFrameAvailableListener(null);sourceTexture.release();sourceTexture=null;}
        if(sourceSurface!=null) {sourceSurface.release();sourceSurface=null;}
        if(program!=0) {GLES20.glDeleteProgram(program);program=0;}
        if(textureId!=0) {GLES20.glDeleteTextures(1,new int[]{textureId},0);textureId=0;}
        if(eglDisplay!=EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(eglSurface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(eglDisplay,eglSurface);
            if(eglContext!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(eglDisplay,eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay=EGL14.EGL_NO_DISPLAY;eglContext=EGL14.EGL_NO_CONTEXT;eglSurface=EGL14.EGL_NO_SURFACE;
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        if(handler!=null) {
            CountDownLatch released=new CountDownLatch(1);
            handler.post(() -> {
                try {cleanup();} finally {released.countDown();thread.quitSafely();}
            });
            if(Thread.currentThread()!=thread) {
                try {released.await(2,TimeUnit.SECONDS);}
                catch(InterruptedException e) {Thread.currentThread().interrupt();}
            }
        }
    }
}

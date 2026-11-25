package com.strickland.jeeptune;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.Surface;

import com.strickland.jeeptune.core.Cam;
import com.strickland.jeeptune.core.SurfaceMesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * The 3D surface: OpenGL ES 3.0 (2.0 when 3.0 is unavailable), no engine library. Lit smooth-shaded mesh with a vertex
 * colour ramp, grid lines, a translucent ghost of the compare table, change outlines, texture-atlas axis labels and
 * additive glow billboards for the replay/live dots. Renders only when something changed, except while animating.
 */
final class GlView extends GLSurfaceView {
    final Cam cam = new Cam();
    private final float dp;
    private final Scene scene = new Scene();
    private final EdgeSwipe edge;
    private final GestureDetector taps;
    private TableRenderer.Listener listener;

    private volatile SurfaceMesh mesh;
    private volatile int meshGen;
    private volatile LabelSet labels;
    private volatile int labelGen;
    private volatile boolean ghostOn;
    private volatile int hovR = -1, hovC = -1, selR = -1, selC = -1;
    private volatile double[] replayDot = {Double.NaN, Double.NaN}, liveDot = {Double.NaN, Double.NaN};
    private volatile double[][] trail = {new double[0], new double[0], new double[0]};

    private boolean externalAnim, gesturing;
    private int lastCount;
    private float lx, ly, lcx, lcy, lspan, lang;
    /** Last pen pressure seen on this view (0..1); reserved for the future editor, not used for drawing. */
    float penPressure;

    GlView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        setEGLConfigChooser(new Chooser());
        setEGLContextFactory(new CtxFactory());
        setPreserveEGLContextOnPause(true);
        setRenderer(scene);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> excludeEdges(r - l, b - t));
        edge = new EdgeSwipe(this, d -> { if (listener != null) listener.onSwipe(d); });
        taps = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { resetCamera(); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int[] rc = pick(e.getX(), e.getY());
                if (rc != null) {
                    selR = rc[0];
                    selC = rc[1];
                    requestRender();
                    if (listener != null) listener.onCell(rc[0], rc[1]);
                }
                return true;
            }
        });
    }

    void setListener(TableRenderer.Listener l) { listener = l; }

    // ---------- state from the UI thread ----------

    void setMesh(SurfaceMesh m, LabelSet ls, boolean newTable) {
        mesh = m;
        meshGen++;
        labels = ls;
        labelGen++;
        if (newTable) { selR = -1; selC = -1; hovR = -1; hovC = -1; }
        requestRender();
    }

    void setGhost(boolean on) { ghostOn = on; requestRender(); }
    void setReplay(double row, double col) { replayDot = new double[]{row, col}; requestRender(); }
    void setLive(double row, double col) { liveDot = new double[]{row, col}; requestRender(); }
    void setTrail(double[] r, double[] c, double[] a) { trail = new double[][]{r, c, a}; requestRender(); }

    void setExternalAnimating(boolean on) { externalAnim = on; updateMode(); }

    void setTilt(double yaw, double pitch) {
        synchronized (cam) { cam.tiltYaw = yaw; cam.tiltPitch = pitch; }
        requestRender();
    }

    void resetCamera() {
        synchronized (cam) { cam.reset(); }
        requestRender();
    }

    private void updateMode() {
        setRenderMode(gesturing || externalAnim ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        if (Build.VERSION.SDK_INT >= 30) {
            try { holder.getSurface().setFrameRate(90f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); }
            catch (RuntimeException ignored) { }
        }
    }

    // ---------- touch ----------

    private int[] pick(float x, float y) {
        SurfaceMesh m = mesh;
        if (m == null || getWidth() == 0) return null;
        double[] o = new double[3], d = new double[3];
        boolean ok;
        synchronized (cam) { ok = cam.ray(x, y, getWidth(), getHeight(), o, d); }
        return ok ? m.pick(o, d) : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        penPressure = Pen.pressure(ev);
        if (edge.onTouch(ev)) return true;
        taps.onTouchEvent(ev);
        int n = ev.getPointerCount();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gesturing = true;
                updateMode();
                lastCount = 0;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gesturing = false;
                updateMode();
                lastCount = 0;
                return true;
            default:
                break;
        }
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_POINTER_UP) n = n - 1;      // the pointer lifting no longer counts
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN || act == MotionEvent.ACTION_POINTER_UP || n != lastCount) {
            start(ev, act == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1);
            lastCount = n;
            return true;
        }
        if (act != MotionEvent.ACTION_MOVE) return true;
        synchronized (cam) {
            if (n == 1) {
                cam.orbit(ev.getX() - lx, ev.getY() - ly);
                lx = ev.getX();
                ly = ev.getY();
            } else if (n >= 2) {
                float cx = (ev.getX(0) + ev.getX(1)) / 2, cy = (ev.getY(0) + ev.getY(1)) / 2;
                float dx = ev.getX(1) - ev.getX(0), dy = ev.getY(1) - ev.getY(0);
                float span = (float) Math.hypot(dx, dy), ang = (float) Math.atan2(dy, dx);
                cam.pan(cx - lcx, cy - lcy, getHeight());
                if (lspan > 10) cam.zoom(span / lspan);
                float da = ang - lang;
                while (da > Math.PI) da -= 2 * Math.PI;
                while (da < -Math.PI) da += 2 * Math.PI;
                cam.twist(-da);
                lcx = cx; lcy = cy; lspan = span; lang = ang;
            }
        }
        requestRender();
        return true;
    }

    /** Re-bases the gesture state when the number of fingers changes, so the view does not jump. */
    private void start(MotionEvent ev, int skip) {
        int a = -1, b = -1;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (i == skip) continue;
            if (a < 0) a = i; else if (b < 0) b = i;
        }
        if (a < 0) return;
        lx = ev.getX(a);
        ly = ev.getY(a);
        if (b >= 0) {
            lcx = (ev.getX(a) + ev.getX(b)) / 2;
            lcy = (ev.getY(a) + ev.getY(b)) / 2;
            float dx = ev.getX(b) - ev.getX(a), dy = ev.getY(b) - ev.getY(a);
            lspan = (float) Math.hypot(dx, dy);
            lang = (float) Math.atan2(dy, dx);
        }
    }

    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        if (!Pen.isStylus(ev)) return super.onHoverEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            hovR = -1;
            hovC = -1;
            requestRender();
            if (listener != null) listener.onHover(-1, -1, ev.getX(), ev.getY());
            return true;
        }
        int[] rc = pick(ev.getX(), ev.getY());
        hovR = rc == null ? -1 : rc[0];
        hovC = rc == null ? -1 : rc[1];
        requestRender();
        if (listener != null) listener.onHover(hovR, hovC, ev.getX(), ev.getY());
        return true;
    }

    /** Keeps the system back gesture off the edge-swipe strips (Android 10+). */
    private void excludeEdges(int w, int h) {
        if (Build.VERSION.SDK_INT >= 29 && w > 0) {
            int e = (int) (28 * dp);
            setSystemGestureExclusionRects(java.util.Arrays.asList(
                    new android.graphics.Rect(0, 0, e, h), new android.graphics.Rect(w - e, 0, w, h)));
        }
    }

    // ---------- EGL setup: 4x MSAA when the GPU offers it, ES 3.0 context with ES 2.0 fallback ----------

    private static final class Chooser implements EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            int[][] tries = {{0x40, 4}, {0x40, 0}, {4, 4}, {4, 0}};     // renderable bit (ES3, ES2), samples
            for (int[] t : tries) {
                int[] a = t[1] > 0
                        ? new int[]{EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16, 0x3040, t[0], EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, t[1], EGL10.EGL_NONE}
                        : new int[]{EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16, 0x3040, t[0], EGL10.EGL_NONE};
                EGLConfig[] cfg = new EGLConfig[1];
                int[] n = new int[1];
                if (egl.eglChooseConfig(d, a, cfg, 1, n) && n[0] > 0) return cfg[0];
            }
            throw new IllegalArgumentException("no EGL config");
        }
    }

    private static final class CtxFactory implements EGLContextFactory {
        private static final int VERSION = 0x3098;
        @Override public EGLContext createContext(EGL10 egl, EGLDisplay d, EGLConfig cfg) {
            EGLContext c = egl.eglCreateContext(d, cfg, EGL10.EGL_NO_CONTEXT, new int[]{VERSION, 3, EGL10.EGL_NONE});
            if (c == null || c == EGL10.EGL_NO_CONTEXT) c = egl.eglCreateContext(d, cfg, EGL10.EGL_NO_CONTEXT, new int[]{VERSION, 2, EGL10.EGL_NONE});
            return c;
        }
        @Override public void destroyContext(EGL10 egl, EGLDisplay d, EGLContext c) { egl.eglDestroyContext(d, c); }
    }

    // ---------- the GL side (runs on the GL thread) ----------

    private final class Scene implements Renderer {
        private boolean es3;
        private int progSurf, progLine, progLabel, progDot;
        private int vbMesh, ibMesh, vbGhost, vbLines, vbHi, vbLabel, vbDot, tex;
        private int meshSeen = -1, labelSeen = -1, idxCount, lineCount, ghostCount, vw = 1, vh = 1;
        private boolean hasGhost;
        private final float[] vpf = new float[16];
        private final double[] vp = new double[16], eye = new double[3], tmp = new double[3];
        private FloatBuffer dynBuf = alloc(4096);

        private FloatBuffer alloc(int floats) { return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            String ver = GLES20.glGetString(GLES20.GL_VERSION);
            es3 = ver != null && ver.contains("OpenGL ES 3");
            progSurf = program(SURF_V, SURF_F);
            progLine = program(LINE_V, LINE_F);
            progLabel = program(LABEL_V, LABEL_F);
            progDot = program(DOT_V, DOT_F);
            int[] b = new int[7];
            GLES20.glGenBuffers(7, b, 0);
            vbMesh = b[0]; ibMesh = b[1]; vbGhost = b[2]; vbLines = b[3]; vbHi = b[4]; vbLabel = b[5]; vbDot = b[6];
            int[] tx = new int[1];
            GLES20.glGenTextures(1, tx, 0);
            tex = tx[0];
            meshSeen = -1;       // everything is re-uploaded (new context)
            labelSeen = -1;
            GLES20.glClearColor(0.055f, 0.071f, 0.09f, 1f);
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            vw = Math.max(1, w);
            vh = Math.max(1, h);
            GLES20.glViewport(0, 0, vw, vh);
        }

        private String header(boolean frag) {
            if (es3) {
                return "#version 300 es\n" + (frag ? "precision highp float;\n#define VARY_IN in\nout vec4 fragColor;\n#define FRAG fragColor\n#define TEX2D texture\n"
                        : "#define ATTR in\n#define VARY_OUT out\n");
            }
            return frag ? "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n#define VARY_IN varying\n#define FRAG gl_FragColor\n#define TEX2D texture2D\n"
                    : "#define ATTR attribute\n#define VARY_OUT varying\n";
        }

        private int program(String vs, String fs) {
            int v = shader(GLES20.GL_VERTEX_SHADER, header(false) + vs), f = shader(GLES20.GL_FRAGMENT_SHADER, header(true) + fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glBindAttribLocation(p, 1, "aA");
            GLES20.glBindAttribLocation(p, 2, "aB");
            GLES20.glBindAttribLocation(p, 3, "aC");
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL link: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL compile: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private void upload(int target, int buf, FloatBuffer fb, int floats, int usage) {
            GLES20.glBindBuffer(target, buf);
            fb.position(0);
            GLES20.glBufferData(target, floats * 4, fb, usage);
        }

        private FloatBuffer fb(float[] a) {
            FloatBuffer f = alloc(a.length);
            f.put(a);
            f.position(0);
            return f;
        }

        private void sync() {
            int g = meshGen;
            SurfaceMesh m = mesh;
            if (g != meshSeen) {
                meshSeen = g;
                if (m != null) {
                    upload(GLES20.GL_ARRAY_BUFFER, vbMesh, fb(m.verts), m.verts.length, GLES20.GL_STATIC_DRAW);
                    ShortBuffer ib = ByteBuffer.allocateDirect(m.indices.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
                    ib.put(m.indices);
                    ib.position(0);
                    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibMesh);
                    GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.indices.length * 2, ib, GLES20.GL_STATIC_DRAW);
                    idxCount = m.indices.length;
                    hasGhost = m.ghost != null;
                    if (hasGhost) {
                        upload(GLES20.GL_ARRAY_BUFFER, vbGhost, fb(m.ghost), m.ghost.length, GLES20.GL_STATIC_DRAW);
                        ghostCount = m.ghost.length;
                    }
                    upload(GLES20.GL_ARRAY_BUFFER, vbLines, fb(m.lines), m.lines.length, GLES20.GL_STATIC_DRAW);
                    lineCount = m.lines.length / SurfaceMesh.LINE_FLOATS;
                } else {
                    idxCount = 0;
                }
            }
            int lg = labelGen;
            if (lg != labelSeen) {
                labelSeen = lg;
                LabelSet ls = labels;
                if (ls != null && ls.atlas != null) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, ls.atlas, 0);
                }
            }
        }

        private void attr(int idx, int size, int stride, int offset) {
            GLES20.glEnableVertexAttribArray(idx);
            GLES20.glVertexAttribPointer(idx, size, GLES20.GL_FLOAT, false, stride * 4, offset * 4);
        }

        private void off(int... idx) { for (int i : idx) GLES20.glDisableVertexAttribArray(i); }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            sync();
            SurfaceMesh m = mesh;
            if (m == null || idxCount == 0) return;
            synchronized (cam) { cam.viewProj(vw / (double) vh, vp, eye); }
            for (int i = 0; i < 16; i++) vpf[i] = (float) vp[i];

            // opaque surface
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
            GLES20.glPolygonOffset(1f, 1f);
            GLES20.glUseProgram(progSurf);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progSurf, "uVP"), 1, false, vpf, 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(progSurf, "uEye"), (float) eye[0], (float) eye[1], (float) eye[2]);
            drawMesh(vbMesh);
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);

            // thin grid lines and change outlines
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progLine);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progLine, "uVP"), 1, false, vpf, 0);
            GLES20.glLineWidth(1f);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbLines);
            attr(0, 3, SurfaceMesh.LINE_FLOATS, 0);
            attr(2, 4, SurfaceMesh.LINE_FLOATS, 3);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount);
            off(0, 2);

            // ghost stock surface: translucent, after everything opaque, no depth writes
            if (ghostOn && hasGhost) {
                GLES20.glDepthMask(false);
                GLES20.glUseProgram(progSurf);
                drawMesh(vbGhost);
                GLES20.glDepthMask(true);
            }

            // highlight rings (hover bright, selected dim) over everything
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(progLine);
            GLES20.glLineWidth(3f);
            ring(m, selR, selC, 0.8f, 0.8f, 0.8f);
            ring(m, hovR, hovC, 1f, 0.82f, 0.48f);

            drawLabels();
            drawDots(m);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private void drawMesh(int vb) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vb);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibMesh);
            attr(0, 3, SurfaceMesh.FLOATS, 0);
            attr(1, 3, SurfaceMesh.FLOATS, 3);
            attr(2, 4, SurfaceMesh.FLOATS, 6);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, 0);
            off(0, 1, 2);
        }

        private void ring(SurfaceMesh m, int r, int c, float cr, float cg, float cb) {
            if (r < 0 || c < 0 || r >= m.rows || c >= m.cols) return;
            float[] l = m.ring(r, c, 0.5, cr, cg, cb);
            upload(GLES20.GL_ARRAY_BUFFER, vbHi, fb(l), l.length, GLES20.GL_STREAM_DRAW);
            attr(0, 3, SurfaceMesh.LINE_FLOATS, 0);
            attr(2, 4, SurfaceMesh.LINE_FLOATS, 3);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, l.length / SurfaceMesh.LINE_FLOATS);
            off(0, 2);
        }

        private void drawLabels() {
            LabelSet ls = labels;
            if (ls == null || ls.atlas == null || ls.labels.isEmpty()) return;
            int n = ls.labels.size();
            if (dynBuf.capacity() < n * 24) dynBuf = alloc(n * 24);
            dynBuf.clear();
            int quads = 0;
            for (LabelSet.Label l : ls.labels) {
                Mat4Proj.project(vp, l.x, l.y, l.z, tmp);
                if (tmp[2] <= 0) continue;
                float x0 = (float) tmp[0] + (shift(l) + l.dx) * 2f / vw, x1 = x0 + l.w * 2f / vw;
                float y0 = (float) tmp[1] + (-l.h / 2f + l.dy) * 2f / vh, y1 = y0 + l.h * 2f / vh;
                quad(x0, y0, x1, y1, l);
                quads++;
            }
            if (quads == 0) return;
            upload(GLES20.GL_ARRAY_BUFFER, vbLabel, dynBuf, quads * 24, GLES20.GL_STREAM_DRAW);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progLabel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progLabel, "uTex"), 0);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(progLabel, "uColor"), 0.79f, 0.83f, 0.87f, 1f);
            attr(0, 2, 4, 0);
            attr(1, 2, 4, 2);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, quads * 6);
            off(0, 1);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private float shift(LabelSet.Label l) { return l.align == LabelSet.CENTER ? -l.w / 2f : l.align == LabelSet.RIGHT ? -l.w : 0; }

        private void quad(float x0, float y0, float x1, float y1, LabelSet.Label l) {
            put(x0, y0, l.u0, l.v1); put(x1, y0, l.u1, l.v1); put(x0, y1, l.u0, l.v0);
            put(x1, y0, l.u1, l.v1); put(x1, y1, l.u1, l.v0); put(x0, y1, l.u0, l.v0);
        }

        private void put(float a, float b, float c, float d) { dynBuf.put(a).put(b).put(c).put(d); }

        private void drawDots(SurfaceMesh m) {
            double[][] tr = trail;
            double[] rd = replayDot, ld = liveDot;
            int maxQuads = tr[0].length + 2;
            if (dynBuf.capacity() < maxQuads * 60) dynBuf = alloc(maxQuads * 60);
            dynBuf.clear();
            int quads = 0;
            float pulse = 1f + 0.1f * (float) Math.sin(SystemClock.uptimeMillis() / 160.0);
            int n = Math.min(tr[0].length, Math.min(tr[1].length, tr[2].length));
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(tr[0][i]) || Double.isNaN(tr[1][i])) continue;
                m.worldPos(tr[0][i], tr[1][i], tmp);
                dot(tmp, (6 + 8 * (float) tr[2][i]) * dp, 1f, 0.3f, 0.3f, (float) tr[2][i] * 0.6f);
                quads++;
            }
            if (!Double.isNaN(rd[0]) && !Double.isNaN(rd[1])) {
                m.worldPos(rd[0], rd[1], tmp);
                dot(tmp, 22 * dp * pulse, 1f, 0.3f, 0.3f, 1f);
                quads++;
            }
            if (!Double.isNaN(ld[0]) && !Double.isNaN(ld[1])) {
                m.worldPos(ld[0], ld[1], tmp);
                dot(tmp, 22 * dp * pulse, 0.21f, 0.82f, 0.73f, 1f);
                quads++;
            }
            if (quads == 0) return;
            upload(GLES20.GL_ARRAY_BUFFER, vbDot, dynBuf, quads * 60, GLES20.GL_STREAM_DRAW);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);        // additive glow
            GLES20.glUseProgram(progDot);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progDot, "uVP"), 1, false, vpf, 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progDot, "uPx"), 2f / vw, 2f / vh);
            attr(0, 3, 10, 0);
            attr(1, 2, 10, 3);
            attr(3, 1, 10, 5);
            attr(2, 4, 10, 6);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, quads * 6);
            off(0, 1, 2, 3);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private void dot(double[] p, float size, float r, float g, float b, float a) {
            float y = (float) p[1] + 0.03f;
            float[][] c = {{-1, -1}, {1, -1}, {-1, 1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] k : c) {
                dynBuf.put((float) p[0]).put(y).put((float) p[2]).put(k[0]).put(k[1]).put(size).put(r).put(g).put(b).put(a);
            }
        }
    }

    /** Projection to normalized device coordinates: out = {x, y, w} with w > 0 meaning in front of the camera. */
    private static final class Mat4Proj {
        static void project(double[] m, double x, double y, double z, double[] out) {
            double cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13];
            double cw = m[3] * x + m[7] * y + m[11] * z + m[15];
            out[2] = cw;
            out[0] = cw > 1e-6 ? cx / cw : 0;
            out[1] = cw > 1e-6 ? cy / cw : 0;
        }
    }

    // ---------- shaders (GLSL 1.00 / 3.00 es through the header macros) ----------

    private static final String SURF_V =
            "uniform mat4 uVP;\nATTR vec3 aPos;\nATTR vec3 aA;\nATTR vec4 aB;\nVARY_OUT vec3 vN;\nVARY_OUT vec4 vC;\nVARY_OUT vec3 vP;\n"
                    + "void main(){ vN=aA; vC=aB; vP=aPos; gl_Position=uVP*vec4(aPos,1.0); }\n";
    private static final String SURF_F =
            "uniform vec3 uEye;\nVARY_IN vec3 vN;\nVARY_IN vec4 vC;\nVARY_IN vec3 vP;\n"
                    + "void main(){ vec3 n=normalize(vN); vec3 v=normalize(uEye-vP); if(dot(n,v)<0.0) n=-n;\n"
                    + " vec3 l=normalize(vec3(-0.4,0.9,0.5));\n"
                    + " float d=0.28+0.5*max(dot(n,l),0.0)+0.3*max(dot(n,v),0.0);\n"
                    + " float s=pow(max(dot(reflect(-l,n),v),0.0),24.0)*0.12;\n"
                    + " FRAG=vec4(vC.rgb*d+vec3(s),vC.a); }\n";
    private static final String LINE_V =
            "uniform mat4 uVP;\nATTR vec3 aPos;\nATTR vec4 aB;\nVARY_OUT vec4 vC;\n"
                    + "void main(){ vC=aB; gl_Position=uVP*vec4(aPos,1.0); }\n";
    private static final String LINE_F = "VARY_IN vec4 vC;\nvoid main(){ FRAG=vC; }\n";
    private static final String LABEL_V =
            "ATTR vec2 aPos;\nATTR vec2 aA;\nVARY_OUT vec2 vUV;\nvoid main(){ vUV=aA; gl_Position=vec4(aPos,0.0,1.0); }\n";
    private static final String LABEL_F =
            "uniform sampler2D uTex;\nuniform vec4 uColor;\nVARY_IN vec2 vUV;\nvoid main(){ FRAG=TEX2D(uTex,vUV)*uColor; }\n";
    private static final String DOT_V =
            "uniform mat4 uVP;\nuniform vec2 uPx;\nATTR vec3 aPos;\nATTR vec2 aA;\nATTR float aC;\nATTR vec4 aB;\n"
                    + "VARY_OUT vec2 vUV;\nVARY_OUT vec4 vC;\n"
                    + "void main(){ vec4 p=uVP*vec4(aPos,1.0); p.xy+=aA*aC*uPx*p.w; gl_Position=p; vUV=aA; vC=aB; }\n";
    private static final String DOT_F =
            "VARY_IN vec2 vUV;\nVARY_IN vec4 vC;\n"
                    + "void main(){ float d=length(vUV); float glow=exp(-d*d*3.2)*(1.0-smoothstep(0.8,1.0,d)); float core=1.0-smoothstep(0.12,0.3,d);\n"
                    + " FRAG=vec4(vC.rgb*(glow+core), vC.a); }\n";
}

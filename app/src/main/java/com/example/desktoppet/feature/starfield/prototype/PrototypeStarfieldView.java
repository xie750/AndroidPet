package com.example.desktoppet.feature.starfield.prototype;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Isolated OpenGL ES prototype for evaluating a real 3D desktop star field. */
public final class PrototypeStarfieldView extends GLSurfaceView {
    private final SpaceRenderer spaceRenderer;
    private final float density;
    private float lastX;
    private float lastY;
    private int activeNode = -1;

    public PrototypeStarfieldView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        boolean reduceMotion = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setPreserveEGLContextOnPause(true);
        spaceRenderer = new SpaceRenderer(reduceMotion);
        setRenderer(spaceRenderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setClickable(true);
        setContentDescription("三维深空原型。拖动能量节点，或拖动空白区域观察空间视差");
    }

    public void setSensorParallax(float x, float y) {
        spaceRenderer.setSensorParallax(x, y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                activeNode = spaceRenderer.pickNode(x, y, density * 34f);
                queueEvent(() -> spaceRenderer.setSelectedNode(activeNode));
                if (activeNode >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;
                int node = activeNode;
                if (node >= 0) {
                    queueEvent(() -> spaceRenderer.moveNode(node, dx, dy));
                } else {
                    queueEvent(() -> spaceRenderer.orbitCamera(dx, dy));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeNode = -1;
                queueEvent(() -> spaceRenderer.setSelectedNode(-1));
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static final class SpaceRenderer implements Renderer {
        private static final int STAR_COUNT = 640;
        private static final int STAR_STRIDE_FLOATS = 7;
        private static final int NODE_COUNT = 4;
        private static final String STAR_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uView;\n" +
                "uniform mat4 uProjection;\n" +
                "uniform float uTime;\n" +
                "in vec3 aPosition;\n" +
                "in float aSize;\n" +
                "in float aPhase;\n" +
                "in float aBand;\n" +
                "in float aTone;\n" +
                "out float vAlpha;\n" +
                "out float vTone;\n" +
                "void main(){\n" +
                "  float speed = mix(0.20, 1.08, aBand);\n" +
                "  float depth = mod((-aPosition.z - 2.5) - uTime * speed, 43.0) + 2.5;\n" +
                "  vec3 p = vec3(aPosition.xy, -depth);\n" +
                "  p.x += sin(uTime * 0.08 + aPhase) * (0.05 + aBand * 0.12);\n" +
                "  p.y += cos(uTime * 0.06 + aPhase) * (0.04 + aBand * 0.09);\n" +
                "  vec4 viewPosition = uView * vec4(p, 1.0);\n" +
                "  gl_Position = uProjection * viewPosition;\n" +
                "  gl_PointSize = clamp(aSize * 14.0 / max(1.0, -viewPosition.z), 1.0, 7.5);\n" +
                "  vAlpha = clamp(0.22 + aBand * 0.56 + 7.0 / max(8.0, -viewPosition.z), 0.18, 0.88);\n" +
                "  vTone = aTone;\n" +
                "}\n";
        private static final String STAR_FRAGMENT =
                "#version 300 es\n" +
                "precision mediump float;\n" +
                "in float vAlpha;\n" +
                "in float vTone;\n" +
                "out vec4 outColor;\n" +
                "void main(){\n" +
                "  vec2 p = gl_PointCoord - vec2(0.5);\n" +
                "  float r = length(p);\n" +
                "  float core = 1.0 - smoothstep(0.04, 0.49, r);\n" +
                "  float halo = (1.0 - smoothstep(0.08, 0.50, r)) * 0.38;\n" +
                "  vec3 cold = vec3(0.46, 0.88, 1.0);\n" +
                "  vec3 whiteBlue = vec3(0.84, 0.96, 1.0);\n" +
                "  vec3 color = mix(cold, whiteBlue, vTone);\n" +
                "  float alpha = max(core, halo) * vAlpha;\n" +
                "  if(alpha < 0.015) discard;\n" +
                "  outColor = vec4(color, alpha);\n" +
                "}\n";
        private static final String NODE_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uMvp;\n" +
                "in vec3 aPosition;\n" +
                "in float aSelected;\n" +
                "out float vSelected;\n" +
                "void main(){\n" +
                "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n" +
                "  gl_Position = clip;\n" +
                "  gl_PointSize = clamp(560.0 / max(6.0, clip.w), 36.0, 76.0) * (1.0 + aSelected * 0.12);\n" +
                "  vSelected = aSelected;\n" +
                "}\n";
        private static final String NODE_FRAGMENT =
                "#version 300 es\n" +
                "precision highp float;\n" +
                "uniform float uTime;\n" +
                "in float vSelected;\n" +
                "out vec4 outColor;\n" +
                "void main(){\n" +
                "  vec2 p = gl_PointCoord * 2.0 - 1.0;\n" +
                "  float r = length(p);\n" +
                "  float angle = atan(p.y, p.x);\n" +
                "  float core = 1.0 - smoothstep(0.03, 0.20, r);\n" +
                "  float glow = (1.0 - smoothstep(0.0, 0.72, r)) * 0.24;\n" +
                "  float ring = (1.0 - smoothstep(0.018, 0.050, abs(r - 0.60)));\n" +
                "  float phase = fract((angle + 3.14159) / 6.28318 + uTime * 0.035);\n" +
                "  float segmented = ring * step(0.13, phase) * step(phase, 0.82);\n" +
                "  float crosshair = (1.0 - smoothstep(0.012, 0.035, min(abs(p.x), abs(p.y))))\n" +
                "      * step(0.72, r) * step(r, 0.92);\n" +
                "  float shell = max(segmented * (0.58 + vSelected * 0.42), crosshair * 0.72);\n" +
                "  float alpha = max(max(core, glow), shell);\n" +
                "  if(r > 0.96 || alpha < 0.015) discard;\n" +
                "  vec3 color = mix(vec3(0.20, 0.74, 0.92), vec3(0.74, 0.98, 1.0), core + vSelected * 0.30);\n" +
                "  outColor = vec4(color, alpha);\n" +
                "}\n";
        private static final String LINE_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uMvp;\n" +
                "in vec3 aPosition;\n" +
                "void main(){ gl_Position = uMvp * vec4(aPosition, 1.0); }\n";
        private static final String LINE_FRAGMENT =
                "#version 300 es\n" +
                "precision mediump float;\n" +
                "out vec4 outColor;\n" +
                "void main(){ outColor = vec4(0.28, 0.84, 0.96, 0.26); }\n";

        private final boolean reduceMotion;
        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] mvp = new float[16];
        private final float[] projectionInput = new float[4];
        private final float[] projectionOutput = new float[4];
        private final float[] projectedNodes = new float[NODE_COUNT * 2];
        private final float[] nodePositions = {
                -3.4f, 4.0f, -10.0f,
                 3.1f, 1.7f, -13.5f,
                -2.0f, -3.6f, -17.0f,
                 3.0f, -6.4f, -22.0f
        };
        private FloatBuffer starData;
        private FloatBuffer nodeData;
        private FloatBuffer lineData;
        private int starProgram;
        private int nodeProgram;
        private int lineProgram;
        private int starBuffer;
        private int nodeBuffer;
        private int lineBuffer;
        private int width;
        private int height;
        private long startedAt;
        private float touchYaw;
        private float touchPitch;
        private volatile float sensorYaw;
        private volatile float sensorPitch;
        private int selectedNode = -1;

        SpaceRenderer(boolean reduceMotion) {
            this.reduceMotion = reduceMotion;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            // The translucent GL buffer itself supplies the reliable desktop scrim. This avoids
            // OEM-specific window blur/dim behavior while keeping the launcher visible below it.
            GLES30.glClearColor(0f, .012f, .025f, .43f);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            starProgram = createProgram(STAR_VERTEX, STAR_FRAGMENT);
            nodeProgram = createProgram(NODE_VERTEX, NODE_FRAGMENT);
            lineProgram = createProgram(LINE_VERTEX, LINE_FRAGMENT);
            starData = createStars();
            nodeData = directFloatBuffer(NODE_COUNT * 4);
            lineData = directFloatBuffer(12 * 3);
            int[] buffers = new int[3];
            GLES30.glGenBuffers(3, buffers, 0);
            starBuffer = buffers[0];
            nodeBuffer = buffers[1];
            lineBuffer = buffers[2];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, starData.capacity() * 4,
                    starData, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            startedAt = System.nanoTime();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            this.width = width;
            this.height = height;
            GLES30.glViewport(0, 0, width, height);
            float aspect = width / (float) Math.max(1, height);
            Matrix.perspectiveM(projection, 0, 48f, aspect, .5f, 70f);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            float time = reduceMotion ? 0f : (System.nanoTime() - startedAt) / 1_000_000_000f;
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            float autoYaw = reduceMotion ? 0f : (float) Math.sin(time * .085f) * .025f;
            float autoPitch = reduceMotion ? 0f : (float) Math.cos(time * .072f) * .018f;
            float yaw = touchYaw + sensorYaw + autoYaw;
            float pitch = touchPitch + sensorPitch + autoPitch;
            float eyeX = yaw * 8.5f;
            float eyeY = pitch * 7f;
            Matrix.setLookAtM(view, 0, eyeX, eyeY, 2.6f,
                    eyeX * .15f, eyeY * .12f, -11f, 0f, 1f, 0f);
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0);
            updateProjectedNodes();
            drawStars(time);
            drawLines();
            drawNodes(time);
        }

        private void drawStars(float time) {
            GLES30.glDepthMask(false);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(starProgram);
            setMatrix(starProgram, "uView", view);
            setMatrix(starProgram, "uProjection", projection);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(starProgram, "uTime"), time);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBuffer);
            int stride = STAR_STRIDE_FLOATS * 4;
            enableAttribute(starProgram, "aPosition", 3, stride, 0);
            enableAttribute(starProgram, "aSize", 1, stride, 3 * 4);
            enableAttribute(starProgram, "aPhase", 1, stride, 4 * 4);
            enableAttribute(starProgram, "aBand", 1, stride, 5 * 4);
            enableAttribute(starProgram, "aTone", 1, stride, 6 * 4);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, STAR_COUNT);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES30.glDepthMask(true);
        }

        private void drawLines() {
            lineData.clear();
            for (int first = 0; first < NODE_COUNT; first++) {
                for (int second = first + 1; second < NODE_COUNT; second++) {
                    putNodePosition(lineData, first);
                    putNodePosition(lineData, second);
                }
            }
            lineData.flip();
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glUseProgram(lineProgram);
            setMatrix(lineProgram, "uMvp", mvp);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineData.remaining() * 4,
                    lineData, GLES30.GL_DYNAMIC_DRAW);
            enableAttribute(lineProgram, "aPosition", 3, 3 * 4, 0);
            GLES30.glLineWidth(1f);
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 12);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void drawNodes(float time) {
            nodeData.clear();
            for (int i = 0; i < NODE_COUNT; i++) {
                int offset = i * 3;
                nodeData.put(nodePositions[offset]);
                nodeData.put(nodePositions[offset + 1]);
                nodeData.put(nodePositions[offset + 2]);
                nodeData.put(i == selectedNode ? 1f : 0f);
            }
            nodeData.flip();
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(nodeProgram);
            setMatrix(nodeProgram, "uMvp", mvp);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(nodeProgram, "uTime"), time);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, nodeBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, nodeData.remaining() * 4,
                    nodeData, GLES30.GL_DYNAMIC_DRAW);
            enableAttribute(nodeProgram, "aPosition", 3, 4 * 4, 0);
            enableAttribute(nodeProgram, "aSelected", 1, 4 * 4, 3 * 4);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, NODE_COUNT);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        synchronized int pickNode(float x, float y, float radius) {
            float best = radius;
            int picked = -1;
            for (int i = 0; i < NODE_COUNT; i++) {
                float dx = x - projectedNodes[i * 2];
                float dy = y - projectedNodes[i * 2 + 1];
                float distance = (float) Math.hypot(dx, dy);
                if (distance < best) {
                    best = distance;
                    picked = i;
                }
            }
            return picked;
        }

        void setSelectedNode(int selectedNode) {
            this.selectedNode = selectedNode;
        }

        void moveNode(int index, float dx, float dy) {
            if (index < 0 || index >= NODE_COUNT || width <= 0 || height <= 0) return;
            int offset = index * 3;
            float depthScale = Math.max(.75f, -nodePositions[offset + 2] / 14f);
            nodePositions[offset] = clamp(nodePositions[offset] + dx / width * 14f * depthScale,
                    -8.5f, 8.5f);
            nodePositions[offset + 1] = clamp(nodePositions[offset + 1] - dy / height * 24f * depthScale,
                    -12f, 12f);
        }

        void orbitCamera(float dx, float dy) {
            if (width <= 0 || height <= 0 || reduceMotion) return;
            touchYaw = clamp(touchYaw + dx / width * .42f, -.24f, .24f);
            touchPitch = clamp(touchPitch + dy / height * .34f, -.18f, .18f);
        }

        void setSensorParallax(float x, float y) {
            if (reduceMotion) return;
            sensorYaw = clamp(x, -.075f, .075f);
            sensorPitch = clamp(y, -.060f, .060f);
        }

        private synchronized void updateProjectedNodes() {
            if (width <= 0 || height <= 0) return;
            for (int i = 0; i < NODE_COUNT; i++) {
                int offset = i * 3;
                projectionInput[0] = nodePositions[offset];
                projectionInput[1] = nodePositions[offset + 1];
                projectionInput[2] = nodePositions[offset + 2];
                projectionInput[3] = 1f;
                Matrix.multiplyMV(projectionOutput, 0, mvp, 0, projectionInput, 0);
                float inverseW = projectionOutput[3] == 0f ? 0f : 1f / projectionOutput[3];
                projectedNodes[i * 2] = (projectionOutput[0] * inverseW * .5f + .5f) * width;
                projectedNodes[i * 2 + 1] =
                        (1f - (projectionOutput[1] * inverseW * .5f + .5f)) * height;
            }
        }

        private static FloatBuffer createStars() {
            FloatBuffer data = directFloatBuffer(STAR_COUNT * STAR_STRIDE_FLOATS);
            Random random = new Random(0x3D57A2L);
            for (int i = 0; i < STAR_COUNT; i++) {
                float band = random.nextFloat();
                float depth = 2.5f + random.nextFloat() * 43f;
                float spread = 5.5f + depth * .38f;
                data.put((random.nextFloat() * 2f - 1f) * spread);
                data.put((random.nextFloat() * 2f - 1f) * spread * 1.35f);
                data.put(-depth);
                data.put(2.4f + random.nextFloat() * 5.8f + band * 2f);
                data.put(random.nextFloat() * 6.28318f);
                data.put(band);
                data.put(random.nextFloat());
            }
            data.flip();
            return data;
        }

        private static FloatBuffer directFloatBuffer(int count) {
            return ByteBuffer.allocateDirect(count * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        private void putNodePosition(FloatBuffer buffer, int node) {
            int offset = node * 3;
            buffer.put(nodePositions[offset]);
            buffer.put(nodePositions[offset + 1]);
            buffer.put(nodePositions[offset + 2]);
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, fragment);
            GLES30.glLinkProgram(program);
            int[] linked = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(program);
                GLES30.glDeleteProgram(program);
                throw new IllegalStateException("OpenGL program link failed: " + log);
            }
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader, source);
            GLES30.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES30.glGetShaderInfoLog(shader);
                GLES30.glDeleteShader(shader);
                throw new IllegalStateException("OpenGL shader compile failed: " + log);
            }
            return shader;
        }

        private static void setMatrix(int program, String name, float[] matrix) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, name),
                    1, false, matrix, 0);
        }

        private static void enableAttribute(int program, String name, int size,
                                            int stride, int offset) {
            int location = GLES30.glGetAttribLocation(program, name);
            if (location < 0) return;
            GLES30.glEnableVertexAttribArray(location);
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT,
                    false, stride, offset);
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

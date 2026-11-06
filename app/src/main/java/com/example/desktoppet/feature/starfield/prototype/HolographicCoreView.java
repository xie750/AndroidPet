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
import java.nio.ShortBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Real-time holographic stellar-core prototype rendered with OpenGL ES 3.0. */
public final class HolographicCoreView extends GLSurfaceView {
    private final CoreRenderer coreRenderer;
    private final float density;
    private float lastX;
    private float lastY;
    private float pinchDistance;
    private int selectedNode = -1;

    public HolographicCoreView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        boolean reduceMotion = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setPreserveEGLContextOnPause(true);
        coreRenderer = new CoreRenderer(reduceMotion);
        setRenderer(coreRenderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setClickable(true);
        setContentDescription("全息星核原型。单指旋转，双指缩放，也可以拖动轨道节点");
    }

    public void setSensorParallax(float x, float y) {
        coreRenderer.setSensorParallax(x, y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            selectedNode = coreRenderer.pickNode(lastX, lastY, density * 32f);
            if (selectedNode >= 0) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                int node = selectedNode;
                queueEvent(() -> coreRenderer.setSelectedNode(node));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            pinchDistance = pointerDistance(event);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() >= 2) {
                float distance = pointerDistance(event);
                if (pinchDistance > 0f && distance > 0f) {
                    float factor = distance / pinchDistance;
                    queueEvent(() -> coreRenderer.zoomBy(factor));
                }
                pinchDistance = distance;
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            float dx = x - lastX;
            float dy = y - lastY;
            lastX = x;
            lastY = y;
            int node = selectedNode;
            if (node >= 0) {
                queueEvent(() -> coreRenderer.dragNode(node, dx));
            } else {
                queueEvent(() -> coreRenderer.rotateBy(dx, dy));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            selectedNode = -1;
            pinchDistance = 0f;
            queueEvent(() -> coreRenderer.setSelectedNode(-1));
            performClick();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            pinchDistance = 0f;
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static float pointerDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        return (float) Math.hypot(event.getX(0) - event.getX(1),
                event.getY(0) - event.getY(1));
    }

    private static final class CoreRenderer implements Renderer {
        private static final int DEEP_STAR_COUNT = 360;
        private static final int CORE_PARTICLE_COUNT = 1480;
        private static final int NODE_COUNT = 4;
        private static final int RING_SEGMENTS = 160;
        private static final float SPHERE_RADIUS = 2.75f;

        private static final String DEEP_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uProjection; uniform float uTime;\n" +
                "in vec3 aPosition; in float aSize; in float aPhase;\n" +
                "out float vAlpha; out float vTone;\n" +
                "void main(){\n" +
                " float z=mod((-aPosition.z)-uTime*(0.10+aPhase*0.08),46.0)+3.0;\n" +
                " vec3 p=vec3(aPosition.xy,-z); gl_Position=uProjection*vec4(p,1.0);\n" +
                " gl_PointSize=clamp(aSize*13.0/z,0.8,4.5);\n" +
                " vAlpha=clamp(0.18+9.0/z,0.18,0.72); vTone=aPhase; }\n";
        private static final String POINT_FRAGMENT =
                "#version 300 es\nprecision mediump float;\n" +
                "in float vAlpha; in float vTone; out vec4 outColor;\n" +
                "void main(){ vec2 p=gl_PointCoord-vec2(.5); float r=length(p);\n" +
                " float a=(1.0-smoothstep(.04,.5,r))*vAlpha; if(a<.012)discard;\n" +
                " vec3 c=mix(vec3(.35,.78,.95),vec3(.88,.97,1.0),vTone); outColor=vec4(c,a);}\n";
        private static final String CORE_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uMvp; uniform float uTime;\n" +
                "in vec3 aPosition; in float aSize; in float aSeed;\n" +
                "out float vAlpha; out float vTone;\n" +
                "void main(){ float a=uTime*(.055+aSeed*.055)+aPosition.y*.75;\n" +
                " float c=cos(a),s=sin(a); vec3 p=aPosition; p.xz=mat2(c,-s,s,c)*p.xz;\n" +
                " float b=uTime*.025+aSeed*6.283; float cb=cos(b),sb=sin(b);\n" +
                " p.xy=mat2(cb,-sb,sb,cb)*p.xy; gl_Position=uMvp*vec4(p,1.0);\n" +
                " gl_PointSize=aSize*(1.0+2.8/max(3.0,gl_Position.w));\n" +
                " vAlpha=.36+aSeed*.58; vTone=.45+aSeed*.55; }\n";
        private static final String GLOW_VERTEX =
                "#version 300 es\nuniform mat4 uMvp; uniform float uScale;\n" +
                "void main(){gl_Position=uMvp*vec4(0.0,0.0,0.0,1.0); gl_PointSize=225.0*uScale;}\n";
        private static final String GLOW_FRAGMENT =
                "#version 300 es\nprecision mediump float; out vec4 outColor;\n" +
                "void main(){vec2 p=gl_PointCoord-vec2(.5); float r=length(p)*2.0;\n" +
                "float hot=1.0-smoothstep(0.0,.25,r); float halo=(1.0-smoothstep(.06,1.0,r))*.54;\n" +
                "float a=max(hot,halo); if(a<.01)discard;\n" +
                "outColor=vec4(mix(vec3(.20,.72,1.0),vec3(1.0),hot),a);}\n";
        private static final String SHELL_VERTEX =
                "#version 300 es\n" +
                "uniform mat4 uModelView; uniform mat4 uProjection;\n" +
                "in vec3 aPosition; in vec3 aNormal;\n" +
                "out vec3 vNormal; out vec3 vViewPosition; out vec3 vObjectPosition;\n" +
                "void main(){ vec4 vp=uModelView*vec4(aPosition,1.0);\n" +
                " vViewPosition=vp.xyz; vNormal=normalize(mat3(uModelView)*aNormal);\n" +
                " vObjectPosition=aPosition; gl_Position=uProjection*vp; }\n";
        private static final String SHELL_FRAGMENT =
                "#version 300 es\nprecision highp float;\n" +
                "uniform float uTime; in vec3 vNormal; in vec3 vViewPosition; in vec3 vObjectPosition;\n" +
                "out vec4 outColor;\n" +
                "void main(){ vec3 n=normalize(vNormal); vec3 viewDir=normalize(-vViewPosition);\n" +
                " float fresnel=pow(1.0-abs(dot(n,viewDir)),2.25);\n" +
                " float longitude=atan(vObjectPosition.z,vObjectPosition.x);\n" +
                " float bands=sin(longitude*7.0+vObjectPosition.y*4.4+uTime*.21);\n" +
                " float veins=smoothstep(.82,.99,bands)*(.25+.75*fresnel);\n" +
                " float polar=pow(1.0-abs(n.y),3.0)*.12;\n" +
                " vec3 c=mix(vec3(.08,.30,.48),vec3(.62,.93,1.0),fresnel+veins*.45);\n" +
                " float alpha=.035+fresnel*.42+veins*.13+polar; outColor=vec4(c,alpha); }\n";
        private static final String LINE_VERTEX =
                "#version 300 es\nuniform mat4 uMvp; in vec3 aPosition;\n" +
                "void main(){gl_Position=uMvp*vec4(aPosition,1.0);}\n";
        private static final String LINE_FRAGMENT =
                "#version 300 es\nprecision mediump float; uniform float uAlpha;\n" +
                "out vec4 outColor; void main(){outColor=vec4(.44,.88,1.0,uAlpha);}\n";
        private static final String MESH_VERTEX =
                "#version 300 es\nuniform mat4 uModelView; uniform mat4 uProjection;\n" +
                "in vec3 aPosition; in vec3 aNormal; out vec3 vNormal; out vec3 vView;\n" +
                "void main(){vec4 vp=uModelView*vec4(aPosition,1.0); vView=vp.xyz;\n" +
                "vNormal=normalize(mat3(uModelView)*aNormal); gl_Position=uProjection*vp;}\n";
        private static final String MESH_FRAGMENT =
                "#version 300 es\nprecision mediump float; uniform float uSelected;\n" +
                "in vec3 vNormal; in vec3 vView; out vec4 outColor;\n" +
                "void main(){vec3 n=normalize(vNormal); vec3 l=normalize(vec3(-.3,.7,.5));\n" +
                "float diffuse=max(.12,dot(n,l)); float rim=pow(1.0-abs(dot(n,normalize(-vView))),2.0);\n" +
                "vec3 c=mix(vec3(.12,.53,.72),vec3(.74,.98,1.0),diffuse+rim*.45+uSelected*.18);\n" +
                "outColor=vec4(c,.72+rim*.25);}\n";

        private final boolean reduceMotion;
        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] scene = new float[16];
        private final float[] modelView = new float[16];
        private final float[] mvp = new float[16];
        private final float[] scratchA = new float[16];
        private final float[] scratchB = new float[16];
        private final float[] projectInput = new float[4];
        private final float[] projectOutput = new float[4];
        private final float[] nodeScreen = new float[NODE_COUNT * 2];
        private final float[] nodeAngles = {.55f, 2.75f, 4.35f, 5.45f};
        private final int[] nodeRings = {0, 1, 2, 0};
        private final float[][] ringTilts = {
                {62f, 7f, 13f}, {-28f, 54f, -16f}, {18f, -38f, 70f}
        };
        private final float[] ringRadii = {3.65f, 3.92f, 3.48f};
        private final float[][] ringMatrices = {new float[16], new float[16], new float[16]};

        private int deepProgram;
        private int coreProgram;
        private int glowProgram;
        private int shellProgram;
        private int lineProgram;
        private int meshProgram;
        private int deepBuffer;
        private int coreBuffer;
        private int sphereVertexBuffer;
        private int sphereIndexBuffer;
        private int ringBuffer;
        private int nodeBuffer;
        private int sphereIndexCount;
        private int nodeVertexCount;
        private int width;
        private int height;
        private long startedAt;
        private float rotationX = -7f;
        private float rotationY = 14f;
        private float zoom = 1f;
        private volatile float sensorYaw;
        private volatile float sensorPitch;
        private int selectedNode = -1;

        CoreRenderer(boolean reduceMotion) {
            this.reduceMotion = reduceMotion;
            Matrix.setIdentityM(view, 0);
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES30.glClearColor(0f, .008f, .021f, .56f);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            deepProgram = program(DEEP_VERTEX, POINT_FRAGMENT);
            coreProgram = program(CORE_VERTEX, POINT_FRAGMENT);
            glowProgram = program(GLOW_VERTEX, GLOW_FRAGMENT);
            shellProgram = program(SHELL_VERTEX, SHELL_FRAGMENT);
            lineProgram = program(LINE_VERTEX, LINE_FRAGMENT);
            meshProgram = program(MESH_VERTEX, MESH_FRAGMENT);
            int[] buffers = new int[6];
            GLES30.glGenBuffers(6, buffers, 0);
            deepBuffer = buffers[0];
            coreBuffer = buffers[1];
            sphereVertexBuffer = buffers[2];
            sphereIndexBuffer = buffers[3];
            ringBuffer = buffers[4];
            nodeBuffer = buffers[5];
            uploadStaticBuffer(deepBuffer, createDeepStars());
            uploadStaticBuffer(coreBuffer, createCoreParticles());
            uploadSphere();
            uploadStaticBuffer(ringBuffer, createRing());
            FloatBuffer nodeMesh = createOctahedron();
            nodeVertexCount = nodeMesh.remaining() / 6;
            uploadStaticBuffer(nodeBuffer, nodeMesh);
            startedAt = System.nanoTime();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            this.width = width;
            this.height = height;
            GLES30.glViewport(0, 0, width, height);
            Matrix.perspectiveM(projection, 0, 43f,
                    width / (float) Math.max(1, height), .5f, 80f);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            float time = reduceMotion ? 0f : (System.nanoTime() - startedAt) / 1_000_000_000f;
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            buildScene(time);
            buildRingMatrices(time);
            drawDeepField(time);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            drawRings(.08f);
            drawCoreParticles(time);
            drawCoreGlow();
            drawShellVisual(time);
            writeSphereDepth(time);
            drawRings(.58f);
            drawNodes(time);
            updateNodeScreen(time);
        }

        private void buildScene(float time) {
            Matrix.setIdentityM(scene, 0);
            Matrix.translateM(scene, 0, 0f, .05f, -19f);
            float drift = reduceMotion ? 0f : (float) Math.sin(time * .09f) * 2.2f;
            Matrix.rotateM(scene, 0, rotationX + sensorPitch * 90f, 1f, 0f, 0f);
            Matrix.rotateM(scene, 0, rotationY + sensorYaw * 100f + drift, 0f, 1f, 0f);
            Matrix.scaleM(scene, 0, zoom, zoom, zoom);
            Matrix.multiplyMM(modelView, 0, view, 0, scene, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0);
        }

        private void buildRingMatrices(float time) {
            for (int i = 0; i < ringMatrices.length; i++) {
                Matrix.setIdentityM(ringMatrices[i], 0);
                float speed = reduceMotion ? 0f : time * (i == 1 ? -.7f : .45f + i * .12f);
                Matrix.rotateM(ringMatrices[i], 0, ringTilts[i][0], 1f, 0f, 0f);
                Matrix.rotateM(ringMatrices[i], 0, ringTilts[i][1] + speed, 0f, 1f, 0f);
                Matrix.rotateM(ringMatrices[i], 0, ringTilts[i][2], 0f, 0f, 1f);
                Matrix.scaleM(ringMatrices[i], 0, ringRadii[i], ringRadii[i], ringRadii[i]);
            }
        }

        private void drawDeepField(float time) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthMask(false);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(deepProgram);
            matrix(deepProgram, "uProjection", projection);
            uniform(deepProgram, "uTime", time);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, deepBuffer);
            attribute(deepProgram, "aPosition", 3, 5 * 4, 0);
            attribute(deepProgram, "aSize", 1, 5 * 4, 3 * 4);
            attribute(deepProgram, "aPhase", 1, 5 * 4, 4 * 4);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, DEEP_STAR_COUNT);
            GLES30.glDepthMask(true);
        }

        private void drawCoreParticles(float time) {
            GLES30.glDepthMask(false);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(coreProgram);
            matrix(coreProgram, "uMvp", mvp);
            uniform(coreProgram, "uTime", time);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, coreBuffer);
            attribute(coreProgram, "aPosition", 3, 5 * 4, 0);
            attribute(coreProgram, "aSize", 1, 5 * 4, 3 * 4);
            attribute(coreProgram, "aSeed", 1, 5 * 4, 4 * 4);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, CORE_PARTICLE_COUNT);
            GLES30.glDepthMask(true);
        }

        private void bindShell(float time) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glUseProgram(shellProgram);
            matrix(shellProgram, "uModelView", modelView);
            matrix(shellProgram, "uProjection", projection);
            uniform(shellProgram, "uTime", time);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sphereVertexBuffer);
            attribute(shellProgram, "aPosition", 3, 6 * 4, 0);
            attribute(shellProgram, "aNormal", 3, 6 * 4, 3 * 4);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, sphereIndexBuffer);
        }

        private void drawShellVisual(float time) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthMask(false);
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            bindShell(time);
            GLES30.glCullFace(GLES30.GL_FRONT);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIndexCount,
                    GLES30.GL_UNSIGNED_SHORT, 0);
            GLES30.glCullFace(GLES30.GL_BACK);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIndexCount,
                    GLES30.GL_UNSIGNED_SHORT, 0);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glDepthMask(true);
        }

        private void writeSphereDepth(float time) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glColorMask(false, false, false, false);
            GLES30.glDepthMask(true);
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
            bindShell(time);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereIndexCount,
                    GLES30.GL_UNSIGNED_SHORT, 0);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glColorMask(true, true, true, true);
        }

        private void drawCoreGlow() {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(glowProgram);
            matrix(glowProgram, "uMvp", mvp);
            uniform(glowProgram, "uScale", zoom);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1);
        }

        private void drawRings(float alpha) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(lineProgram);
            uniform(lineProgram, "uAlpha", alpha);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ringBuffer);
            attribute(lineProgram, "aPosition", 3, 3 * 4, 0);
            GLES30.glLineWidth(alpha > .5f ? 2.25f : 1.2f);
            for (float[] ringMatrix : ringMatrices) {
                Matrix.multiplyMM(scratchA, 0, scene, 0, ringMatrix, 0);
                Matrix.multiplyMM(scratchB, 0, view, 0, scratchA, 0);
                Matrix.multiplyMM(scratchA, 0, projection, 0, scratchB, 0);
                matrix(lineProgram, "uMvp", scratchA);
                GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, RING_SEGMENTS + 1);
            }
        }

        private void drawNodes(float time) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            GLES30.glUseProgram(meshProgram);
            matrix(meshProgram, "uProjection", projection);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, nodeBuffer);
            attribute(meshProgram, "aPosition", 3, 6 * 4, 0);
            attribute(meshProgram, "aNormal", 3, 6 * 4, 3 * 4);
            for (int i = 0; i < NODE_COUNT; i++) {
                buildNodeModel(i, time, scratchA);
                Matrix.multiplyMM(scratchB, 0, view, 0, scratchA, 0);
                matrix(meshProgram, "uModelView", scratchB);
                uniform(meshProgram, "uSelected", i == selectedNode ? 1f : 0f);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, nodeVertexCount);
            }
        }

        private void buildNodeModel(int node, float time, float[] output) {
            int ring = nodeRings[node];
            float speed = reduceMotion ? 0f : time * (.10f + ring * .025f);
            float angle = nodeAngles[node] + speed;
            float[] local = projectInput;
            local[0] = (float) Math.cos(angle);
            local[1] = 0f;
            local[2] = (float) Math.sin(angle);
            local[3] = 1f;
            Matrix.multiplyMV(projectOutput, 0, ringMatrices[ring], 0, local, 0);
            Matrix.setIdentityM(output, 0);
            Matrix.translateM(output, 0, projectOutput[0], projectOutput[1], projectOutput[2]);
            Matrix.rotateM(output, 0, time * (18f + node * 4f), 0.4f, 1f, .2f);
            float scale = iSelected(node) ? .18f : .14f;
            Matrix.scaleM(output, 0, scale, scale, scale);
            Matrix.multiplyMM(scratchB, 0, scene, 0, output, 0);
            System.arraycopy(scratchB, 0, output, 0, 16);
        }

        private boolean iSelected(int node) {
            return node == selectedNode;
        }

        private synchronized void updateNodeScreen(float time) {
            if (width <= 0 || height <= 0) return;
            for (int i = 0; i < NODE_COUNT; i++) {
                buildNodeModel(i, time, scratchA);
                projectInput[0] = projectInput[1] = projectInput[2] = 0f;
                projectInput[3] = 1f;
                Matrix.multiplyMM(scratchB, 0, projection, 0, view, 0);
                Matrix.multiplyMM(scratchA, 0, scratchB, 0, scratchA, 0);
                Matrix.multiplyMV(projectOutput, 0, scratchA, 0, projectInput, 0);
                float inverseW = projectOutput[3] == 0f ? 0f : 1f / projectOutput[3];
                nodeScreen[i * 2] = (projectOutput[0] * inverseW * .5f + .5f) * width;
                nodeScreen[i * 2 + 1] =
                        (1f - (projectOutput[1] * inverseW * .5f + .5f)) * height;
            }
        }

        synchronized int pickNode(float x, float y, float radius) {
            int bestNode = -1;
            float best = radius;
            for (int i = 0; i < NODE_COUNT; i++) {
                float distance = (float) Math.hypot(x - nodeScreen[i * 2],
                        y - nodeScreen[i * 2 + 1]);
                if (distance < best) {
                    best = distance;
                    bestNode = i;
                }
            }
            return bestNode;
        }

        void setSelectedNode(int node) {
            selectedNode = node;
        }

        void dragNode(int node, float dx) {
            if (node < 0 || node >= NODE_COUNT || width <= 0) return;
            nodeAngles[node] += dx / width * 4.6f;
        }

        void rotateBy(float dx, float dy) {
            if (reduceMotion || width <= 0 || height <= 0) return;
            rotationY += dx / width * 150f;
            rotationX = clamp(rotationX + dy / height * 120f, -58f, 58f);
        }

        void zoomBy(float factor) {
            if (factor <= 0f) return;
            zoom = clamp(zoom * factor, .72f, 1.18f);
        }

        void setSensorParallax(float x, float y) {
            if (reduceMotion) return;
            sensorYaw = clamp(x, -.055f, .055f);
            sensorPitch = clamp(y, -.045f, .045f);
        }

        private void uploadSphere() {
            final int latitudes = 32;
            final int longitudes = 48;
            FloatBuffer vertices = directFloats((latitudes + 1) * (longitudes + 1) * 6);
            for (int lat = 0; lat <= latitudes; lat++) {
                float theta = (float) Math.PI * lat / latitudes;
                float sinTheta = (float) Math.sin(theta);
                float cosTheta = (float) Math.cos(theta);
                for (int lon = 0; lon <= longitudes; lon++) {
                    float phi = (float) (Math.PI * 2d) * lon / longitudes;
                    float nx = sinTheta * (float) Math.cos(phi);
                    float ny = cosTheta;
                    float nz = sinTheta * (float) Math.sin(phi);
                    vertices.put(nx * SPHERE_RADIUS).put(ny * SPHERE_RADIUS)
                            .put(nz * SPHERE_RADIUS).put(nx).put(ny).put(nz);
                }
            }
            vertices.flip();
            sphereIndexCount = latitudes * longitudes * 6;
            ShortBuffer indices = ByteBuffer.allocateDirect(sphereIndexCount * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int lat = 0; lat < latitudes; lat++) {
                for (int lon = 0; lon < longitudes; lon++) {
                    int first = lat * (longitudes + 1) + lon;
                    int second = first + longitudes + 1;
                    indices.put((short) first).put((short) second).put((short) (first + 1));
                    indices.put((short) second).put((short) (second + 1)).put((short) (first + 1));
                }
            }
            indices.flip();
            uploadStaticBuffer(sphereVertexBuffer, vertices);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, sphereIndexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.remaining() * 2,
                    indices, GLES30.GL_STATIC_DRAW);
        }

        private static FloatBuffer createDeepStars() {
            FloatBuffer data = directFloats(DEEP_STAR_COUNT * 5);
            Random random = new Random(0xD33F13L);
            for (int i = 0; i < DEEP_STAR_COUNT; i++) {
                data.put((random.nextFloat() * 2f - 1f) * 18f);
                data.put((random.nextFloat() * 2f - 1f) * 25f);
                data.put(-(3f + random.nextFloat() * 46f));
                data.put(2f + random.nextFloat() * 5.5f);
                data.put(random.nextFloat());
            }
            data.flip();
            return data;
        }

        private static FloatBuffer createCoreParticles() {
            FloatBuffer data = directFloats(CORE_PARTICLE_COUNT * 5);
            Random random = new Random(0xC0AEB1L);
            for (int i = 0; i < CORE_PARTICLE_COUNT; i++) {
                float distribution = random.nextFloat();
                float radialSample = distribution < .72f
                        ? (float) Math.pow(random.nextFloat(), 1.65f)
                        : (float) Math.cbrt(random.nextFloat());
                float radius = SPHERE_RADIUS * .92f * radialSample;
                float theta = random.nextFloat() * 6.28318f;
                float y = (random.nextFloat() * 2f - 1f) * radius;
                float radial = (float) Math.sqrt(Math.max(0f, radius * radius - y * y));
                data.put((float) Math.cos(theta) * radial);
                data.put(y * .54f);
                data.put((float) Math.sin(theta) * radial);
                data.put(2.4f + random.nextFloat() * 7.4f);
                data.put(random.nextFloat());
            }
            data.flip();
            return data;
        }

        private static FloatBuffer createRing() {
            FloatBuffer data = directFloats((RING_SEGMENTS + 1) * 3);
            for (int i = 0; i <= RING_SEGMENTS; i++) {
                float angle = i / (float) RING_SEGMENTS * 6.28318f;
                data.put((float) Math.cos(angle)).put(0f).put((float) Math.sin(angle));
            }
            data.flip();
            return data;
        }

        private static FloatBuffer createOctahedron() {
            float[][] p = {{0, 1, 0}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0},
                    {0, 0, -1}, {0, -1, 0}};
            int[][] faces = {{0,1,2},{0,2,3},{0,3,4},{0,4,1},
                    {5,2,1},{5,3,2},{5,4,3},{5,1,4}};
            FloatBuffer data = directFloats(faces.length * 3 * 6);
            for (int[] face : faces) {
                float[] a = p[face[0]], b = p[face[1]], c = p[face[2]];
                float ux=b[0]-a[0], uy=b[1]-a[1], uz=b[2]-a[2];
                float vx=c[0]-a[0], vy=c[1]-a[1], vz=c[2]-a[2];
                float nx=uy*vz-uz*vy, ny=uz*vx-ux*vz, nz=ux*vy-uy*vx;
                float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                nx/=length; ny/=length; nz/=length;
                for (int index : face) {
                    data.put(p[index][0]).put(p[index][1]).put(p[index][2]);
                    data.put(nx).put(ny).put(nz);
                }
            }
            data.flip();
            return data;
        }

        private static FloatBuffer directFloats(int count) {
            return ByteBuffer.allocateDirect(count * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        private static void uploadStaticBuffer(int buffer, FloatBuffer data) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining() * 4,
                    data, GLES30.GL_STATIC_DRAW);
        }

        private static int program(String vertexSource, String fragmentSource) {
            int vertex = shader(GLES30.GL_VERTEX_SHADER, vertexSource);
            int fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, fragment);
            GLES30.glLinkProgram(program);
            int[] status = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException("GL link failed: " + GLES30.glGetProgramInfoLog(program));
            }
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            return program;
        }

        private static int shader(int type, String source) {
            int shader = GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader, source);
            GLES30.glCompileShader(shader);
            int[] status = new int[1];
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException("GL shader failed: " + GLES30.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private static void matrix(int program, String name, float[] value) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, name),
                    1, false, value, 0);
        }

        private static void uniform(int program, String name, float value) {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value);
        }

        private static void attribute(int program, String name, int count,
                                      int stride, int offset) {
            int location = GLES30.glGetAttribLocation(program, name);
            GLES30.glEnableVertexAttribArray(location);
            GLES30.glVertexAttribPointer(location, count, GLES30.GL_FLOAT,
                    false, stride, offset);
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

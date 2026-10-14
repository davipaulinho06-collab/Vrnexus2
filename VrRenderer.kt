package com.nexusvr.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderizador estereoscópico (olho esquerdo / direito) do NEXUS VR.
 * Cena em coordenadas de mundo: x = leste, y = norte, z = cima.
 */
class VrRenderer : GLSurfaceView.Renderer {

    @Volatile var ipd = 0.064f
    @Volatile var fov = 90f
    @Volatile var useSensor = false

    private val lock = Any()
    private val sensorRot = FloatArray(9) // dispositivo -> mundo (linha por linha)
    private var yawOffset = 0f
    private var recenterRequested = false
    private var dragYaw = 0f
    private var dragPitch = 0f

    private var program = 0
    private var aPos = 0
    private var aCol = 0
    private var uMvp = 0
    private var uSize = 0

    private lateinit var gridBuf: FloatBuffer
    private var gridCount = 0
    private lateinit var shapesBuf: FloatBuffer
    private var shapesCount = 0
    private lateinit var starsBuf: FloatBuffer
    private var starsCount = 0
    private lateinit var reticleBuf: FloatBuffer
    private var reticleCount = 0

    private var width = 1
    private var height = 1

    private val proj = FloatArray(16)
    private val base = FloatArray(16)
    private val eye = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    fun setRotation(r: FloatArray) {
        synchronized(lock) {
            System.arraycopy(r, 0, sensorRot, 0, 9)
        }
    }

    fun recenter() {
        synchronized(lock) { recenterRequested = true }
        dragYaw = 0f
        dragPitch = 0f
    }

    fun drag(dx: Float, dy: Float) {
        dragYaw += dx
        dragPitch = (dragPitch + dy).coerceIn(-80f, 80f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.01f, 0.02f, 0.06f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vs = """
            attribute vec4 aPos;
            attribute vec4 aCol;
            uniform mat4 uMvp;
            uniform float uSize;
            varying vec4 vCol;
            void main() {
                gl_Position = uMvp * aPos;
                gl_PointSize = uSize;
                vCol = aCol;
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec4 vCol;
            void main() {
                gl_FragColor = vCol;
            }
        """.trimIndent()

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aCol = GLES20.glGetAttribLocation(program, "aCol")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uSize = GLES20.glGetUniformLocation(program, "uSize")

        buildScene()
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun makeBuf(a: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a)
        b.position(0)
        return b
    }

    // Adiciona um vértice (x,y,z,r,g,b,a)
    private fun MutableList<Float>.v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        add(x); add(y); add(z); add(r); add(g); add(b); add(1f)
    }

    private fun MutableList<Float>.line(
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float
    ) {
        v(x1, y1, z1, r, g, b)
        v(x2, y2, z2, r, g, b)
    }

    private fun MutableList<Float>.cube(
        cx: Float, cy: Float, cz: Float, s: Float, r: Float, g: Float, b: Float
    ) {
        val h = s / 2f
        val xs = floatArrayOf(cx - h, cx + h)
        val ys = floatArrayOf(cy - h, cy + h)
        val zs = floatArrayOf(cz - h, cz + h)
        for (a in 0..1) for (c in 0..1) {
            line(xs[0], ys[a], zs[c], xs[1], ys[a], zs[c], r, g, b)
            line(xs[a], ys[0], zs[c], xs[a], ys[1], zs[c], r, g, b)
            line(xs[a], ys[c], zs[0], xs[a], ys[c], zs[1], r, g, b)
        }
    }

    private fun buildScene() {
        // Chão em grade
        val grid = ArrayList<Float>()
        for (i in -20..20) {
            val strong = i % 5 == 0
            val r = if (strong) 0.35f else 0.08f
            val g = if (strong) 0.45f else 0.18f
            val b = if (strong) 1.0f else 0.55f
            grid.line(i.toFloat(), -20f, 0f, i.toFloat(), 20f, 0f, r, g, b)
            grid.line(-20f, i.toFloat(), 0f, 20f, i.toFloat(), 0f, r, g, b)
        }
        gridBuf = makeBuf(grid.toFloatArray())
        gridCount = grid.size / 7

        // Formas: cubos neon em anel, círculo no chão e portal vertical ao norte
        val sh = ArrayList<Float>()
        for (i in 0 until 8) {
            val ang = (i * 45f) * Math.PI.toFloat() / 180f
            val x = Math.sin(ang.toDouble()).toFloat() * 6f
            val y = Math.cos(ang.toDouble()).toFloat() * 6f
            val z = 0.5f + (i % 3) * 0.6f
            if (i % 2 == 0) sh.cube(x, y, z, 1f, 0.2f, 0.6f, 1f)
            else sh.cube(x, y, z, 1f, 0.65f, 0.3f, 1f)
        }
        val seg = 64
        for (i in 0 until seg) {
            val a1 = (i.toFloat() / seg) * 2f * Math.PI.toFloat()
            val a2 = ((i + 1).toFloat() / seg) * 2f * Math.PI.toFloat()
            sh.line(
                Math.cos(a1.toDouble()).toFloat() * 10f, Math.sin(a1.toDouble()).toFloat() * 10f, 0.02f,
                Math.cos(a2.toDouble()).toFloat() * 10f, Math.sin(a2.toDouble()).toFloat() * 10f, 0.02f,
                0.6f, 0.3f, 1f
            )
            // portal: círculo vertical (plano x-z) a 8 m ao norte
            sh.line(
                Math.cos(a1.toDouble()).toFloat() * 1.6f, 8f, 1.8f + Math.sin(a1.toDouble()).toFloat() * 1.6f,
                Math.cos(a2.toDouble()).toFloat() * 1.6f, 8f, 1.8f + Math.sin(a2.toDouble()).toFloat() * 1.6f,
                0.2f, 0.9f, 1f
            )
        }
        shapesBuf = makeBuf(sh.toFloatArray())
        shapesCount = sh.size / 7

        // Estrelas
        val st = ArrayList<Float>()
        val rnd = Random(42)
        for (i in 0 until 700) {
            var x = rnd.nextGaussian().toFloat()
            var y = rnd.nextGaussian().toFloat()
            var z = Math.abs(rnd.nextGaussian().toFloat()) + 0.05f
            val len = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            x = x / len * 60f; y = y / len * 60f; z = z / len * 60f
            val br = 0.5f + rnd.nextFloat() * 0.5f
            st.v(x, y, z, br * 0.8f, br * 0.9f, br)
        }
        starsBuf = makeBuf(st.toFloatArray())
        starsCount = st.size / 7

        // Retículo no centro da visão (cruz pequena)
        val rt = ArrayList<Float>()
        rt.line(-0.03f, 0f, 0f, 0.03f, 0f, 0f, 1f, 1f, 1f)
        rt.line(0f, -0.03f, 0f, 0f, 0.03f, 0f, 1f, 1f, 1f)
        reticleBuf = makeBuf(rt.toFloatArray())
        reticleCount = rt.size / 7
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    private fun computeBase() {
        Matrix.setIdentityM(base, 0)
        if (useSensor) {
            val r = FloatArray(9)
            var doRecenter = false
            synchronized(lock) {
                System.arraycopy(sensorRot, 0, r, 0, 9)
                if (recenterRequested) {
                    recenterRequested = false
                    doRecenter = true
                }
            }
            if (doRecenter) {
                // direção para onde o aparelho aponta (-z do dispositivo) no mundo
                val fx = -r[2]
                val fy = -r[5]
                yawOffset = Math.toDegrees(Math.atan2(fx.toDouble(), fy.toDouble())).toFloat()
            }
            // base = R^T (mundo -> dispositivo), coluna j recebe a linha j de r
            for (j in 0..2) for (i in 0..2) base[j * 4 + i] = r[j * 3 + i]
            Matrix.rotateM(base, 0, -yawOffset, 0f, 0f, 1f)
        } else {
            Matrix.rotateM(base, 0, dragPitch, 1f, 0f, 0f)
            Matrix.rotateM(base, 0, -90f, 1f, 0f, 0f)
            Matrix.rotateM(base, 0, dragYaw, 0f, 0f, 1f)
        }
        // altura dos olhos
        Matrix.translateM(base, 0, 0f, 0f, -1.6f)
    }

    private fun drawBuf(buf: FloatBuffer, count: Int, mode: Int, m: FloatArray, pointSize: Float) {
        GLES20.glUniformMatrix4fv(uMvp, 1, false, m, 0)
        GLES20.glUniform1f(uSize, pointSize)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glDrawArrays(mode, 0, count)
    }

    private fun drawEye(sign: Float, x: Int, w: Int) {
        GLES20.glViewport(x, 0, w, height)
        GLES20.glScissor(x, 0, w, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val aspect = w.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 150f)

        // desloca a câmera para o olho: esquerdo = +ipd/2 no espaço da câmera
        Matrix.setIdentityM(eye, 0)
        Matrix.translateM(eye, 0, sign * ipd / 2f, 0f, 0f)
        Matrix.multiplyMM(view, 0, eye, 0, base, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        drawBuf(starsBuf, starsCount, GLES20.GL_POINTS, mvp, 3f)
        drawBuf(gridBuf, gridCount, GLES20.GL_LINES, mvp, 1f)
        drawBuf(shapesBuf, shapesCount, GLES20.GL_LINES, mvp, 1f)

        // retículo fixo na cabeça, a 3 m de distância
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, 0f, 0f, -3f)
        Matrix.multiplyMM(view, 0, eye, 0, tmp, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        drawBuf(reticleBuf, reticleCount, GLES20.GL_LINES, mvp, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glScissor(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)
        computeBase()

        val half = width / 2
        drawEye(+1f, 0, half)
        drawEye(-1f, half, width - half)
    }
}

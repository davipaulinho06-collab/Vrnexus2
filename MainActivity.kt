package com.nexusvr.app

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private var hasSensor = false

    private val rot9 = FloatArray(9)
    private val remapped = FloatArray(9)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("nexus_vr", Context.MODE_PRIVATE)
        renderer = VrRenderer()
        renderer.ipd = prefs.getFloat("ipd", 0.064f)
        renderer.fov = prefs.getFloat("fov", 90f)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glView)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasSensor = rotSensor != null
        renderer.useSensor = hasSensor

        if (hasSensor) {
            toast("Sensores OK. Toque no centro: recentrar. Laterais: campo de visão. Volume: distância entre lentes.")
        } else {
            toast("Sem giroscópio: arraste a tela para olhar ao redor (modo demonstração).")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        rotSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        glView.onPause()
        getSharedPreferences("nexus_vr", Context.MODE_PRIVATE).edit()
            .putFloat("ipd", renderer.ipd)
            .putFloat("fov", renderer.fov)
            .apply()
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot9, event.values)
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (rotation == Surface.ROTATION_270) {
            SensorManager.remapCoordinateSystem(
                rot9, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
            )
        } else {
            SensorManager.remapCoordinateSystem(
                rot9, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
            )
        }
        renderer.setRotation(remapped)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                renderer.ipd = (renderer.ipd + 0.002f).coerceAtMost(0.09f)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                renderer.ipd = (renderer.ipd - 0.002f).coerceAtLeast(0.03f)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (Math.abs(e.x - downX) > 24f || Math.abs(e.y - downY) > 24f) moved = true
                if (!hasSensor) renderer.drag(dx * 0.2f, dy * 0.2f)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val w = glView.width.toFloat()
                    when {
                        e.x < w / 3f -> renderer.fov = (renderer.fov - 3f).coerceAtLeast(60f)
                        e.x > w * 2f / 3f -> renderer.fov = (renderer.fov + 3f).coerceAtMost(120f)
                        else -> renderer.recenter()
                    }
                }
            }
        }
        return true
    }
}

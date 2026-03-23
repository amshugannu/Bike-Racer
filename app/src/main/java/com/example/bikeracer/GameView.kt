package com.example.bikeracer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, SensorEventListener {
    private val threadLock = Any()
    private val sourceBikeBitmap: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.bike)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val bikeBottomMargin = 100f
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    private var surfaceAvailable = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var currentFps = 0

    var tiltX: Float = 0f
    var bikeSpeedX: Float = 0f
    var isAccelerating = false
    var currentSpeed = 5f
    val maxSpeed = 25f
    val acceleration = 0.5f
    val deceleration = 0.3f
    private val maxTiltSpeed = 20f

    lateinit var bikeBitmap: Bitmap

    private var gameThread: GameThread? = null
    private var screenWidth = 0
    private var bikeX = 0f
    private var bikeY = 0f
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    init {
        bikeBitmap = sourceBikeBitmap
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        if (width > 0 && height > 0) {
            updateBikeLayout(width, height)
        }
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateBikeLayout(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        sensorManager.unregisterListener(this)
        stopGameThread()
    }

    fun pause() {
        isPaused = true
        sensorManager.unregisterListener(this)
        stopGameThread()
    }

    fun resume() {
        isPaused = false
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        startGameThreadIfNeeded()
    }

    fun canRender(): Boolean = surfaceAvailable && !isPaused

    private fun startGameThreadIfNeeded() {
        synchronized(threadLock) {
            if (!surfaceAvailable || isPaused) {
                return
            }

            val existingThread = gameThread
            if (existingThread?.isAlive == true) {
                return
            }

            gameThread = GameThread(holder, this).apply {
                isRunning = true
                start()
            }
        }
    }

    private fun stopGameThread() {
        val thread = synchronized(threadLock) {
            gameThread?.also {
                it.isRunning = false
                it.interrupt()
            }.also {
                gameThread = null
            }
        } ?: return

        var retry = true
        while (retry) {
            try {
                thread.join()
                retry = false
            } catch (_: InterruptedException) {
                thread.interrupt()
            }
        }
    }

    fun update() {
        if (isAccelerating) {
            currentSpeed += acceleration
        } else {
            currentSpeed -= deceleration
        }

        currentSpeed = currentSpeed.coerceIn(5f, maxSpeed)

        bikeSpeedX = (-tiltX * 2f).coerceIn(-maxTiltSpeed, maxTiltSpeed)
        bikeX += bikeSpeedX

        val maxBikeX = (screenWidth - bikeBitmap.width).coerceAtLeast(0).toFloat()
        bikeX = bikeX.coerceIn(0f, maxBikeX)
        bikeSpeedX *= 0.9f
    }

    fun setCurrentFps(fps: Int) {
        currentFps = fps
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }

        val x = event.values[0]
        tiltX = tiltX * 0.8f + x * 0.2f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isAccelerating = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isAccelerating = false
        }
        return true
    }

    private fun updateBikeLayout(screenWidth: Int, screenHeight: Int) {
        this.screenWidth = screenWidth
        val targetBikeWidth = (screenWidth * 0.2f).toInt().coerceAtLeast(1)
        val aspectRatio = sourceBikeBitmap.height.toFloat() / sourceBikeBitmap.width.toFloat()
        val scaledWidth = targetBikeWidth
        val scaledHeight = (scaledWidth * aspectRatio).toInt().coerceAtLeast(1)

        bikeBitmap = Bitmap.createScaledBitmap(sourceBikeBitmap, scaledWidth, scaledHeight, true)

        val centeredX = screenWidth / 2f - bikeBitmap.width / 2f
        val bottomAlignedY = screenHeight - bikeBitmap.height - bikeBottomMargin
        val maxX = (screenWidth - bikeBitmap.width).coerceAtLeast(0).toFloat()
        val maxY = (screenHeight - bikeBitmap.height).coerceAtLeast(0).toFloat()

        bikeX = centeredX.coerceIn(0f, maxX)
        bikeY = bottomAlignedY.coerceIn(0f, maxY)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawColor(Color.BLACK)
        if (::bikeBitmap.isInitialized) {
            canvas.drawBitmap(bikeBitmap, bikeX, bikeY, null)
        }
        canvas.drawText("FPS: $currentFps", 24f, 56f, fpsPaint)
        canvas.drawText("Speed: ${"%.1f".format(currentSpeed)}", 24f, 104f, fpsPaint)
    }
}

package com.example.bikeracer

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val threadLock = Any()

    @Volatile
    private var surfaceAvailable = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var currentFps = 0

    private var gameThread: GameThread? = null
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        stopGameThread()
    }

    fun pause() {
        isPaused = true
        stopGameThread()
    }

    fun resume() {
        isPaused = false
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
    }

    fun setCurrentFps(fps: Int) {
        currentFps = fps
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.drawText("FPS: $currentFps", 24f, 56f, fpsPaint)
    }
}

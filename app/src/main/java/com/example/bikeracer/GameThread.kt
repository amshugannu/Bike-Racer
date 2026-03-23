package com.example.bikeracer

import android.graphics.Canvas
import android.view.SurfaceHolder

class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread() {
    private val targetFPS = 60
    private val targetTime = 1000L / targetFPS

    @Volatile
    var isRunning = false

    override fun run() {
        var frameCount = 0
        var fpsTimer = System.currentTimeMillis()

        while (isRunning) {
            val startTime = System.currentTimeMillis()

            if (!gameView.canRender() || !surfaceHolder.surface.isValid) {
                sleepFrame(targetTime)
                continue
            }

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null && gameView.canRender()) {
                    synchronized(surfaceHolder) {
                        gameView.update()
                        gameView.draw(canvas)
                    }
                    frameCount++
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }

            val currentTime = System.currentTimeMillis()
            if (currentTime - fpsTimer >= 1000L) {
                gameView.setCurrentFps(frameCount)
                frameCount = 0
                fpsTimer = currentTime
            }

            val elapsedTime = currentTime - startTime
            val waitTime = targetTime - elapsedTime
            if (waitTime > 0L) {
                sleepFrame(waitTime)
            }
        }
    }

    private fun sleepFrame(waitTime: Long) {
        try {
            sleep(waitTime)
        } catch (_: InterruptedException) {
            interrupt()
        }
    }
}

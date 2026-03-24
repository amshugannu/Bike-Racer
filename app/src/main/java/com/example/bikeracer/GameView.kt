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
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, SensorEventListener {
    enum class GameState {
        START,
        PLAYING,
        GAME_OVER
    }

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
    var baseSpeed = 5f
    var currentSpeed = 5f
    var maxSpeed = 25f
    val acceleration = 0.5f
    val deceleration = 0.3f
    var roadOffset = 0f
    var bikeTiltAngle = 0f
    var cameraOffsetX = 0f
    var spawnTimer = 0
    var nextSpawnDelay = 80
    var gameState = GameState.START
    var score = 0
    var nextMilestone = 2000
    var showSpeedText = false
    var speedTextAlpha = 0f
    var speedTextY = 0f
    val obstacles = mutableListOf<Obstacle>()
    private val obstacleBitmaps = mutableMapOf<Obstacle, Bitmap>()
    private val obstacleSpawnOffset = 300f
    private val minYGap = 200f
    private val maxTiltSpeed = 30f

    lateinit var bikeBitmap: Bitmap
    lateinit var carBitmap: Bitmap
    lateinit var carBitmap2: Bitmap
    lateinit var carBitmap3: Bitmap
    lateinit var carBitmap4: Bitmap

    private var gameThread: GameThread? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var bikeX = 0f
    private var bikeY = 0f
    private val roadPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 50f
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 40f
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val gameOverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 80f
        textAlign = Paint.Align.CENTER
    }
    private val speedUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 70f
        textAlign = Paint.Align.CENTER
    }
    private val roadPath = Path()
    private val carBitmaps = mutableListOf<Bitmap>()

    init {
        bikeBitmap = sourceBikeBitmap
        carBitmap = BitmapFactory.decodeResource(resources, R.drawable.car)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        carBitmap2 = BitmapFactory.decodeResource(resources, R.drawable.car2)
            ?: carBitmap
        carBitmap3 = BitmapFactory.decodeResource(resources, R.drawable.car3)
            ?: carBitmap
        carBitmap4 = BitmapFactory.decodeResource(resources, R.drawable.car4)
            ?: carBitmap
        carBitmaps.addAll(listOf(carBitmap, carBitmap2, carBitmap3, carBitmap4))
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
        when (gameState) {
            GameState.START -> return
            GameState.GAME_OVER -> return
            GameState.PLAYING -> Unit
        }

        score++
        if (score >= nextMilestone) {
            baseSpeed += 10f
            maxSpeed += 10f
            nextMilestone += 2000
            triggerSpeedText()
        }

        if (showSpeedText) {
            speedTextY -= 2f
            speedTextAlpha -= 5f
            if (speedTextAlpha <= 0f) {
                showSpeedText = false
                speedTextAlpha = 0f
            }
        }

        val targetSpeed = if (isAccelerating) {
            maxSpeed
        } else {
            baseSpeed
        }
        currentSpeed += (targetSpeed - currentSpeed) * 0.18f
        currentSpeed = currentSpeed.coerceIn(baseSpeed, maxSpeed)
        roadOffset += currentSpeed
        if (roadOffset > screenHeight) {
            roadOffset = 0f
        }

        spawnTimer++
        if (spawnTimer >= nextSpawnDelay) {
            spawnObstacle()
            spawnTimer = 0
            scheduleNextSpawn()
        }

        for (obstacle in obstacles) {
            obstacle.y += obstacle.speed + (currentSpeed * 0.6f)
        }
        obstacles.removeAll { it.y > screenHeight }
        obstacleBitmaps.keys.retainAll(obstacles.toSet())

        bikeSpeedX = (-tiltX * 3.2f).coerceIn(-maxTiltSpeed, maxTiltSpeed)
        bikeX += bikeSpeedX

        val maxBikeX = (screenWidth - bikeBitmap.width).coerceAtLeast(0).toFloat()
        bikeX = bikeX.coerceIn(0f, maxBikeX)
        bikeSpeedX *= 0.9f

        val bikeWidth = bikeBitmap.width.toFloat()
        val bikeHeight = bikeBitmap.height.toFloat()
        val bikeRect = RectF(
            bikeX + bikeWidth * 0.2f,
            bikeY + bikeHeight * 0.2f,
            bikeX + bikeWidth * 0.8f,
            bikeY + bikeHeight * 0.9f
        )

        for (obstacle in obstacles) {
            if (RectF.intersects(bikeRect, getObstacleRect(obstacle))) {
                gameState = GameState.GAME_OVER
                return
            }
        }

        bikeTiltAngle = (-tiltX * 3f).coerceIn(-15f, 15f)
        bikeTiltAngle *= 0.9f

        cameraOffsetX = bikeSpeedX * 0.2f
        cameraOffsetX *= 0.9f
    }

    fun setCurrentFps(fps: Int) {
        currentFps = fps
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }

        val x = event.values[0]
        tiltX = tiltX * 0.65f + x * 0.35f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            when (gameState) {
                GameState.START -> {
                    gameState = GameState.PLAYING
                }
                GameState.GAME_OVER -> {
                    resetGame()
                    gameState = GameState.PLAYING
                }
                GameState.PLAYING -> {
                    isAccelerating = true
                }
            }
        }

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isAccelerating = false
        }
        return true
    }

    fun spawnObstacle() {
        val laneWidth = screenWidth / 3
        if (laneWidth <= 0) {
            return
        }

        val width = (laneWidth * 0.9f).toInt()
        val height = width * 2
        val playerLane = ((bikeX + bikeBitmap.width / 2f) / laneWidth)
            .toInt()
            .coerceIn(0, 2)
        val candidateLanes = mutableListOf<Int>()
        val primaryLane = when ((0..99).random()) {
            in 0..54 -> playerLane
            in 55..79 -> (playerLane - 1).coerceAtLeast(0)
            else -> (playerLane + 1).coerceAtMost(2)
        }
        candidateLanes.add(primaryLane)

        val extraSpawnChance = (40 + (score / 250)).coerceAtMost(75)
        if ((0..99).random() < extraSpawnChance) {
            val secondaryOptions = (0..2)
                .filter { it != primaryLane }
                .sortedBy { kotlin.math.abs(it - playerLane) }
            secondaryOptions.firstOrNull()?.let(candidateLanes::add)
        }

        for (lane in candidateLanes.distinct()) {
            val dynamicGap = (minYGap - (score / 40f)).coerceAtLeast(90f)
            val blockedByAdjacentLane = obstacles.any { obstacle ->
                val obstacleLane = (obstacle.x / laneWidth).toInt().coerceIn(0, 2)
                kotlin.math.abs(obstacleLane - lane) == 1 &&
                    kotlin.math.abs(obstacle.y - (-obstacleSpawnOffset)) < dynamicGap
            }
            if (blockedByAdjacentLane) {
                continue
            }

            val x = lane * laneWidth + laneWidth / 4
            val obstacle = Obstacle(
                x.toFloat(),
                -obstacleSpawnOffset,
                width,
                height,
                8f
            )
            obstacles.add(obstacle)
            obstacleBitmaps[obstacle] = carBitmaps.random()
        }
    }

    private fun scheduleNextSpawn() {
        val speedFactor = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        val scoreFactor = (score / 4000f).coerceIn(0f, 1f)
        val intensity = ((speedFactor * 0.6f) + (scoreFactor * 0.4f)).coerceIn(0f, 1f)
        val minDelay = (40 - intensity * 18f).toInt().coerceAtLeast(18)
        val maxDelay = (72 - intensity * 30f).toInt().coerceAtLeast(minDelay + 6)
        nextSpawnDelay = (minDelay..maxDelay).random()
    }

    private fun updateBikeLayout(screenWidth: Int, screenHeight: Int) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
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

    fun resetGame() {
        obstacles.clear()
        score = 0
        baseSpeed = 5f
        currentSpeed = baseSpeed
        maxSpeed = 25f
        nextMilestone = 2000
        showSpeedText = false
        speedTextAlpha = 0f
        speedTextY = 0f
        obstacleBitmaps.clear()
        spawnTimer = 0
        nextSpawnDelay = 80
        roadOffset = 0f
        bikeSpeedX = 0f
        bikeTiltAngle = 0f
        cameraOffsetX = 0f
        tiltX = 0f
        isAccelerating = false
        bikeX = screenWidth / 2f - bikeBitmap.width / 2f
        bikeY = screenHeight - bikeBitmap.height - bikeBottomMargin
        gameState = GameState.START
    }

    fun triggerSpeedText() {
        showSpeedText = true
        speedTextAlpha = 255f
        speedTextY = screenHeight / 2f
    }

    private fun getObstacleDrawRect(obstacle: Obstacle): RectF {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return RectF()
        }

        val obstacleBitmap = obstacleBitmaps[obstacle] ?: carBitmap
        val baseLaneWidth = screenWidth / 3f
        val lane = (obstacle.x / baseLaneWidth).toInt().coerceIn(0, 2)
        val t = ((obstacle.y + obstacleSpawnOffset) /
            (screenHeight.toFloat() + obstacleSpawnOffset)).coerceIn(0f, 1f)
        val depth = 0.3f * t + 0.7f * t * t
        val transformedY = depth * screenHeight
        val roadWidth = screenWidth * (0.8f + 0.2f * depth)
        val left = (screenWidth - roadWidth) / 2f
        val laneWidth = roadWidth / 3f
        val centerX = left + laneWidth * lane + laneWidth / 2f
        val scale = 0.6f + depth * 0.7f
        val carWidth = laneWidth * 0.62f * scale
        val carHeight =
            carWidth * (obstacleBitmap.height.toFloat() / obstacleBitmap.width.toFloat())

        return RectF(
            centerX - carWidth / 2f,
            transformedY,
            centerX + carWidth / 2f,
            transformedY + carHeight
        )
    }

    private fun getObstacleRect(obstacle: Obstacle): RectF {
        val drawRect = getObstacleDrawRect(obstacle)
        val carWidth = drawRect.width()
        val carHeight = drawRect.height()
        val centerX = drawRect.centerX()

        return RectF(
            centerX - carWidth * 0.35f,
            drawRect.top + carHeight * 0.2f,
            centerX + carWidth * 0.35f,
            drawRect.top + carHeight * 0.9f
        )
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawColor(Color.BLACK)

        canvas.save()
        canvas.translate(cameraOffsetX, 0f)

        val roadTopWidth = screenWidth * 0.7f
        val roadBottomWidth = screenWidth.toFloat()
        val topLeft = (screenWidth - roadTopWidth) / 2f
        val bottomLeft = 0f

        roadPath.reset()
        roadPath.moveTo(topLeft, 0f)
        roadPath.lineTo(topLeft + roadTopWidth, 0f)
        roadPath.lineTo(bottomLeft + roadBottomWidth, screenHeight.toFloat())
        roadPath.lineTo(bottomLeft, screenHeight.toFloat())
        roadPath.close()
        canvas.drawPath(roadPath, roadPaint)

        val step = 60
        for (i in 0..screenHeight step step) {
            val baseY = (i + roadOffset) % screenHeight
            val t = baseY / screenHeight.toFloat()
            val depth = t * t
            val y = depth * screenHeight
            val roadWidth = screenWidth * (0.85f + 0.15f * depth)
            val left = (screenWidth - roadWidth) / 2f
            val laneWidth = roadWidth / 3f
            val lane1X = left + laneWidth
            val lane2X = left + laneWidth * 2f
            val lineWidth = 4f + (depth * 8f)
            val segmentHeight = 40f + (depth * 80f)
            val segmentBottom = (y + segmentHeight).coerceAtMost(screenHeight.toFloat())

            canvas.drawRect(
                lane1X - lineWidth,
                y,
                lane1X + lineWidth,
                segmentBottom,
                lanePaint
            )
            canvas.drawRect(
                lane2X - lineWidth,
                y,
                lane2X + lineWidth,
                segmentBottom,
                lanePaint
            )
        }

        canvas.restore()

        for (obstacle in obstacles) {
            canvas.drawBitmap(
                obstacleBitmaps[obstacle] ?: carBitmap,
                null,
                getObstacleDrawRect(obstacle),
                null
            )
        }

        if (::bikeBitmap.isInitialized) {
            canvas.save()
            canvas.rotate(
                bikeTiltAngle,
                bikeX + bikeBitmap.width / 2f,
                bikeY + bikeBitmap.height / 2f
            )
            canvas.drawBitmap(bikeBitmap, bikeX, bikeY, null)
            canvas.restore()
        }

        if (gameState == GameState.PLAYING) {
            canvas.drawText(
                "Score: $score",
                40f,
                80f,
                scorePaint
            )
            canvas.drawText(
                "Speed: ${currentSpeed.toInt()}",
                screenWidth - 250f,
                80f,
                speedPaint
            )
        }

        if (showSpeedText) {
            speedUpPaint.alpha = speedTextAlpha.toInt().coerceIn(0, 255)
            canvas.drawText(
                "SPEED UP!",
                screenWidth / 2f,
                speedTextY,
                speedUpPaint
            )
        }

        if (gameState == GameState.START) {
            canvas.drawText(
                "TILT RACER",
                screenWidth / 2f,
                screenHeight / 2f - 100f,
                gameOverPaint
            )

            gameOverPaint.textSize = 50f
            canvas.drawText(
                "Tap to Start",
                screenWidth / 2f,
                screenHeight / 2f,
                gameOverPaint
            )
            gameOverPaint.textSize = 80f
        }

        if (gameState == GameState.GAME_OVER) {
            canvas.drawColor(Color.argb(150, 0, 0, 0))
            canvas.drawText(
                "GAME OVER",
                screenWidth / 2f,
                screenHeight / 2f,
                gameOverPaint
            )

            gameOverPaint.textSize = 50f
            canvas.drawText(
                "Score: $score",
                screenWidth / 2f,
                screenHeight / 2f + 80f,
                gameOverPaint
            )
            canvas.drawText(
                "Tap to Restart",
                screenWidth / 2f,
                screenHeight / 2f + 150f,
                gameOverPaint
            )
            gameOverPaint.textSize = 80f
        }
    }
}

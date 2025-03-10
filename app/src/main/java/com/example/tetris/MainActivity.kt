package com.example.tetris

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.WindowManager
import android.view.View
import com.example.tetris.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, HandTracker.HandGestureCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handTracker: HandTracker
    private lateinit var tetrisGrid: TetrisGrid
    private var lastHandResult: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult? = null
    private val TAG = "MainActivity"
    
    // Constants for SharedPreferences
    companion object {
        private const val PREFS_NAME = "TetrisPrefs"
        private const val HIGH_SCORE_KEY = "high_score"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    // Touch control variables
    private var lastTouchY: Float = 0f
    private val SWIPE_THRESHOLD = 50f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the action bar
        supportActionBar?.hide()

        // Make the app fullscreen and keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Hide system UI for true immersive mode
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate() called")

        // Initialize hand tracker
        handTracker = HandTracker(this, this)

        // Set up reset button click listener
        binding.resetButton.setOnClickListener {
            resetGame()
        }

        // Set up start game button click listener
        binding.startGameButton.setOnClickListener {
            startGame()
        }

        // Set up in-game restart button click listener
        binding.inGameRestartButton.setOnClickListener {
            showRestartConfirmation()
        }

        // Set up the overlay for hand tracking visualization
        binding.overlay.holder.addCallback(this)
        binding.overlay.setZOrderMediaOverlay(true) // This ensures proper z-ordering
        binding.overlay.holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)

        // Request camera permissions
        if (allPermissionsGranted()) {
            Log.d(TAG, "All permissions granted, starting camera")
            startCamera()
        } else {
            Log.d(TAG, "Requesting camera permissions")
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::tetrisGrid.isInitialized) {
            val gameState = tetrisGrid.saveState()
            outState.putParcelable("game_state", GameStateParcelable(gameState))
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (::tetrisGrid.isInitialized) {
            val gameStateParcelable = savedInstanceState.getParcelable<GameStateParcelable>("game_state")
            gameStateParcelable?.let {
                tetrisGrid.restoreState(it.toGameState())
                updateGameState()
            }
        }
    }

    // Parcelable wrapper for game state
    class GameStateParcelable(private val gameState: TetrisGame.GameState) : android.os.Parcelable {
        constructor(parcel: android.os.Parcel) : this(
            TetrisGame.GameState(
                grid = Array(TetrisGame.GRID_HEIGHT) { IntArray(TetrisGame.GRID_WIDTH) }.apply {
                    for (i in 0 until TetrisGame.GRID_HEIGHT) {
                        for (j in 0 until TetrisGame.GRID_WIDTH) {
                            this[i][j] = parcel.readInt()
                        }
                    }
                },
                score = parcel.readInt(),
                highScore = parcel.readInt(),
                isGameOver = parcel.readInt() == 1,
                isPaused = parcel.readInt() == 1,
                currentPieceState = if (parcel.readInt() == 1) {
                    TetrisGame.CurrentPieceState(
                        type = TetriminoType.values()[parcel.readInt()],
                        rotation = parcel.readInt(),
                        x = parcel.readInt(),
                        y = parcel.readInt()
                    )
                } else null,
                nextPieceType = TetriminoType.values()[parcel.readInt()]
            )
        )

        override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
            // Write grid
            for (row in gameState.grid) {
                for (cell in row) {
                    parcel.writeInt(cell)
                }
            }
            
            // Write other state
            parcel.writeInt(gameState.score)
            parcel.writeInt(gameState.highScore)
            parcel.writeInt(if (gameState.isGameOver) 1 else 0)
            parcel.writeInt(if (gameState.isPaused) 1 else 0)
            
            // Write current piece state
            parcel.writeInt(if (gameState.currentPieceState != null) 1 else 0)
            gameState.currentPieceState?.let {
                parcel.writeInt(it.type.ordinal)
                parcel.writeInt(it.rotation)
                parcel.writeInt(it.x)
                parcel.writeInt(it.y)
            }
            
            // Write next piece type
            parcel.writeInt(gameState.nextPieceType.ordinal)
        }

        override fun describeContents(): Int = 0

        fun toGameState(): TetrisGame.GameState = gameState

        companion object CREATOR : android.os.Parcelable.Creator<GameStateParcelable> {
            override fun createFromParcel(parcel: android.os.Parcel): GameStateParcelable {
                return GameStateParcelable(parcel)
            }

            override fun newArray(size: Int): Array<GameStateParcelable?> {
                return arrayOfNulls(size)
            }
        }
    }

    // Also handle immersive mode for when the user swipes from edge
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun startCamera() {
        Log.d(TAG, "Starting camera")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            Log.d(TAG, "Camera provider ready")

            try {
                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                        Log.d(TAG, "Preview surface provider set")
                    }

                // Select front camera
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                // Image analysis for hand tracking
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ImageAnalyzer())
                        Log.d(TAG, "Image analyzer set")
                    }

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                Log.d(TAG, "Unbound previous use cases")

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                Log.d(TAG, "Use cases bound to lifecycle")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                Log.d(TAG, "Camera permissions granted")
                startCamera()
            } else {
                Log.e(TAG, "Camera permissions denied")
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        cameraExecutor.shutdown()
        handTracker.close()
        // Pause the game to stop the handler callbacks
        if (::tetrisGrid.isInitialized) {
            tetrisGrid.pause()
        }
    }

    // SurfaceHolder.Callback methods for hand tracking overlay
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated() called")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged() called: width=$width, height=$height")
        // Initialize tetrisGrid when surface dimensions are known
        if (!::tetrisGrid.isInitialized) {
            tetrisGrid = TetrisGrid(width, height)
            // Load the saved high score
            val savedHighScore = loadHighScore()
            tetrisGrid.game.setHighScore(savedHighScore)
            Log.d(TAG, "Initialized game with saved high score: $savedHighScore")
            // Show instructions overlay for new game
            binding.instructionsOverlay.visibility = View.VISIBLE
        }
        // Draw any existing hand tracking results
        drawOverlay()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed() called")
    }

    // Update the score and check for game over
    private fun updateGameState() {
        if (::tetrisGrid.isInitialized) {
            // Update score display
            val oldText = binding.fingerCountText.text.toString()
            val score = tetrisGrid.getScore()
            val newText = "Score: $score"
            Log.d(TAG, "updateGameState() called. Old text: $oldText, New text: $newText")
            
            // Only update if the score has changed
            if (oldText != newText) {
                binding.fingerCountText.text = newText
                Log.d(TAG, "Score display updated to: $score")
            }
            
            // Check for game over
            if (tetrisGrid.game.isGameOver) {
                showGameOver(score)
            }
        }
    }
    
    // Reset the game and show instructions
    private fun resetGame() {
        if (::tetrisGrid.isInitialized) {
            tetrisGrid.reset()
            // Load the saved high score when resetting
            val savedHighScore = loadHighScore()
            tetrisGrid.game.setHighScore(savedHighScore)
            binding.fingerCountText.text = "Score: 0"
            binding.gameOverOverlay.visibility = View.GONE
            binding.instructionsOverlay.visibility = View.VISIBLE
            binding.inGameRestartButton.visibility = View.GONE
            Log.d(TAG, "Game reset, score display reset to 0, high score loaded: $savedHighScore")
        }
    }

    // Load high score from SharedPreferences
    private fun loadHighScore(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val highScore = prefs.getInt(HIGH_SCORE_KEY, 0)
        Log.d(TAG, "Loaded high score from preferences: $highScore")
        return highScore
    }

    // Save high score to SharedPreferences
    private fun saveHighScore(score: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putInt(HIGH_SCORE_KEY, score).apply()
        Log.d(TAG, "High score saved to preferences: $score")
    }

    // Show game over screen with final score
    private fun showGameOver(finalScore: Int) {
        Log.d(TAG, "showGameOver() called with score: $finalScore")
        // Save high score when game is over
        saveHighScore(finalScore)
        runOnUiThread {
            binding.gameOverOverlay.apply {
                bringToFront() // Ensure overlay is on top
                visibility = View.VISIBLE
            }
            binding.finalScoreText.text = "Final Score: $finalScore\nHigh Score: ${tetrisGrid.game.highScore}"
            
            // Make sure the reset button is clickable and on top
            binding.resetButton.apply {
                bringToFront()
                isClickable = true
            }
        }
    }
    
    // Show confirmation dialog before restarting
    private fun showRestartConfirmation() {
        // Pause the game while showing dialog
        if (::tetrisGrid.isInitialized) {
            tetrisGrid.game.isPaused = true
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Restart Game")
            .setMessage("Are you sure you want to restart? Current progress will be lost.")
            .setPositiveButton("Restart") { _, _ ->
                resetGame()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // Resume the game
                if (::tetrisGrid.isInitialized) {
                    tetrisGrid.game.isPaused = false
                }
                dialog.dismiss()
            }
            .setOnCancelListener {
                // Resume the game if dialog is dismissed
                if (::tetrisGrid.isInitialized) {
                    tetrisGrid.game.isPaused = false
                }
            }
            .show()
    }

    // Start the game and hide instructions
    private fun startGame() {
        binding.instructionsOverlay.visibility = View.GONE
        binding.inGameRestartButton.visibility = View.VISIBLE
        if (::tetrisGrid.isInitialized) {
            tetrisGrid.start()
        }
    }

    private fun drawOverlay() {
        val holder = binding.overlay.holder
        val canvas: Canvas? = try {
            holder.lockCanvas()
        } catch (e: Exception) {
            Log.e(TAG, "Error locking canvas: ${e.message}")
            null
        }
        
        if (canvas == null) {
            Log.e(TAG, "Failed to lock canvas for drawing overlay")
            return
        }

        try {
            // Clear previous drawing
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            
            // Draw hand landmarks first (underneath)
            if (lastHandResult != null) {
                Log.d(TAG, "Drawing hand landmarks")
                handTracker.drawHandLandmarks(
                    canvas,
                    lastHandResult,
                    binding.previewView.width,
                    binding.previewView.height
                )
            }
            
            // Draw Tetris grid on top
            tetrisGrid.draw(canvas)
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing overlay: ${e.message}")
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Error posting canvas: ${e.message}")
            }
        }
        
        // Update game state after drawing
        updateGameState()
    }

    // HandTracker.HandGestureCallback implementation
    override fun onFingerCountUpdated(fingerCount: Int) {
        // We no longer need to update the UI for finger count
        runOnUiThread {
            drawOverlay()
        }
    }

    override fun onPointerFingerMoved(x: Float, y: Float, isPointing: Boolean) {
        Log.d(TAG, "Pointer finger moved: x=$x, y=$y, isPointing=$isPointing")
        runOnUiThread {
            if (::tetrisGrid.isInitialized) {
                tetrisGrid.handleFingerMovement(x, isPointing)
                drawOverlay() // Redraw with updated piece position
            }
        }
    }
    
    override fun onFistGesture() {
        Log.d(TAG, "Fist gesture detected - hard dropping piece")
        runOnUiThread {
            if (::tetrisGrid.isInitialized) {
                tetrisGrid.handleHardDrop()
                drawOverlay() // Redraw with dropped piece
            }
        }
    }

    override fun onTwoFingerGesture() {
        Log.d(TAG, "Two finger gesture detected - hard dropping piece")
        runOnUiThread {
            if (::tetrisGrid.isInitialized) {
                tetrisGrid.game.handleTwoFingerGesture()
                // Force a UI update immediately after the gesture
                updateGameState()
                drawOverlay() // Redraw with dropped piece
            }
        }
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val result = handTracker.detectHands(imageProxy)
                if (result != null) {
                    lastHandResult = result
                    drawOverlay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
                e.printStackTrace()
            } finally {
                imageProxy.close()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Save high score when app is paused
        if (::tetrisGrid.isInitialized) {
            saveHighScore(tetrisGrid.game.highScore)
        }
    }

    override fun onResume() {
        super.onResume()
        // If we have a saved state, the game will be restored via onRestoreInstanceState
        // If not, the instructions overlay will be shown when tetrisGrid is initialized
        if (::tetrisGrid.isInitialized) {
            // Load high score when resuming
            val savedHighScore = loadHighScore()
            tetrisGrid.game.setHighScore(savedHighScore)
            drawOverlay()
        }
    }
}
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

        // Set up the overlay for hand tracking visualization
        binding.overlay.holder.addCallback(this)
        binding.overlay.setZOrderOnTop(true)
        binding.overlay.holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)

        cameraExecutor = Executors.newSingleThreadExecutor()
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
        tetrisGrid = TetrisGrid(width, height)
        // Start the Tetris game
        tetrisGrid.start()
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
    
    // Show game over screen with final score
    private fun showGameOver(finalScore: Int) {
        Log.d(TAG, "showGameOver() called with score: $finalScore")
        runOnUiThread {
            binding.finalScoreText.text = "Final Score: $finalScore"
            binding.gameOverOverlay.visibility = View.VISIBLE
        }
    }
    
    // Reset the game and hide game over screen
    private fun resetGame() {
        if (::tetrisGrid.isInitialized) {
            tetrisGrid.reset()
            binding.fingerCountText.text = "Score: 0"
            binding.gameOverOverlay.visibility = View.GONE
            tetrisGrid.start()
            Log.d(TAG, "Game reset, score display reset to 0")
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
            
            // Update game state (score and check for game over)
            updateGameState()
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing overlay: ${e.message}")
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Error posting canvas: ${e.message}")
            }
        }
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

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
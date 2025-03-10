package com.example.tetris

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.nio.ByteBuffer

class HandTracker(private val context: Context, private val callback: HandGestureCallback) {

    private var handLandmarker: HandLandmarker? = null
    private val TAG = "HandTracker"
    private val landmarkPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 8f
    }
    private val connectionPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    // Track the last gesture state to prevent rapid repeated triggers
    private var lastFistDetectionTime: Long = 0
    private var lastTwoFingerDetectionTime: Long = 0
    private val gestureDetectionCooldown = 1000L // 1 second cooldown
    
    // Callback interface to report hand gesture data
    interface HandGestureCallback {
        fun onFingerCountUpdated(fingerCount: Int)
        fun onPointerFingerMoved(x: Float, y: Float, isPointing: Boolean)
        fun onFistGesture() // New callback for fist detection
        fun onTwoFingerGesture() // Alternative rotation gesture
    }

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = com.google.mediapipe.tasks.core.BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)  // For now, tracking only one hand
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up hand landmarker: ${e.message}")
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    fun detectHands(imageProxy: ImageProxy): HandLandmarkerResult? {
        try {
            val mediaImage = imageProxy.image ?: return null
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // This uses the improved image conversion method
            val bitmap = imageProxyToBitmap(imageProxy)
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to convert image to bitmap")
                return null
            }
            
            val mpImage = BitmapImageBuilder(bitmap).build()
            
            val options = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                // Since we're using front camera, flip horizontally
                .build()

            val result = handLandmarker?.detect(mpImage, options)
            countFingers(result)
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting hands: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    @ExperimentalGetImage
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Create a properly rotated bitmap for front camera (mirror horizontally)
        val matrix = Matrix()
        matrix.postScale(-1.0f, 1.0f) // Mirror horizontally
        
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, 
            matrix, true
        )
    }

    fun drawHandLandmarks(canvas: Canvas, result: HandLandmarkerResult?, imageWidth: Int, imageHeight: Int) {
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()
        
        // Draw control zone indicators
        drawControlZones(canvas, screenWidth, screenHeight)
        
        if (result == null || result.landmarks().isEmpty()) return

        val hand = result.landmarks()[0]
        
        // Calculate aspect ratio of the camera image
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val screenAspectRatio = screenWidth / screenHeight

        // Determine scaling factors to maintain aspect ratio
        val scaleX: Float
        val scaleY: Float
        if (imageAspectRatio > screenAspectRatio) {
            scaleX = screenWidth
            scaleY = screenWidth / imageAspectRatio
        } else {
            scaleX = screenHeight * imageAspectRatio
            scaleY = screenHeight
        }

        // Offset to center the image on the screen
        val offsetX = (screenWidth - scaleX) / 2
        val offsetY = (screenHeight - scaleY) / 2

        // Fix for 90-degree rotation - swap x and y coordinates and adjust
        for (landmark in hand) {
            // For 90-degree counter-clockwise rotation correction:
            // New x = (1 - landmark.y()) * scaleX + offsetX
            // New y = landmark.x() * scaleY + offsetY
            val scaledX = (1 - landmark.y()) * scaleX + offsetX
            val scaledY = landmark.x() * scaleY + offsetY
            canvas.drawCircle(scaledX, scaledY, 8f, landmarkPaint)
        }

        // Draw connections with the same coordinate transformation
        drawConnectionRotated(canvas, hand, HandLandmark.WRIST, HandLandmark.THUMB_CMC, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.THUMB_CMC, HandLandmark.THUMB_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.THUMB_MCP, HandLandmark.THUMB_IP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.THUMB_IP, HandLandmark.THUMB_TIP, scaleX, scaleY, offsetX, offsetY)

        drawConnectionRotated(canvas, hand, HandLandmark.WRIST, HandLandmark.INDEX_FINGER_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.INDEX_FINGER_MCP, HandLandmark.INDEX_FINGER_PIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.INDEX_FINGER_PIP, HandLandmark.INDEX_FINGER_DIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.INDEX_FINGER_DIP, HandLandmark.INDEX_FINGER_TIP, scaleX, scaleY, offsetX, offsetY)

        drawConnectionRotated(canvas, hand, HandLandmark.WRIST, HandLandmark.MIDDLE_FINGER_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.MIDDLE_FINGER_MCP, HandLandmark.MIDDLE_FINGER_PIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.MIDDLE_FINGER_PIP, HandLandmark.MIDDLE_FINGER_DIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.MIDDLE_FINGER_DIP, HandLandmark.MIDDLE_FINGER_TIP, scaleX, scaleY, offsetX, offsetY)

        drawConnectionRotated(canvas, hand, HandLandmark.WRIST, HandLandmark.RING_FINGER_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.RING_FINGER_MCP, HandLandmark.RING_FINGER_PIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.RING_FINGER_PIP, HandLandmark.RING_FINGER_DIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.RING_FINGER_DIP, HandLandmark.RING_FINGER_TIP, scaleX, scaleY, offsetX, offsetY)

        drawConnectionRotated(canvas, hand, HandLandmark.WRIST, HandLandmark.PINKY_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.PINKY_MCP, HandLandmark.PINKY_PIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.PINKY_PIP, HandLandmark.PINKY_DIP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.PINKY_DIP, HandLandmark.PINKY_TIP, scaleX, scaleY, offsetX, offsetY)

        drawConnectionRotated(canvas, hand, HandLandmark.INDEX_FINGER_MCP, HandLandmark.MIDDLE_FINGER_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.MIDDLE_FINGER_MCP, HandLandmark.RING_FINGER_MCP, scaleX, scaleY, offsetX, offsetY)
        drawConnectionRotated(canvas, hand, HandLandmark.RING_FINGER_MCP, HandLandmark.PINKY_MCP, scaleX, scaleY, offsetX, offsetY)
    }

    // New helper method with rotation correction and aspect ratio adjustment
    private fun drawConnectionRotated(
        canvas: Canvas, 
        landmarks: List<NormalizedLandmark>, 
        start: Int, 
        end: Int, 
        scaleX: Float, 
        scaleY: Float, 
        offsetX: Float, 
        offsetY: Float
    ) {
        val startPoint = landmarks[start]
        val endPoint = landmarks[end]
        canvas.drawLine(
            (1 - startPoint.y()) * scaleX + offsetX, 
            startPoint.x() * scaleY + offsetY,
            (1 - endPoint.y()) * scaleX + offsetX, 
            endPoint.x() * scaleY + offsetY, 
            connectionPaint
        )
    }

    private fun countFingers(result: HandLandmarkerResult?): Int {
        if (result == null || result.landmarks().isEmpty()) {
            callback.onFingerCountUpdated(0)
            callback.onPointerFingerMoved(0f, 0f, false) // No finger detected
            return 0
        }

        val hand = result.landmarks()[0]
        val wristPoint = hand[HandLandmark.WRIST]
        var count = 0
        
        // Get current time for gesture cooldowns
        val currentTime = System.currentTimeMillis()

        // Check thumb - adjusted for rotation
        val thumbTip = hand[HandLandmark.THUMB_TIP]
        val thumbIp = hand[HandLandmark.THUMB_IP]
        val isThumbExtended = thumbTip.y() < thumbIp.y() // Using y instead of x due to rotation
        if (isThumbExtended) {
            count++
        }

        // Check index finger with rotation correction
        val isIndexExtended = isFingerExtended(hand, HandLandmark.INDEX_FINGER_TIP, HandLandmark.INDEX_FINGER_PIP, wristPoint)
        if (isIndexExtended) {
            count++
            
            // SIMPLIFIED POSITION DETECTION:
            // Just using the raw horizontal position (1-y due to front camera orientation)
            // This creates a simple left-to-right mapping without complex transformations
            // The wrist position helps normalize so it's relative to your hand position
            val indexTip = hand[HandLandmark.INDEX_FINGER_TIP]
            val wristPos = hand[HandLandmark.WRIST]
            
            // Normalize based on screen position only
            var normalizedX = 1 - indexTip.y()  // Simple direct mapping (0=left, 1=right)
            
            // Ensure the value stays in valid range
            normalizedX = normalizedX.coerceIn(0f, 1f)
            
            callback.onPointerFingerMoved(
                normalizedX, // Send normalized 0-1 value for horizontal position
                indexTip.x().toFloat(), // Y position (not needed for Tetris control)
                true
            )
        } else {
            callback.onPointerFingerMoved(0f, 0f, false) // Index finger not extended
        }

        // Check middle finger
        val isMiddleExtended = isFingerExtended(hand, HandLandmark.MIDDLE_FINGER_TIP, HandLandmark.MIDDLE_FINGER_PIP, wristPoint)
        if (isMiddleExtended) {
            count++
        }

        // Check ring finger
        val isRingExtended = isFingerExtended(hand, HandLandmark.RING_FINGER_TIP, HandLandmark.RING_FINGER_PIP, wristPoint)
        if (isRingExtended) {
            count++
        }

        // Check pinky
        val isPinkyExtended = isFingerExtended(hand, HandLandmark.PINKY_TIP, HandLandmark.PINKY_PIP, wristPoint)
        if (isPinkyExtended) {
            count++
        }

        // Detect fist gesture - all fingers closed (ignore thumb)
        // Only check the four main fingers, not the thumb
        if (!isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            // We have a valid hand with all main fingers closed - that's a fist!
            // Only trigger if we're not in the cooldown period
            if (currentTime - lastFistDetectionTime > gestureDetectionCooldown) {
                callback.onFistGesture()
                lastFistDetectionTime = currentTime
            }
        }
        
        // Detect exactly two fingers extended (index + middle) and others closed
        // Ignore thumb state completely for easier rotation gesture
        if (isIndexExtended && isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            // Only trigger if we're not in the cooldown period
            if (currentTime - lastTwoFingerDetectionTime > gestureDetectionCooldown) {
                callback.onTwoFingerGesture()
                lastTwoFingerDetectionTime = currentTime
            }
        }

        callback.onFingerCountUpdated(count)
        return count
    }

    private fun isFingerExtended(
        hand: List<NormalizedLandmark>, 
        tipLandmark: Int, 
        pipLandmark: Int, 
        wrist: NormalizedLandmark
    ): Boolean {
        val tip = hand[tipLandmark]
        val pip = hand[pipLandmark]
        
        // Adjusted for rotation - checking x-coordinates (was y) for extension
        // With the rotation, an extended finger will have a smaller x value than its pip
        return tip.x() < pip.x()
    }

    fun close() {
        handLandmarker?.close()
    }

    // Draw visual indicators for the control zones
    private fun drawControlZones(canvas: Canvas, width: Float, height: Float) {
        // Define zone boundaries (match values in TetrisGame)
        val leftZoneBoundary = 0.4f * width
        val rightZoneBoundary = 0.6f * width
        
        // Paint for zone indicators
        val zonePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 100
        }
        
        // Left zone indicator (red)
        zonePaint.color = Color.RED
        canvas.drawLine(leftZoneBoundary, 0f, leftZoneBoundary, height, zonePaint)
        
        // Right zone indicator (blue)
        zonePaint.color = Color.BLUE
        canvas.drawLine(rightZoneBoundary, 0f, rightZoneBoundary, height, zonePaint)
        
        // Zone labels
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            alpha = 100
            textAlign = Paint.Align.CENTER
        }
        
        // Draw labels at the top of the screen
        val labelY = 50f
        canvas.drawText("MOVE LEFT", leftZoneBoundary / 2, labelY, textPaint)
        canvas.drawText("STAY", (leftZoneBoundary + rightZoneBoundary) / 2, labelY, textPaint)
        canvas.drawText("MOVE RIGHT", rightZoneBoundary + (width - rightZoneBoundary) / 2, labelY, textPaint)
    }
}
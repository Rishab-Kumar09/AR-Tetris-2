package com.example.tetris

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.util.Log

class TetrisGame(private val width: Int, private val height: Int) {
    // Grid dimensions (standard Tetris is 10x20)
    companion object {
        const val GRID_WIDTH = 10
        const val GRID_HEIGHT = 20
        
        // Game speed in milliseconds (500ms = 2 blocks per second)
        const val DEFAULT_SPEED = 500L
        
        // Cooldown between piece drops (5 seconds)
        const val PIECE_DROP_COOLDOWN = 5000L
        
        // Gesture cooldowns - much longer to prevent multiple triggers
        const val HARD_DROP_COOLDOWN = 1500L // 1.5 second cooldown for hard drop
        const val ROTATION_COOLDOWN = 800L // 0.8 second cooldown for rotation
    }
    
    // The game grid now stores the colors instead of just binary occupied state
    private val grid = Array(GRID_HEIGHT) { Array(GRID_WIDTH) { 0 } }
    
    // Current and next pieces
    private var currentPiece: Tetrimino? = null
    private var nextPiece: Tetrimino = Tetrimino.random()
    
    // Timestamp for piece drop cooldown
    private var lastPieceDropTime: Long = 0
    
    // Cell size for drawing
    private val cellWidth = width / GRID_WIDTH.toFloat()
    private val cellHeight = height / GRID_HEIGHT.toFloat()
    
    // Paint for drawing locked pieces
    private val lockedPiecePaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 160  // About 63% opacity - slightly more transparent than active pieces
    }
    
    // Paint for block outlines
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = Tetrimino.OUTLINE_WIDTH
        alpha = 220 // 86% opacity for outlines
    }
    
    // Game state
    var isGameOver = false
    var isPaused = false
    var score = 0
        get() {
            Log.d("TetrisGame", "getScore() called, returning: $field")
            return field
        }
    
    // Getter for next piece (for preview)
    fun getNextPiece(): Tetrimino {
        return nextPiece
    }
    
    // Score values based on rows cleared at once (standard Tetris scoring)
    private val scoreValues = mapOf(
        1 to 100,    // 1 row = 100 points
        2 to 300,    // 2 rows = 300 points
        3 to 500,    // 3 rows = 500 points
        4 to 800     // 4 rows = 800 points (Tetris)
    )
    
    // Variables for finger control
    private var lastFingerX: Float = -1f
    private var lastMoveTime: Long = 0
    private val moveDelayMs = 150L // Delay between piece movements for smooth control
    
    // Define three control zones
    private val leftZone = 0.4f  // Left 40% of screen = move left
    private val rightZone = 0.6f // Right 40% of screen = move right
                                // Center 20% = no movement
    
    // Movement amplification for better edge access
    private val handAmplification = 3.0f  // Much stronger amplification
    private val edgeSnapThreshold = 0.35f // Larger edge snap threshold
    
    // Offset the control center to make left side more accessible
    private val controlCenterOffset = 0.3f // Much larger offset to shift the "center" to the right
    
    // Track finger position in normalized coordinates (0.0-1.0)
    private var targetX: Float = 0.5f // Middle of screen
    
    // Variables for gesture cooldowns
    private var lastRotationTime: Long = 0
    private var lastHardDropTime: Long = 0
    
    // Two-finger gesture for rotation
    private var lastTwoFingerTime: Long = 0
    
    // Handler for game loop
    private val handler = Handler(Looper.getMainLooper())
    private val gameLoopRunnable = object : Runnable {
        override fun run() {
            if (!isPaused && !isGameOver) {
                update()
                // Schedule the next update
                handler.postDelayed(this, DEFAULT_SPEED)
            }
        }
    }
    
    // Start the game
    fun start() {
        if (currentPiece == null) {
            spawnNewPiece()
        }
        isPaused = false
        handler.post(gameLoopRunnable)
    }
    
    // Pause the game
    fun pause() {
        isPaused = true
        handler.removeCallbacks(gameLoopRunnable)
    }
    
    // Reset the game
    fun reset() {
        // Clear the grid
        for (i in grid.indices) {
            for (j in grid[i].indices) {
                grid[i][j] = 0
            }
        }
        
        isGameOver = false
        isPaused = true
        score = 0  // Reset score
        
        // Reset pieces
        currentPiece = null
        nextPiece = Tetrimino.random()
        
        // Stop any pending updates
        handler.removeCallbacks(gameLoopRunnable)
    }
    
    // Update game state (called on game tick)
    private fun update() {
        if (currentPiece == null) {
            spawnNewPiece()
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Only allow automatic piece movement if cooldown has elapsed
        if (currentTime - lastPieceDropTime >= PIECE_DROP_COOLDOWN) {
            // Try to move the current piece down
            if (canMove(currentPiece!!, 0, 1)) {
                currentPiece!!.moveDown()
            } else {
                // Lock the piece in place
                lockPiece()
                
                // Check for completed rows
                checkCompletedRows()
                
                // Log state after normal drop
                Log.d("TetrisGame", "After normal drop and row check - Score: $score")
                
                // Spawn a new piece
                spawnNewPiece()
                
                // Check if game is over
                if (isCollision(currentPiece!!)) {
                    isGameOver = true
                    Log.d("TetrisGame", "Game over detected after normal drop. Final score: $score")
                }
                
                // Update the drop time
                lastPieceDropTime = currentTime
            }
        }
    }
    
    // Find the drop position for the shadow preview
    fun findDropPosition(piece: Tetrimino): Int {
        var dropY = piece.y
        
        // Keep moving down until we hit something
        while (canMove(piece, 0, dropY - piece.y + 1)) {
            dropY++
        }
        
        return dropY
    }
    
    // Spawn a new piece at the top of the grid
    private fun spawnNewPiece() {
        currentPiece = nextPiece
        nextPiece = Tetrimino.random()
        
        // Position at the top center
        currentPiece!!.x = (GRID_WIDTH - currentPiece!!.getShape()[0].size) / 2
        currentPiece!!.y = 0
        
        // Reset finger tracking for new piece
        lastFingerX = -1f
    }
    
    // Lock the current piece into the grid
    private fun lockPiece() {
        Log.d("TetrisGame", "Locking piece into grid")
        val shape = currentPiece!!.getShape()
        val color = currentPiece!!.color
        
        for (i in shape.indices) {
            for (j in shape[i].indices) {
                if (shape[i][j] == 1) {
                    val gridX = currentPiece!!.x + j
                    val gridY = currentPiece!!.y + i
                    
                    // Only place within bounds
                    if (gridY >= 0 && gridY < GRID_HEIGHT && gridX >= 0 && gridX < GRID_WIDTH) {
                        grid[gridY][gridX] = color
                    }
                }
            }
        }
        
        // Record the time when a piece was locked
        lastPieceDropTime = System.currentTimeMillis()
        Log.d("TetrisGame", "Piece locked, current score before checking rows: $score")
    }
    
    // Check for completed rows and remove them
    private fun checkCompletedRows() {
        var rowsCleared = 0
        Log.d("TetrisGame", "Starting to check for completed rows. Current score: $score")
        
        for (i in grid.indices) {
            if (isRowComplete(i)) {
                Log.d("TetrisGame", "Found complete row at index: $i")
                clearRow(i)
                rowsCleared++
            }
        }
        
        // Update score based on rows cleared
        if (rowsCleared > 0) {
            val scoreIncrease = scoreValues[rowsCleared] ?: (rowsCleared * 100)
            val oldScore = score
            score += scoreIncrease
            Log.d("TetrisGame", "Cleared $rowsCleared rows. Score changed from $oldScore to $score (increase: $scoreIncrease)")
        } else {
            Log.d("TetrisGame", "No rows cleared")
        }
    }
    
    // Check if a row is complete (all cells filled)
    private fun isRowComplete(row: Int): Boolean {
        for (cell in grid[row]) {
            if (cell == 0) {
                return false
            }
        }
        Log.d("TetrisGame", "Row $row is complete")
        return true
    }
    
    // Clear a row and move all rows above it down
    private fun clearRow(row: Int) {
        Log.d("TetrisGame", "Clearing row $row")
        // Move all rows above down
        for (i in row downTo 1) {
            grid[i] = grid[i - 1].copyOf()
        }
        
        // Clear the top row
        grid[0] = Array(GRID_WIDTH) { 0 }
    }
    
    // Check if the piece can move to the specified position
    fun canMove(piece: Tetrimino, dx: Int, dy: Int): Boolean {
        val shape = piece.getShape()
        
        for (i in shape.indices) {
            for (j in shape[i].indices) {
                if (shape[i][j] == 1) {
                    val newX = piece.x + j + dx
                    val newY = piece.y + i + dy
                    
                    // Check bounds
                    if (newX < 0 || newX >= GRID_WIDTH || newY >= GRID_HEIGHT) {
                        return false
                    }
                    
                    // Check collision with locked pieces (but only if within the grid vertically)
                    if (newY >= 0 && grid[newY][newX] != 0) {
                        return false
                    }
                }
            }
        }
        
        return true
    }
    
    // Check if the piece collides with anything in its current position
    private fun isCollision(piece: Tetrimino): Boolean {
        return !canMove(piece, 0, 0)
    }
    
    // Draw the game state
    fun draw(canvas: Canvas) {
        // Draw the grid (filled cells)
        for (i in grid.indices) {
            for (j in grid[i].indices) {
                if (grid[i][j] != 0) {
                    val left = j * cellWidth
                    val top = i * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight
                    
                    // Draw occupied cells with their original colors
                    lockedPiecePaint.color = grid[i][j]
                    canvas.drawRect(left, top, right, bottom, lockedPiecePaint)
                    
                    // Draw block outlines
                    canvas.drawRect(left, top, right, bottom, outlinePaint)
                }
            }
        }
        
        // Draw the shadow preview (where piece would drop)
        if (currentPiece != null) {
            val dropY = findDropPosition(currentPiece!!)
            currentPiece!!.drawShadow(canvas, cellWidth, cellHeight, dropY)
        }
        
        // Draw the current piece
        currentPiece?.draw(canvas, cellWidth, cellHeight)
    }
    
    // Handle pointer finger movement with zone-based control
    fun handleFingerMovement(x: Float, isPointing: Boolean) {
        if (!isPaused && !isGameOver && currentPiece != null && isPointing) {
            val currentTime = System.currentTimeMillis()
            
            // Only move pieces at certain intervals to prevent too-fast movement
            if (currentTime - lastMoveTime < moveDelayMs) {
                return
            }
            
            // Check which zone the finger is in and move accordingly
            when {
                // Left zone - move piece left
                x < leftZone -> {
                    if (canMove(currentPiece!!, -1, 0)) {
                        currentPiece!!.moveLeft()
                        lastMoveTime = currentTime
                    }
                }
                
                // Right zone - move piece right
                x > rightZone -> {
                    if (canMove(currentPiece!!, 1, 0)) {
                        currentPiece!!.moveRight()
                        lastMoveTime = currentTime
                    }
                }
                
                // Center zone - no movement
                else -> {
                    // Do nothing, just stay in place
                }
            }
        }
    }
    
    // Handle rotation with fist (swapped from two fingers)
    fun handleTwoFingerGesture() {
        if (isPaused || isGameOver || currentPiece == null) {
            return
        }
        
        // Check for hard drop cooldown
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTwoFingerTime < HARD_DROP_COOLDOWN) {
            return  // Skip if in cooldown period
        }
        
        // Find the drop position
        val dropY = findDropPosition(currentPiece!!)
        
        // Move the piece to the drop position
        while (currentPiece!!.y < dropY) {
            currentPiece!!.moveDown()
        }
        
        // Lock the piece immediately
        lockPiece()
        
        // Check for completed rows
        checkCompletedRows()
        
        // Log the state after row clearing
        Log.d("TetrisGame", "After hard drop and row check - Score: $score")
        
        // Spawn a new piece
        spawnNewPiece()
        
        // Check if game is over
        if (isCollision(currentPiece!!)) {
            isGameOver = true
            Log.d("TetrisGame", "Game over detected after hard drop. Final score: $score")
        }
        
        // Update the cooldown time for hard drop
        lastTwoFingerTime = currentTime
    }
    
    // Handle fist gesture to rotate the piece (swapped from hard drop)
    fun handleHardDrop() {
        val currentTime = System.currentTimeMillis()
        
        // Check for cooldown period
        if (currentTime - lastHardDropTime < ROTATION_COOLDOWN) {
            return
        }
        
        // Rotate the piece
        if (!isPaused && !isGameOver && currentPiece != null) {
            val originalRotation = currentPiece!!.rotation
            currentPiece!!.rotate()
            
            if (isCollision(currentPiece!!)) {
                currentPiece!!.rotation = originalRotation
            } else {
                // Update the cooldown time
                lastHardDropTime = currentTime
            }
        }
    }
} 
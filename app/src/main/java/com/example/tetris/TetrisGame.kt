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
    private val grid = Array(GRID_HEIGHT) { IntArray(GRID_WIDTH) }
    
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
        alpha = 255  // Full opacity for locked pieces
    }
    
    // Paint for block outlines
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = Tetrimino.OUTLINE_WIDTH
        alpha = 220 // 86% opacity for outlines
    }

    // Paint for main color (brighter)
    private val mainPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 255 // Full opacity
    }

    // Paint for top highlight
    private val topHighlightPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 102 // 40% opacity
    }

    // Paint for left highlight
    private val leftHighlightPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 102 // 40% opacity
    }

    // Paint for glossy effect
    private val glossPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 64 // 25% opacity
    }

    // Paint for shadow edges
    private val shadowEdgePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 77 // 30% opacity
    }
    
    // Game state and score handling
    var isGameOver = false
    var isPaused = false
    private var _score = 0
    private var _highScore = 0
    var score: Int
        get() {
            Log.d("TetrisGame", "Score accessed: $_score")
            return _score
        }
        private set(value) {
            val oldScore = _score
            _score = value
            if (_score > _highScore) {
                _highScore = _score
                Log.d("TetrisGame", "New high score: $_highScore")
            }
            Log.d("TetrisGame", "Score changed from $oldScore to $_score")
        }
    
    val highScore: Int
        get() = _highScore

    // Method to set high score from outside (for persistence)
    fun setHighScore(value: Int) {
        if (value > _highScore) {
            _highScore = value
            Log.d("TetrisGame", "High score set to: $_highScore")
        }
    }
    
    // Save game state
    fun saveState(): GameState {
        // Convert grid to Array<IntArray>
        val savedGrid = Array(GRID_HEIGHT) { i ->
            grid[i].clone()
        }
        
        return GameState(
            grid = savedGrid,
            score = _score,
            highScore = _highScore,
            isGameOver = isGameOver,
            isPaused = isPaused,
            currentPieceState = currentPiece?.let { CurrentPieceState(it.type, it.rotation, it.x, it.y) },
            nextPieceType = nextPiece.type
        )
    }
    
    // Restore game state
    fun restoreState(state: GameState) {
        // Restore grid
        for (i in state.grid.indices) {
            grid[i] = state.grid[i].clone()
        }
        
        // Restore score and game state
        _score = state.score
        _highScore = state.highScore
        isGameOver = state.isGameOver
        isPaused = state.isPaused
        
        // Restore current piece if it exists
        currentPiece = state.currentPieceState?.let { pieceState ->
            Tetrimino(pieceState.type).apply {
                rotation = pieceState.rotation
                x = pieceState.x
                y = pieceState.y
            }
        }
        
        // Restore next piece
        nextPiece = Tetrimino(state.nextPieceType)
        
        // If game is not paused, restart the game loop
        if (!isPaused && !isGameOver) {
            handler.post(gameLoopRunnable)
        }
    }
    
    // Data classes for state preservation
    data class GameState(
        val grid: Array<IntArray>,
        val score: Int,
        val highScore: Int,
        val isGameOver: Boolean,
        val isPaused: Boolean,
        val currentPieceState: CurrentPieceState?,
        val nextPieceType: TetriminoType
    )
    
    data class CurrentPieceState(
        val type: TetriminoType,
        val rotation: Int,
        val x: Int,
        val y: Int
    )
    
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
        score = 0  // Reset score using the property setter
        // Note: We don't reset the high score here anymore
        Log.d("TetrisGame", "Game reset - Score reset to 0, high score preserved: $_highScore")
        
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
        
        // First, find all completed rows
        val completedRows = mutableListOf<Int>()
        for (i in grid.indices) {
            if (isRowComplete(i)) {
                Log.d("TetrisGame", "Found complete row at index: $i")
                completedRows.add(i)
                rowsCleared++
            }
        }
        
        // Then clear them from bottom to top
        completedRows.sortedDescending().forEach { row ->
            clearRow(row)
        }
        
        // Update score if any rows were cleared
        if (rowsCleared > 0) {
            updateScore(rowsCleared)
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
            grid[i] = grid[i - 1].clone()
        }
        
        // Clear the top row
        grid[0] = IntArray(GRID_WIDTH)
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
                    
                    // Get base color (darker version)
                    val baseColor = grid[i][j]
                    val mainColor = when {
                        baseColor == Color.rgb(0, 183, 183) -> Color.rgb(0, 255, 255)   // Cyan
                        baseColor == Color.rgb(0, 0, 183) -> Color.rgb(0, 0, 255)       // Blue
                        baseColor == Color.rgb(183, 91, 0) -> Color.rgb(255, 127, 0)    // Orange
                        baseColor == Color.rgb(183, 183, 0) -> Color.rgb(255, 255, 0)   // Yellow
                        baseColor == Color.rgb(0, 183, 0) -> Color.rgb(0, 255, 0)       // Green
                        baseColor == Color.rgb(142, 68, 173) -> Color.rgb(155, 89, 182) // Purple
                        baseColor == Color.rgb(183, 0, 0) -> Color.rgb(255, 0, 0)       // Red
                        else -> baseColor
                    }
                    
                    // Draw base (darker) color
                    lockedPiecePaint.color = baseColor
                    canvas.drawRect(left, top, right, bottom, lockedPiecePaint)
                    
                    // Draw main (brighter) color slightly inset
                    mainPaint.color = mainColor
                    canvas.drawRect(
                        left + 1,
                        top + 1,
                        right - 1,
                        bottom - 1,
                        mainPaint
                    )
                    
                    // Draw top highlight
                    canvas.drawRect(
                        left + 1,
                        top,
                        right - 1,
                        top + 1,
                        topHighlightPaint
                    )
                    
                    // Draw left highlight
                    canvas.drawRect(
                        left,
                        top + 1,
                        left + 1,
                        bottom - 1,
                        leftHighlightPaint
                    )
                    
                    // Draw glossy effect on top half
                    canvas.drawRect(
                        left + 2,
                        top + 2,
                        right - 2,
                        top + (bottom - top) / 2,
                        glossPaint
                    )
                    
                    // Draw right shadow edge
                    canvas.drawRect(
                        right - 1,
                        top + 1,
                        right,
                        bottom - 1,
                        shadowEdgePaint
                    )
                    
                    // Draw block outline
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
    
    // Function to update score
    private fun updateScore(rowsCleared: Int) {
        val scoreIncrease = scoreValues[rowsCleared] ?: (rowsCleared * 100)
        val oldScore = score
        score = oldScore + scoreIncrease
        Log.d("TetrisGame", "Score updated - Rows cleared: $rowsCleared, Score increase: $scoreIncrease, New score: $score")
    }
} 
package com.example.tetris

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log

class TetrisGrid(
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        const val ROWS = TetrisGame.GRID_HEIGHT
        const val COLS = TetrisGame.GRID_WIDTH
        // Preview box constants
        const val PREVIEW_SIZE = 3.5f // Smaller size in grid cells
        const val PREVIEW_PADDING = 20f // Padding from edge in pixels
        const val PREVIEW_LABEL_PADDING = 40f // Padding for "NEXT" label
    }

    private val cellWidth: Float = screenWidth / COLS.toFloat()
    private val cellHeight: Float = screenHeight / ROWS.toFloat()
    private val gridPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 128
    }
    
    // Paint for preview box
    private val previewBoxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        alpha = 180
    }
    
    // Paint for preview box background
    private val previewBackgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 160  // Semi-transparent black background
    }
    
    // Paint for preview label
    private val previewLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        alpha = 220
    }
    
    // Create the game instance
    val game = TetrisGame(screenWidth, screenHeight)

    fun draw(canvas: Canvas) {
        // Draw the grid lines
        for (i in 0..COLS) {
            val x = i * cellWidth
            canvas.drawLine(x, 0f, x, screenHeight.toFloat(), gridPaint)
        }

        for (i in 0..ROWS) {
            val y = i * cellHeight
            canvas.drawLine(0f, y, screenWidth.toFloat(), y, gridPaint)
        }
        
        // Draw the game state (occupied cells and current piece)
        game.draw(canvas)
        
        // Draw the next piece preview
        drawNextPiecePreview(canvas)
    }
    
    // Draw the next piece preview
    private fun drawNextPiecePreview(canvas: Canvas) {
        // Get the next piece
        val nextPiece = game.getNextPiece()
        
        // Calculate preview box size and position
        // Position in top-right corner
        val previewBoxSize = cellWidth * PREVIEW_SIZE // Smaller preview box
        val previewLeft = screenWidth - previewBoxSize - PREVIEW_PADDING
        val previewTop = PREVIEW_PADDING + PREVIEW_LABEL_PADDING
        
        // Draw "NEXT" label
        canvas.drawText("NEXT", previewLeft, previewTop - 10f, previewLabelPaint)
        
        // Draw preview box background first
        canvas.drawRect(
            previewLeft, 
            previewTop, 
            previewLeft + previewBoxSize, 
            previewTop + previewBoxSize, 
            previewBackgroundPaint
        )
        
        // Then draw preview box border
        canvas.drawRect(
            previewLeft, 
            previewTop, 
            previewLeft + previewBoxSize, 
            previewTop + previewBoxSize, 
            previewBoxPaint
        )
        
        // Get the shape and size of the next piece
        val shape = nextPiece.getShape()
        val pieceHeight = shape.size
        val pieceWidth = shape[0].size
        
        // Calculate cell size for preview (scaled to fit preview box)
        val previewCellSize = previewBoxSize / 4 // Divide by 4 to ensure any piece fits
        
        // Calculate centering offsets
        val offsetX = previewLeft + (previewBoxSize - pieceWidth * previewCellSize) / 2
        val offsetY = previewTop + (previewBoxSize - pieceHeight * previewCellSize) / 2
        
        // Draw the next piece
        nextPiece.drawPreview(canvas, offsetX, offsetY, previewCellSize)
    }
    
    // Get current score
    fun getScore(): Int {
        val currentScore = game.score
        Log.d("TetrisGrid", "getScore() called, returning: $currentScore")
        return currentScore
    }
    
    // Save game state
    fun saveState(): TetrisGame.GameState {
        return game.saveState()
    }
    
    // Restore game state
    fun restoreState(state: TetrisGame.GameState) {
        game.restoreState(state)
    }
    
    // Reset the game
    fun reset() {
        game.reset()
        Log.d("TetrisGrid", "Game reset, score reset to 0")
    }
    
    // Start the game
    fun start() {
        game.start()
        Log.d("TetrisGrid", "Game started")
    }
    
    // Pause the game
    fun pause() {
        game.pause()
        Log.d("TetrisGrid", "Game paused")
    }
    
    // Handle pointer finger movement
    fun handleFingerMovement(x: Float, isPointing: Boolean) {
        game.handleFingerMovement(x, isPointing)
    }
    
    // Handle fist gesture for hard drop
    fun handleHardDrop() {
        game.handleHardDrop()
    }
    
    // Handle two-finger gesture for rotation
    fun handleTwoFingerRotation() {
        game.handleTwoFingerGesture()
    }
} 
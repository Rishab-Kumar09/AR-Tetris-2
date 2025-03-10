package com.example.tetris

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

// The seven standard Tetriminos: I, J, L, O, S, T, Z
enum class TetriminoType {
    I, J, L, O, S, T, Z
}

class Tetrimino(val type: TetriminoType) {
    companion object {
        // Define all shapes
        private val SHAPES = mapOf(
            TetriminoType.I to arrayOf(
                intArrayOf(0, 0, 0, 0),
                intArrayOf(1, 1, 1, 1),
                intArrayOf(0, 0, 0, 0),
                intArrayOf(0, 0, 0, 0)
            ),
            TetriminoType.J to arrayOf(
                intArrayOf(1, 0, 0),
                intArrayOf(1, 1, 1),
                intArrayOf(0, 0, 0)
            ),
            TetriminoType.L to arrayOf(
                intArrayOf(0, 0, 1),
                intArrayOf(1, 1, 1),
                intArrayOf(0, 0, 0)
            ),
            TetriminoType.O to arrayOf(
                intArrayOf(1, 1),
                intArrayOf(1, 1)
            ),
            TetriminoType.S to arrayOf(
                intArrayOf(0, 1, 1),
                intArrayOf(1, 1, 0),
                intArrayOf(0, 0, 0)
            ),
            TetriminoType.T to arrayOf(
                intArrayOf(0, 1, 0),
                intArrayOf(1, 1, 1),
                intArrayOf(0, 0, 0)
            ),
            TetriminoType.Z to arrayOf(
                intArrayOf(1, 1, 0),
                intArrayOf(0, 1, 1),
                intArrayOf(0, 0, 0)
            )
        )

        // Colors for each type - using authentic Tetris colors
        private val COLORS = mapOf(
            TetriminoType.I to Color.rgb(0, 255, 255),   // Cyan (#00FFFF)
            TetriminoType.J to Color.rgb(0, 0, 255),     // Blue (#0000FF)
            TetriminoType.L to Color.rgb(255, 127, 0),   // Orange (#FF7F00)
            TetriminoType.O to Color.rgb(255, 255, 0),   // Yellow (#FFFF00)
            TetriminoType.S to Color.rgb(0, 255, 0),     // Green (#00FF00)
            TetriminoType.T to Color.rgb(128, 0, 128),   // Purple (#800080)
            TetriminoType.Z to Color.rgb(255, 0, 0)      // Red (#FF0000)
        )
        
        // Transparency level for all pieces (0-255)
        const val PIECE_ALPHA = 180 // About 70% opacity
        
        // Shadow transparency
        const val SHADOW_ALPHA = 80 // About 31% opacity
        
        // Block outline width
        const val OUTLINE_WIDTH = 2f

        // Create a random Tetrimino
        fun random(): Tetrimino {
            return Tetrimino(TetriminoType.values().random())
        }
    }

    // Current piece position (grid coordinates)
    var x = 0
    var y = 0
    
    // Current rotation (0-3)
    var rotation = 0
    
    // Current shape matrix (based on type and rotation)
    private var shape = SHAPES[type]!!

    // Color of this Tetrimino
    val color = COLORS[type]!!
    
    // Shadow paint for the drop preview
    private val shadowPaint = Paint().apply {
        color = this@Tetrimino.color
        style = Paint.Style.FILL
        alpha = SHADOW_ALPHA
    }
    
    // Outline paint for block edges
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH
        alpha = 220 // 86% opacity for outlines
    }

    // Paint for drawing
    private val paint = Paint().apply {
        color = this@Tetrimino.color
        style = Paint.Style.FILL
        alpha = PIECE_ALPHA
    }

    // Get current shape based on rotation
    fun getShape(): Array<IntArray> {
        return when (rotation % 4) {
            0 -> shape
            1 -> rotateClockwise(shape)
            2 -> rotateClockwise(rotateClockwise(shape))
            3 -> rotateClockwise(rotateClockwise(rotateClockwise(shape)))
            else -> shape
        }
    }

    // Move the piece down
    fun moveDown() {
        y++
    }

    // Move the piece left
    fun moveLeft() {
        x--
    }

    // Move the piece right
    fun moveRight() {
        x++
    }

    // Rotate the piece clockwise
    fun rotate() {
        rotation = (rotation + 1) % 4
    }

    // Helper method to rotate a matrix clockwise
    private fun rotateClockwise(matrix: Array<IntArray>): Array<IntArray> {
        val n = matrix.size
        val result = Array(n) { IntArray(n) }
        
        for (i in 0 until n) {
            for (j in 0 until n) {
                result[j][n - 1 - i] = matrix[i][j]
            }
        }
        
        return result
    }
    
    // Draw shadow preview where piece would drop
    fun drawShadow(canvas: Canvas, cellWidth: Float, cellHeight: Float, dropY: Int) {
        val currentShape = getShape()
        
        for (i in currentShape.indices) {
            for (j in currentShape[i].indices) {
                if (currentShape[i][j] == 1) {
                    val left = (x + j) * cellWidth
                    val top = (dropY + i) * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight
                    
                    // Draw shadow fill
                    canvas.drawRect(left, top, right, bottom, shadowPaint)
                    
                    // Draw block outline
                    canvas.drawRect(left, top, right, bottom, outlinePaint)
                }
            }
        }
    }

    // Draw the tetrimino on the canvas
    fun draw(canvas: Canvas, cellWidth: Float, cellHeight: Float) {
        val currentShape = getShape()
        
        for (i in currentShape.indices) {
            for (j in currentShape[i].indices) {
                if (currentShape[i][j] == 1) {
                    val left = (x + j) * cellWidth
                    val top = (y + i) * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight
                    
                    // Draw block fill
                    canvas.drawRect(left, top, right, bottom, paint)
                    
                    // Draw block outline
                    canvas.drawRect(left, top, right, bottom, outlinePaint)
                }
            }
        }
    }
    
    // Draw the tetrimino in the preview box
    fun drawPreview(canvas: Canvas, offsetX: Float, offsetY: Float, cellSize: Float) {
        val currentShape = getShape()
        
        for (i in currentShape.indices) {
            for (j in currentShape[i].indices) {
                if (currentShape[i][j] == 1) {
                    val left = offsetX + j * cellSize
                    val top = offsetY + i * cellSize
                    val right = left + cellSize
                    val bottom = top + cellSize
                    
                    // Draw block fill
                    canvas.drawRect(left, top, right, bottom, paint)
                    
                    // Draw block outline
                    canvas.drawRect(left, top, right, bottom, outlinePaint)
                }
            }
        }
    }
} 
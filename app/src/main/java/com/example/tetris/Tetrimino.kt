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
                intArrayOf(0, 0, 0),
                intArrayOf(0, 1, 1),
                intArrayOf(0, 1, 1)
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

        // Colors for each type - using authentic Tetris colors with darker variants
        private val COLORS = mapOf(
            TetriminoType.I to Color.rgb(0, 183, 183),   // Darker Cyan
            TetriminoType.J to Color.rgb(0, 0, 183),     // Darker Blue
            TetriminoType.L to Color.rgb(183, 91, 0),    // Darker Orange
            TetriminoType.O to Color.rgb(255, 191, 0),   // Golden Yellow
            TetriminoType.S to Color.rgb(0, 183, 0),     // Darker Green
            TetriminoType.T to Color.rgb(142, 68, 173),  // Darker Purple (#8E44AD)
            TetriminoType.Z to Color.rgb(183, 0, 0)      // Darker Red
        )

        // Main colors for each type (brighter version)
        private val MAIN_COLORS = mapOf(
            TetriminoType.I to Color.rgb(0, 255, 255),   // Cyan
            TetriminoType.J to Color.rgb(0, 0, 255),     // Blue
            TetriminoType.L to Color.rgb(255, 127, 0),   // Orange
            TetriminoType.O to Color.rgb(255, 255, 64),  // Bright Lemon Yellow
            TetriminoType.S to Color.rgb(0, 255, 0),     // Green
            TetriminoType.T to Color.rgb(155, 89, 182),  // Purple (#9B59B6)
            TetriminoType.Z to Color.rgb(255, 0, 0)      // Red
        )
        
        // Transparency level for all pieces (0-255)
        const val PIECE_ALPHA = 255 // Full opacity for main piece
        
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

    // Base color (darker) of this Tetrimino
    val color = COLORS[type]!!
    
    // Main color (brighter) of this Tetrimino
    private val mainColor = MAIN_COLORS[type]!!
    
    // Shadow paint for the drop preview
    private val shadowPaint = Paint().apply {
        color = this@Tetrimino.mainColor
        style = Paint.Style.FILL
        alpha = SHADOW_ALPHA
    }
    
    // Paint for block outlines
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH
        alpha = 220 // 86% opacity for outlines
    }

    // Paint for base color (darker)
    private val basePaint = Paint().apply {
        color = this@Tetrimino.color
        style = Paint.Style.FILL
        alpha = PIECE_ALPHA
    }

    // Paint for main color (brighter)
    private val mainPaint = Paint().apply {
        color = this@Tetrimino.mainColor
        style = Paint.Style.FILL
        alpha = PIECE_ALPHA
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
                    
                    // Draw base (darker) color
                    canvas.drawRect(left, top, right, bottom, basePaint)
                    
                    // Draw main (brighter) color slightly inset
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
    }
    
    // Draw the tetrimino in the preview box with the same effects
    fun drawPreview(canvas: Canvas, offsetX: Float, offsetY: Float, cellSize: Float) {
        val currentShape = getShape()
        
        for (i in currentShape.indices) {
            for (j in currentShape[i].indices) {
                if (currentShape[i][j] == 1) {
                    val left = offsetX + j * cellSize
                    val top = offsetY + i * cellSize
                    val right = left + cellSize
                    val bottom = top + cellSize
                    
                    // Draw base (darker) color
                    canvas.drawRect(left, top, right, bottom, basePaint)
                    
                    // Draw main (brighter) color slightly inset
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
    }
} 
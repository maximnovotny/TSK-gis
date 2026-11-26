package cz.photoscanar.ui.gl

import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * Coloured points and lines in world space: the trajectory, the positions the
 * photos were taken from, their viewing directions and the coverage voxels.
 */
class OverlayDraw {

    private var program = 0
    private var aPosition = 0
    private var aColor = 0
    private var uMvp = 0
    private var uPointSize = 0

    private var positions: FloatBuffer = GlUtil.floatBuffer(INITIAL)
    private var colors: FloatBuffer = GlUtil.floatBuffer(INITIAL)

    fun create() {
        program = GlUtil.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    private fun ensureCapacity(floats: Int) {
        if (positions.capacity() < floats) {
            positions = GlUtil.floatBuffer(floats * 2)
            colors = GlUtil.floatBuffer(floats * 2)
        }
    }

    /** @param xyz triplets; @param rgb triplets of the same length, or null for [color] */
    fun drawPoints(mvp: FloatArray, xyz: FloatArray, rgb: FloatArray?, color: FloatArray?, sizePx: Float) {
        draw(GLES20.GL_POINTS, mvp, xyz, rgb, color, sizePx, 1f)
    }

    fun drawLineStrip(mvp: FloatArray, xyz: FloatArray, color: FloatArray, widthPx: Float) {
        draw(GLES20.GL_LINE_STRIP, mvp, xyz, null, color, 1f, widthPx)
    }

    fun drawLines(mvp: FloatArray, xyz: FloatArray, color: FloatArray, widthPx: Float) {
        draw(GLES20.GL_LINES, mvp, xyz, null, color, 1f, widthPx)
    }

    private fun draw(
        primitive: Int,
        mvp: FloatArray,
        xyz: FloatArray,
        rgb: FloatArray?,
        uniformColor: FloatArray?,
        pointSize: Float,
        lineWidth: Float
    ) {
        if (program == 0 || xyz.size < 3) return
        val vertexCount = xyz.size / 3
        ensureCapacity(xyz.size)

        positions.position(0)
        positions.put(xyz, 0, xyz.size).position(0)
        colors.position(0)
        if (rgb != null && rgb.size >= vertexCount * 3) {
            colors.put(rgb, 0, vertexCount * 3).position(0)
        } else {
            val c = uniformColor ?: floatArrayOf(1f, 1f, 1f)
            for (i in 0 until vertexCount) { colors.put(c[0]); colors.put(c[1]); colors.put(c[2]) }
            colors.position(0)
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(uPointSize, pointSize)
        GLES20.glLineWidth(lineWidth)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 0, colors)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glDrawArrays(primitive, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    private companion object {
        const val INITIAL = 6000

        const val VERTEX_SRC = """
            uniform mat4 u_Mvp;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
                v_Color = a_Color;
            }
        """

        const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec3 v_Color;
            void main() {
                gl_FragColor = vec4(v_Color, 0.9);
            }
        """
    }
}

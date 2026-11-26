package cz.photoscanar.ui.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * Draws the camera image as a full-screen quad from an external OES texture.
 *
 * The same texture is used by ARCore (which writes into it and tells us the UVs)
 * and by the plain path (where we own a SurfaceTexture and compute the UVs
 * ourselves), so the preview looks identical in both modes.
 */
class CameraBackground {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uTexTransform = 0

    private val quad = floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f)
    private val positionBuffer: FloatBuffer = GlUtil.floatBufferOf(quad)
    private val uvBuffer: FloatBuffer = GlUtil.floatBuffer(8)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    var textureId = 0
        private set

    fun create() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = GlUtil.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")
        uTexTransform = GLES20.glGetUniformLocation(program, "u_TexTransform")
    }

    /**
     * @param uvs 8 floats matching the NDC quad order
     * @param texTransform optional SurfaceTexture transform; null means identity
     */
    fun draw(uvs: FloatArray, texTransform: FloatArray?) {
        if (program == 0 || uvs.size < 8) return
        uvBuffer.position(0)
        uvBuffer.put(uvs, 0, 8).position(0)
        positionBuffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexTransform, 1, false, texTransform ?: identity, 0)

        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX_SRC = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 u_TexTransform;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = (u_TexTransform * vec4(a_TexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}

package com.lfq06.arknightsreader.turngl

import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * Minimal seam of the GLES20 static methods the renderer and mesh buffers use,
 * so the lifecycle state machine and VBO staging can be exercised on the JVM
 * with a recording fake. The production binding forwards every call straight
 * to [GLES20].
 */
interface GlesProxy {
    fun glCreateShader(type: Int): Int
    fun glShaderSource(shader: Int, source: String)
    fun glCompileShader(shader: Int)
    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray)
    fun glGetShaderInfoLog(shader: Int): String
    fun glCreateProgram(): Int
    fun glAttachShader(program: Int, shader: Int)
    fun glLinkProgram(program: Int)
    fun glGetProgramiv(program: Int, pname: Int, params: IntArray)
    fun glGetProgramInfoLog(program: Int): String
    fun glDeleteShader(shader: Int)
    fun glDeleteProgram(program: Int)
    fun glGenTextures(textures: IntArray)
    fun glDeleteTextures(textures: IntArray)
    fun glClearColor(r: Float, g: Float, b: Float, a: Float)
    fun glEnable(cap: Int)
    fun glDisable(cap: Int)
    fun glViewport(x: Int, y: Int, w: Int, h: Int)
    fun glBindAttribLocation(program: Int, index: Int, name: String)
    fun glGenBuffers(buffers: IntArray)
    fun glDeleteBuffers(buffers: IntArray)
    fun glBindBuffer(target: Int, buffer: Int)
    fun glBufferData(target: Int, size: Int, data: FloatBuffer?, usage: Int)
    fun glBufferSubData(target: Int, offset: Int, size: Int, data: FloatBuffer)
}

/** Production [GlesProxy] that forwards to the real GLES20 pipeline. */
object RealGles : GlesProxy {
    override fun glCreateShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun glShaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun glCompileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun glGetShaderiv(shader: Int, pname: Int, params: IntArray) =
        GLES20.glGetShaderiv(shader, pname, params, 0)
    override fun glGetShaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun glCreateProgram(): Int = GLES20.glCreateProgram()
    override fun glAttachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun glLinkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun glGetProgramiv(program: Int, pname: Int, params: IntArray) =
        GLES20.glGetProgramiv(program, pname, params, 0)
    override fun glGetProgramInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun glDeleteShader(shader: Int) = GLES20.glDeleteShader(shader)
    override fun glDeleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun glGenTextures(textures: IntArray) = GLES20.glGenTextures(textures.size, textures, 0)
    override fun glDeleteTextures(textures: IntArray) = GLES20.glDeleteTextures(textures.size, textures, 0)
    override fun glClearColor(r: Float, g: Float, b: Float, a: Float) = GLES20.glClearColor(r, g, b, a)
    override fun glEnable(cap: Int) = GLES20.glEnable(cap)
    override fun glDisable(cap: Int) = GLES20.glDisable(cap)
    override fun glViewport(x: Int, y: Int, w: Int, h: Int) = GLES20.glViewport(x, y, w, h)
    override fun glBindAttribLocation(program: Int, index: Int, name: String) =
        GLES20.glBindAttribLocation(program, index, name)
    override fun glGenBuffers(buffers: IntArray) = GLES20.glGenBuffers(buffers.size, buffers, 0)
    override fun glDeleteBuffers(buffers: IntArray) = GLES20.glDeleteBuffers(buffers.size, buffers, 0)
    override fun glBindBuffer(target: Int, buffer: Int) = GLES20.glBindBuffer(target, buffer)
    override fun glBufferData(target: Int, size: Int, data: FloatBuffer?, usage: Int) =
        GLES20.glBufferData(target, size, data, usage)
    override fun glBufferSubData(target: Int, offset: Int, size: Int, data: FloatBuffer) =
        GLES20.glBufferSubData(target, offset, size, data)
}

/** GLES constants shared by the JVM-testable pieces and the real draw path. */
object GlesConsts {
    const val VERTEX_SHADER_TYPE = 0x8B31 // GL_VERTEX_SHADER
    const val FRAGMENT_SHADER_TYPE = 0x8B30 // GL_FRAGMENT_SHADER
    const val COMPILE_STATUS = 0x8B81
    const val LINK_STATUS = 0x8B82
    const val ARRAY_BUFFER = 0x8892 // GL_ARRAY_BUFFER
    const val DYNAMIC_DRAW = 0x88E8 // GL_DYNAMIC_DRAW
}

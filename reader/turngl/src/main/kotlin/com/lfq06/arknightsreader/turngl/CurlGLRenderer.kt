package com.lfq06.arknightsreader.turngl

/**
 * GLES-free renderer lifecycle state machine, testable on the JVM via an
 * injected [GlesProxy]. The real draw path (program use, attribute binding,
 * draw calls) lives in [CurlEglFrame]; this class owns shader/program/
 * texture handle management and the NEW -> READY -> RELEASED / ERROR states.
 */
class CurlGLRenderer(private val gl: GlesProxy = RealGles) {

    enum class Lifecycle { NEW, READY, ERROR, RELEASED }

    private var program = 0
    private var frontTexture = 0
    private var backTexture = 0
    private var lifecycleState = Lifecycle.NEW

    val lifecycle: Lifecycle get() = lifecycleState
    val isReady: Boolean get() = lifecycleState == Lifecycle.READY
    /** True after [release]; the host uses this to rebuild for a new cycle. */
    val isReleased: Boolean get() = lifecycleState == Lifecycle.RELEASED
    val programHandle: Int get() = program
    val frontTextureHandle: Int get() = frontTexture
    val backTextureHandle: Int get() = backTexture

    /** Compiles and links the curl program and allocates the two page textures. */
    fun initialize() {
        when (lifecycle) {
            Lifecycle.READY, Lifecycle.ERROR -> return
            Lifecycle.RELEASED -> return
            Lifecycle.NEW -> Unit
        }
        val vs = compile(GlesConsts.VERTEX_SHADER_TYPE, CurlShaderProgram.VERTEX_SHADER)
        if (vs == 0) {
            lifecycleState = Lifecycle.ERROR
            return
        }
        val fs = compile(GlesConsts.FRAGMENT_SHADER_TYPE, CurlShaderProgram.FRAGMENT_SHADER)
        if (fs == 0) {
            gl.glDeleteShader(vs)
            lifecycleState = Lifecycle.ERROR
            return
        }
        program = gl.glCreateProgram()
        gl.glAttachShader(program, vs)
        gl.glAttachShader(program, fs)
        // Attribute locations must be bound BEFORE linking (GLSL ES 2.0):
        // binding after link has no effect on an already-linked program, which
        // made the vertex attrib pointers nondeterministic. position = 0,
        // uv = 1 matches CurlShaderProgram.ATTR_POSITION / ATTR_UV.
        gl.glBindAttribLocation(program, CurlShaderProgram.ATTR_POSITION, "position")
        gl.glBindAttribLocation(program, CurlShaderProgram.ATTR_UV, "uv")
        gl.glLinkProgram(program)
        gl.glDeleteShader(vs)
        gl.glDeleteShader(fs)
        val status = IntArray(1)
        gl.glGetProgramiv(program, GlesConsts.LINK_STATUS, status)
        if (status[0] == 0) {
            gl.glGetProgramInfoLog(program)
            gl.glDeleteProgram(program)
            program = 0
            lifecycleState = Lifecycle.ERROR
            return
        }
        val tex = IntArray(2)
        gl.glGenTextures(tex)
        frontTexture = tex[0]
        backTexture = tex[1]
        lifecycleState = Lifecycle.READY
    }

    /** Frees GL handles; idempotent. After release, initialize is refused. */
    fun release() {
        when (lifecycle) {
            Lifecycle.RELEASED, Lifecycle.NEW -> {
                if (lifecycleState == Lifecycle.NEW) lifecycleState = Lifecycle.RELEASED
                return
            }
            Lifecycle.READY, Lifecycle.ERROR -> Unit
        }
        if (program != 0) {
            gl.glDeleteProgram(program)
            program = 0
        }
        if (frontTexture != 0 || backTexture != 0) {
            gl.glDeleteTextures(intArrayOf(frontTexture, backTexture))
            frontTexture = 0
            backTexture = 0
        }
        lifecycleState = Lifecycle.RELEASED
    }

    /** Hook for subclasses / host: draws one frame. No-op unless READY. */
    fun render(params: CurlFrameParams) {
        if (lifecycleState != Lifecycle.READY) return
        // The actual draw sequence lives in CurlEglFrame.draw; this
        // hook exists so callers always go through the lifecycle guard.
    }

    private fun compile(type: Int, source: String): Int {
        val shader = gl.glCreateShader(type)
        if (shader == 0) return 0
        gl.glShaderSource(shader, source)
        gl.glCompileShader(shader)
        val status = IntArray(1)
        gl.glGetShaderiv(shader, GlesConsts.COMPILE_STATUS, status)
        if (status[0] == 0) {
            gl.glGetShaderInfoLog(shader)
            gl.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}

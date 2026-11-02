package io.github.iamr0s.carvex.session
import io.github.iamr0s.carvex.sessioncore.*
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Front surface between the picture source and the encoder input surface.
 *
 * The picture source - the CarWith host or the app-owned virtual display - renders into [surface]
 * instead of the codec's own input surface. Every submitted frame is blitted through EGL onto the
 * codec surface, and the newest frame stays bound to the texture, so a source that has stopped
 * changing can still be repeated on demand.
 *
 * That repeat is the whole point. MediaCodec has no "produce one more frame" parameter: with surface
 * input the only way to make the encoder emit anything is to submit a buffer, and the platform's own
 * repeat-previous-frame-after hint is optional for encoders - this device ignores it, which is why a
 * still picture used to silence the media stream completely. Resubmitting the held frame gives that
 * still picture the small P frame it needs, and it works for both picture sources, because neither
 * of them knows it is talking to a front surface rather than to the codec.
 *
 * Requests are never queued: a new request while one is pending replaces it, which is the right
 * model for real-time projection. [create] returns null when EGL cannot be brought up, and the
 * caller then hands the codec surface to the source directly, exactly as before. */
class EncoderFrontSurface private constructor(
    private val width:Int,private val height:Int,
    private val thread:HandlerThread,private val handler:Handler,private val log:(String)->Unit,
    private val display:EGLDisplay,private val context:EGLContext,private val window:EGLSurface,
    private val texture:SurfaceTexture,private val textureId:Int,
    private val program:Int,private val matrix:Int,private val sampler:Int,private val quad:FloatBuffer
):AutoCloseable {
    /** Where the picture source renders. The encoder only ever sees what this blits. */
    val surface:Surface=Surface(texture)
    private val pending=AtomicBoolean(false)
    private val fresh=AtomicBoolean(false)
    /** 画面源提交的帧数（不含 repeat）。用来区分"宿主没画"和"我们没转发"。 */
    private val sourceFrames=java.util.concurrent.atomic.AtomicLong()
    private val closed=AtomicBoolean(false)
    private val drawFailure=AtomicBoolean(false)
    private val transform=FloatArray(16)
    /** Shortest gap between two blits; 0 keeps every frame. See [markFresh]. */
    @Volatile private var minIntervalNs=0L
    /** When a frame actually reached the codec; read and written on the GL thread only. */
    private var lastBlitNs=0L

    /** Submits the newest frame again. The picture did not change, so the codec codes it as a skip
     *  P frame; that small repeat is what keeps a still source producing media packets. */
    fun repeat(){schedule(::draw)}

    private fun schedule(work:()->Unit){if(!closed.get()&&pending.compareAndSet(false,true))handler.post(work)}

    /** Shortest gap between two submitted frames. The codec's own frame-rate parameters are hints it
     *  is free to ignore, so a rate that must actually hold has to be enforced here. */
    fun setMinInterval(nanoseconds:Long){minIntervalNs=nanoseconds.coerceAtLeast(0)}

    private fun draw(){
        if(closed.get())return
        pending.set(false)
        try {
            if(!EGL14.eglMakeCurrent(display,window,window,context))return
            // Only a real submission may cross to the producer side: asking again while the source is
            // still would block this thread until it decides to submit something.
            if(fresh.getAndSet(false))texture.updateTexImage()
            texture.getTransformMatrix(transform)
            GLES20.glViewport(0,0,width,height)
            // No clear: the full-screen quad writes every pixel, so clearing first would only add a
            // second full-surface write per frame for nothing.
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
            GLES20.glUniform1i(sampler,0)
            GLES20.glUniformMatrix4fv(matrix,1,false,transform,0)
            quad.position(0)
            GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,16,quad)
            GLES20.glEnableVertexAttribArray(0)
            quad.position(2)
            GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,16,quad)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            EGL14.eglSwapBuffers(display,window)
            lastBlitNs=System.nanoTime()
        }catch(t:Throwable){
            // Reported once: a failing blit would otherwise repeat every heartbeat interval.
            if(drawFailure.compareAndSet(false,true))log("front-surface-draw-failed")
        }
    }

    override fun close(){
        if(!closed.compareAndSet(false,true))return
        val done=CountDownLatch(1)
        handler.post{
            try {
                texture.setOnFrameAvailableListener(null)
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                runCatching{EGL14.eglDestroySurface(display,window)}
                runCatching{EGL14.eglDestroyContext(display,context)}
                runCatching{EGL14.eglTerminate(display)}
                runCatching{GLES20.glDeleteProgram(program)}
                runCatching{texture.release()}
                runCatching{surface.release()}
            }finally{done.countDown()}
        }
        // A wedged GL thread must not stall session teardown; the thread dies with the process anyway.
        runCatching{done.await(2,TimeUnit.SECONDS)}
        thread.quitSafely()
    }

    companion object {
        /** EGL_ANDROID_recordable: the surface an encoder consumes. android.opengl.EGL14 does not
         *  expose this attribute as a constant, so it is spelled out here. */
        private const val EGL_RECORDABLE_ANDROID=0x3142
        private const val VERTEX=
            "attribute vec4 aPosition;\n"+
            "attribute vec4 aTexture;\n"+
            "uniform mat4 uMatrix;\n"+
            "varying vec2 vTexture;\n"+
            "void main(){gl_Position=aPosition;vTexture=(uMatrix*aTexture).xy;}\n"
        private const val FRAGMENT=
            "#extension GL_OES_EGL_image_external : require\n"+
            "precision mediump float;\n"+
            "uniform samplerExternalOES uSampler;\n"+
            "varying vec2 vTexture;\n"+
            "void main(){gl_FragColor=texture2D(uSampler,vTexture);}\n"

        /** Builds the front surface, or null when this device refuses the EGL setup. */
        fun create(width:Int,height:Int,target:Surface,log:(String)->Unit):EncoderFrontSurface? {
            if(width<=0||height<=0)return null
            val thread=HandlerThread("encoder-front").apply{start()}
            val handler=Handler(thread.looper)
            val ready=CountDownLatch(1)
            var built:EncoderFrontSurface?=null
            handler.post{
                try{built=build(width,height,target,thread,handler,log)}
                finally{ready.countDown()}
            }
            runCatching{ready.await(5,TimeUnit.SECONDS)}
            val result=built
            if(result==null){log("front-surface-off");thread.quitSafely()}else log("front-surface-on")
            return result
        }

        private fun build(width:Int,height:Int,target:Surface,thread:HandlerThread,handler:Handler,log:(String)->Unit):EncoderFrontSurface? {
            var display:EGLDisplay=EGL14.EGL_NO_DISPLAY
            var context:EGLContext=EGL14.EGL_NO_CONTEXT
            var window:EGLSurface=EGL14.EGL_NO_SURFACE
            var program=0
            try {
                display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display!=EGL14.EGL_NO_DISPLAY){"no EGL display"}
                val version=IntArray(2)
                check(EGL14.eglInitialize(display,version,0,version,1)){"eglInitialize failed"}
                val configs=arrayOfNulls<EGLConfig>(1)
                val count=IntArray(1)
                val attributes=intArrayOf(
                    EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                    EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                    EGL_RECORDABLE_ANDROID,1,
                    EGL14.EGL_NONE)
                check(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)&&count[0]>=1){"no EGL config"}
                val config=configs[0]?:error("no EGL config")
                context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
                check(context!=EGL14.EGL_NO_CONTEXT){"eglCreateContext failed"}
                window=EGL14.eglCreateWindowSurface(display,config,target,intArrayOf(EGL14.EGL_NONE),0)
                check(window!=EGL14.EGL_NO_SURFACE){"eglCreateWindowSurface failed"}
                check(EGL14.eglMakeCurrent(display,window,window,context)){"eglMakeCurrent failed"}
                val names=IntArray(1)
                GLES20.glGenTextures(1,names,0)
                val textureId=names[0]
                check(textureId!=0){"glGenTextures failed"}
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                program=buildProgram()
                val texture=SurfaceTexture(textureId).apply{setDefaultBufferSize(width,height)}
                val created=EncoderFrontSurface(width,height,thread,handler,log,display,context,window,texture,textureId,
                    program,GLES20.glGetUniformLocation(program,"uMatrix"),GLES20.glGetUniformLocation(program,"uSampler"),quad())
                // The listener runs on the GL thread, so a submission and the blit that answers it never
                // race; the pending flag collapses a burst into one blit.
                texture.setOnFrameAvailableListener({created.markFresh()},handler)
                return created
            }catch(t:Throwable){
                log("front-surface-rejected")
                runCatching{EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)}
                runCatching{if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window)}
                runCatching{if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context)}
                runCatching{if(display!=EGL14.EGL_NO_DISPLAY)EGL14.eglTerminate(display)}
                runCatching{if(program!=0)GLES20.glDeleteProgram(program)}
                return null
            }
        }

        private fun buildProgram():Int {
            val vertex=compileShader(GLES20.GL_VERTEX_SHADER,VERTEX)
            val fragment=compileShader(GLES20.GL_FRAGMENT_SHADER,FRAGMENT)
            val program=GLES20.glCreateProgram()
            GLES20.glAttachShader(program,vertex)
            GLES20.glAttachShader(program,fragment)
            GLES20.glBindAttribLocation(program,0,"aPosition")
            GLES20.glBindAttribLocation(program,1,"aTexture")
            GLES20.glLinkProgram(program)
            val status=IntArray(1)
            GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(status[0]==GLES20.GL_TRUE){"program link failed"}
            return program
        }

        private fun compileShader(type:Int,source:String):Int {
            val shader=GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader,source)
            GLES20.glCompileShader(shader)
            val status=IntArray(1)
            GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0]==GLES20.GL_TRUE){"shader compile failed"}
            return shader
        }

        /** Full-screen triangle strip with the texture coordinates the SurfaceTexture matrix expects. */
        private fun quad():FloatBuffer {
            val data=floatArrayOf(
                -1f,-1f,0f,0f,
                1f,-1f,1f,0f,
                -1f,1f,0f,1f,
                1f,1f,1f,1f)
            return ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(data);position(0)}
        }
    }

    private fun markFresh(){
        fresh.set(true)
        // 只数"画面源真的提交了几帧"：这是判定 CarWith 到底有没有在画的唯一证据。
        // repeat() 走的是另一条路，不计入这里，所以这个数就是宿主实际提交的次数。
        val n=sourceFrames.incrementAndGet()
        if(n==1L||n%300==0L)log("source-frames="+n)
        // A repeat request is never throttled - it happens once a second and is what keeps a still
        // picture alive. Real submissions are: one arriving faster than the requested rate is consumed
        // without being drawn, so the encoder is fed no more frames than the rate allows.
        // A frame arriving at exactly the target interval must not be dropped: clock jitter around
        // the boundary would turn a source that already runs at the requested rate into roughly half
        // of it, and the result reads as stutter rather than as a rate limit. The margin leaves real
        // overshoot limited while keeping that boundary harmless.
        val min=minIntervalNs
        if(min>0L&&System.nanoTime()-lastBlitNs<min-min/8)schedule(::drain) else schedule(::draw)
    }

    /** Consumes a submission without drawing it. Skipping this would leave the picture source blocked
     *  in queueBuffer once its buffer queue fills up, which is worse than the frame meant to be
     *  dropped: the source would stall instead of merely being sampled less often. */
    private fun drain(){
        pending.set(false)
        if(closed.get())return
        try {
            if(!EGL14.eglMakeCurrent(display,window,window,context))return
            if(fresh.getAndSet(false))texture.updateTexImage()
        }catch(t:Throwable){
            if(drawFailure.compareAndSet(false,true))log("front-surface-draw-failed")
        }
    }
}

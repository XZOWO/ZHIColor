package com.naivety.zhicolor.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

@Composable
fun BurningCover(bitmap: ImageBitmap, rotation: () -> Float, size: Dp) {
    val modifier = Modifier.size(size).graphicsLayer { rotationZ = rotation() }.clip(CircleShape)
    if (Build.VERSION.SDK_INT >= 33) {
        BurnCanvas(bitmap, modifier)
    } else {
        Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

@RequiresApi(33)
@Composable
private fun BurnCanvas(bitmap: ImageBitmap, modifier: Modifier) {
    val context = LocalContext.current
    val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val painter = remember { BurnPainter(bitmap) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(bitmap) {
        if (painter.target !== bitmap) {
            val fraction = progress.value
            val prepared = withContext(Dispatchers.Default) { painter.prepare(bitmap, fraction) }
            painter.replace(prepared)
            progress.snapTo(if (reduced) 1f else 0f)
            if (!reduced) progress.animateTo(1f, tween(1150, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        val fraction = progress.value
        drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, fraction) }
    }
}

@RequiresApi(33)
private class BurnPainter(initial: ImageBitmap) {
    var target = initial
        private set
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@BurnPainter.shader }
    private var current = crop(initial)
    private var previous = current
    private var direction = 0f

    init { bind() }

    data class Prepared(val old: Bitmap, val next: Bitmap, val target: ImageBitmap)

    fun prepare(next: ImageBitmap, progress: Float): Prepared =
        Prepared(snapshot(progress), crop(next), next)

    fun replace(prepared: Prepared) {
        previous = prepared.old
        current = prepared.next
        target = prepared.target
        direction = (direction + 1f) % 2f
        bind()
    }

    /** Prepare a rapid-skip composite off the UI thread; software Canvas cannot run AGSL. */
    private fun snapshot(progress: Float): Bitmap {
        if (progress >= 1f) return current
        if (progress <= 0f) return previous
        val old = IntArray(256 * 256)
        val next = IntArray(old.size)
        previous.getPixels(old, 0, 256, 0, 0, 256, 256)
        current.getPixels(next, 0, 256, 0, 0, 256, 256)
        val result = IntArray(old.size)
        fun hash(x: Float, y: Float): Float {
            val v = sin(x * 127.1f + y * 311.7f) * 43758.5453f
            return v - floor(v)
        }
        fun noise(x: Float, y: Float): Float {
            val ix = floor(x); val iy = floor(y)
            val fx = x - ix; val fy = y - iy
            val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
            val a = hash(ix, iy); val b = hash(ix + 1, iy)
            val c = hash(ix, iy + 1); val d = hash(ix + 1, iy + 1)
            return (a + (b - a) * ux) * (1f - uy) + (c + (d - c) * ux) * uy
        }
        for (y in 0 until 256) for (x in 0 until 256) {
            val px = if (direction == 0f) (x + .5f) / 256f else 1f - (x + .5f) / 256f
            val py = (y + .5f) / 256f
            val paper = .57f * px + .43f * py + .16f * (noise(px * 9, py * 9) - .5f) +
                .06f * (noise(px * 27, py * 27) - .5f)
            val edge = paper - (progress * 1.3f - .15f)
            val t = ((edge + .012f) / .024f).coerceIn(0f, 1f)
            val keep = t * t * (3f - 2f * t)
            val charred = 1f - .78f * exp(-edge.coerceAtLeast(0f) * 40f)
            val ember = exp(-abs(edge) * 90f) * .75f
            val i = y * 256 + x
            fun channel(shift: Int, glow: Float): Int {
                val a = (old[i] ushr shift) and 255
                val b = (next[i] ushr shift) and 255
                return (b * (1f - keep) + a * charred * keep + 255f * glow * ember).toInt().coerceIn(0, 255)
            }
            result[i] = (255 shl 24) or (channel(16, 1f) shl 16) or
                (channel(8, .38f) shl 8) or channel(0, .045f)
        }
        return Bitmap.createBitmap(result, 256, 256, Bitmap.Config.ARGB_8888)
    }

    private fun bind() {
        shader.setInputShader("oldCover", BitmapShader(previous, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("newCover", BitmapShader(current, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }

    fun draw(canvas: AndroidCanvas, width: Float, height: Float, progress: Float) {
        shader.setFloatUniform("dimensions", width, height)
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("direction", direction)
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun crop(image: ImageBitmap): Bitmap {
        val bitmap = image.asAndroidBitmap()
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
            AndroidCanvas(it).drawBitmap(bitmap, Rect(x, y, x + side, y + side), Rect(0, 0, 256, 256), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private companion object {
        const val SOURCE = """
            uniform shader oldCover;
            uniform shader newCover;
            uniform float2 dimensions;
            uniform float progress;
            uniform float direction;
            float hash(float2 p) { return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
            float noise(float2 p) {
                float2 i=floor(p), f=fract(p);
                f=f*f*(3.0-2.0*f);
                return mix(mix(hash(i),hash(i+float2(1,0)),f.x),
                    mix(hash(i+float2(0,1)),hash(i+float2(1,1)),f.x),f.y);
            }
            half4 main(float2 coordinate) {
                float2 uv=coordinate/dimensions;
                half4 old=oldCover.eval(uv*256.0);
                half4 next=newCover.eval(uv*256.0);
                if(progress<=0.0) return old;
                if(progress>=1.0) return next;
                float2 p=float2(mix(uv.x,1.0-uv.x,direction),uv.y);
                float paper=.57*p.x+.43*p.y+.16*(noise(p*9.0)-.5)+.06*(noise(p*27.0)-.5);
                float edge=paper-(progress*1.3-.15);
                float keep=smoothstep(-.012,.012,edge);
                float ember=exp(-abs(edge)*90.0);
                float charred=1.0-.78*exp(-max(edge,0.0)*40.0);
                half3 ink=old.rgb*charred;
                half3 result=mix(next.rgb,ink,keep);
                result+=half3(1.0,.38,.045)*ember*.75;
                return half4(result,1.0);
            }
        """
    }
}

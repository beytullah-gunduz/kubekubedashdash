package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
internal enum class Vision { NORMAL, PROTAN, DEUTAN, TRITAN }
private val MACHADO = mapOf(
    Vision.PROTAN to floatArrayOf(0.152286f, 1.052583f, -0.204868f, 0.114503f, 0.786281f, 0.099216f, -0.003882f, -0.048116f, 1.051998f),
    Vision.DEUTAN to floatArrayOf(0.367322f, 0.860646f, -0.227968f, 0.280085f, 0.672501f, 0.047413f, -0.011820f, 0.042940f, 0.968881f),
    Vision.TRITAN to floatArrayOf(1.255528f, -0.076749f, -0.178779f, -0.078411f, 0.930809f, 0.147602f, 0.004733f, 0.691367f, 0.303900f),
)
private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
private fun linear(c: Color, v: Vision): FloatArray {
    val rgb = floatArrayOf(toLinear(c.red), toLinear(c.green), toLinear(c.blue))
    val m = MACHADO[v] ?: return rgb
    return FloatArray(3) { i -> (m[i * 3] * rgb[0] + m[i * 3 + 1] * rgb[1] + m[i * 3 + 2] * rgb[2]).coerceIn(0f, 1f) }
}
private fun oklab(l: FloatArray): FloatArray {
    val lc = cbrt(0.4122214708f * l[0] + 0.5363325363f * l[1] + 0.0514459929f * l[2])
    val mc = cbrt(0.2119034982f * l[0] + 0.6806995451f * l[1] + 0.1073969566f * l[2])
    val sc = cbrt(0.0883024619f * l[0] + 0.2817188376f * l[1] + 0.6299787005f * l[2])
    return floatArrayOf(
        0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc,
        1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc,
        0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc,
    )
}
internal fun oklabDistance(a: Color, b: Color, v: Vision): Float {
    val x = oklab(linear(a, v))
    val y = oklab(linear(b, v))
    return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
}

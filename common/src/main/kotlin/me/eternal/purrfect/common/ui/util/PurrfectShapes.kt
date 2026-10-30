package me.eternal.purrfect.common.ui.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.util.fastCoerceIn
import kotlin.math.*

/**
 * G2 Continuous Curvature Rounded Rectangle.
 * Implements smooth corner transitions (3 segments/10 points per corner).
 */
@Immutable
class G2RoundedRectangle(
    val radius: Dp = 0.dp,
    val topLeft: Dp = radius,
    val topRight: Dp = radius,
    val bottomRight: Dp = radius,
    val bottomLeft: Dp = radius
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        if (topLeft == 0.dp && topRight == 0.dp && bottomRight == 0.dp && bottomLeft == 0.dp) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }

        val path = Path().apply {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            
            val tr = with(density) { topRight.toPx() }.toDouble().fastCoerceIn(0.0, min(w, h) * 0.5)
            val br = with(density) { bottomRight.toPx() }.toDouble().fastCoerceIn(0.0, min(w, h) * 0.5)
            val bl = with(density) { bottomLeft.toPx() }.toDouble().fastCoerceIn(0.0, min(w, h) * 0.5)
            val tl = with(density) { topLeft.toPx() }.toDouble().fastCoerceIn(0.0, min(w, h) * 0.5)

            val builder = G2CornerBuilder.Default

            // Top Right
            var p = builder.getCornerPoints(tr, size)
            var x = w - tr
            var y = 0.0
            moveTo((x + p[0] * tr).toFloat(), (y + p[1] * tr).toFloat())
            drawG2Corner(this, x, y, tr, p)

            // Bottom Right
            p = builder.getCornerPoints(br, size)
            x = w - br
            y = h
            lineTo((x + p[18] * br).toFloat(), (y - p[19] * br).toFloat())
            drawG2CornerFlippedY(this, x, y, br, p)

            // Bottom Left
            p = builder.getCornerPoints(bl, size)
            x = bl
            y = h
            lineTo((x - p[0] * bl).toFloat(), (y - p[1] * bl).toFloat())
            drawG2CornerFlippedXY(this, x, y, bl, p)

            // Top Left
            p = builder.getCornerPoints(tl, size)
            x = tl
            y = 0.0
            lineTo((x - p[18] * tl).toFloat(), (y + p[19] * tl).toFloat())
            drawG2CornerFlippedX(this, x, y, tl, p)

            close()
        }
        return Outline.Generic(path)
    }

    private fun drawG2Corner(path: Path, x: Double, y: Double, r: Double, p: DoubleArray) {
        path.cubicTo(
            (x + p[2] * r).toFloat(), (y + p[3] * r).toFloat(),
            (x + p[4] * r).toFloat(), (y + p[5] * r).toFloat(),
            (x + p[6] * r).toFloat(), (y + p[7] * r).toFloat()
        )
        path.cubicTo(
            (x + p[8] * r).toFloat(), (y + p[9] * r).toFloat(),
            (x + p[10] * r).toFloat(), (y + p[11] * r).toFloat(),
            (x + p[12] * r).toFloat(), (y + p[13] * r).toFloat()
        )
        path.cubicTo(
            (x + p[14] * r).toFloat(), (y + p[15] * r).toFloat(),
            (x + p[16] * r).toFloat(), (y + p[17] * r).toFloat(),
            (x + p[18] * r).toFloat(), (y + p[19] * r).toFloat()
        )
    }

    private fun drawG2CornerFlippedY(path: Path, x: Double, y: Double, r: Double, p: DoubleArray) {
        path.cubicTo(
            (x + p[16] * r).toFloat(), (y - p[17] * r).toFloat(),
            (x + p[14] * r).toFloat(), (y - p[15] * r).toFloat(),
            (x + p[12] * r).toFloat(), (y - p[13] * r).toFloat()
        )
        path.cubicTo(
            (x + p[10] * r).toFloat(), (y - p[11] * r).toFloat(),
            (x + p[8] * r).toFloat(), (y - p[9] * r).toFloat(),
            (x + p[6] * r).toFloat(), (y - p[7] * r).toFloat()
        )
        path.cubicTo(
            (x + p[4] * r).toFloat(), (y - p[5] * r).toFloat(),
            (x + p[2] * r).toFloat(), (y - p[3] * r).toFloat(),
            (x + p[0] * r).toFloat(), (y - p[1] * r).toFloat()
        )
    }

    private fun drawG2CornerFlippedXY(path: Path, x: Double, y: Double, r: Double, p: DoubleArray) {
        path.cubicTo(
            (x - p[2] * r).toFloat(), (y - p[3] * r).toFloat(),
            (x - p[4] * r).toFloat(), (y - p[5] * r).toFloat(),
            (x - p[6] * r).toFloat(), (y - p[7] * r).toFloat()
        )
        path.cubicTo(
            (x - p[8] * r).toFloat(), (y - p[9] * r).toFloat(),
            (x - p[10] * r).toFloat(), (y - p[11] * r).toFloat(),
            (x - p[12] * r).toFloat(), (y - p[13] * r).toFloat()
        )
        path.cubicTo(
            (x - p[14] * r).toFloat(), (y - p[15] * r).toFloat(),
            (x - p[16] * r).toFloat(), (y - p[17] * r).toFloat(),
            (x - p[18] * r).toFloat(), (y - p[19] * r).toFloat()
        )
    }

    private fun drawG2CornerFlippedX(path: Path, x: Double, y: Double, r: Double, p: DoubleArray) {
        path.cubicTo(
            (x - p[16] * r).toFloat(), (y + p[17] * r).toFloat(),
            (x - p[14] * r).toFloat(), (y + p[15] * r).toFloat(),
            (x - p[12] * r).toFloat(), (y + p[13] * r).toFloat()
        )
        path.cubicTo(
            (x - p[10] * r).toFloat(), (y + p[11] * r).toFloat(),
            (x - p[8] * r).toFloat(), (y + p[9] * r).toFloat(),
            (x - p[6] * r).toFloat(), (y + p[7] * r).toFloat()
        )
        path.cubicTo(
            (x - p[4] * r).toFloat(), (y + p[5] * r).toFloat(),
            (x - p[2] * r).toFloat(), (y + p[3] * r).toFloat(),
            (x - p[0] * r).toFloat(), (y - p[1] * r).toFloat()
        )
    }
}

internal class G2CornerBuilder(
    val extendedFraction: Double = 2.0 / 3.0,
    val arcFraction: Double = 0.5
) {
    private val theta = (1.0 - arcFraction) * (PI / 4.0)
    private val cos = cos(theta)
    private val sin = sin(theta)
    private val cot = 1.0 / tan(theta)
    private val cos2 = cos * cos
    private val sin2 = sin * sin
    private val cos3 = cos2 * cos
    private val sin3 = sin2 * sin

    private val k0 = 27.0 * (SQRT_2 - 6.0 * cos + 6.0 * SQRT_2 * cos2 - 4.0 * cos3) * cot +
                2.0 * sin * (-9.0 + 2.0 * (SQRT_2 - 2.0 * sin) * sin3 + 2.0 * SQRT_2 * cos * (9.0 + sin2) - 2.0 * cos2 * (9.0 + 2.0 * sin2))
    private val k1 = -81.0 * (-2.0 + SQRT_2 + 4.0 * (-1.0 + SQRT_2) * cos + 2.0 * (-2.0 + SQRT_2) * cos2) * cot -
                4.0 * sin * (-9.0 + 9.0 * SQRT_2 + SQRT_2 * sin3 + (-2.0 + SQRT_2) * cos * (9.0 + sin2))
    private val k2 = 9.0 * (9.0 * (-4.0 + 3.0 * SQRT_2 + (-6.0 + 4.0 * SQRT_2) * cos) * cot + (-6.0 + 4.0 * SQRT_2) * sin)
    private val k3 = 27.0 * (10.0 - 7.0 * SQRT_2) * cot

    fun getCornerPoints(radius: Double, size: Size): DoubleArray {
        val tW = ((size.width * 0.5 - radius) / radius).fastCoerceIn(0.0, 1.0)
        val tH = ((size.height * 0.5 - radius) / radius).fastCoerceIn(0.0, 1.0)
        return buildCornerBezierPoints(tW, tH)
    }

    private fun buildCornerBezierPoints(tH: Double, tV: Double): DoubleArray {
        val kH = extendedFraction * tH
        val kV = extendedFraction * tV

        val kappa3 = solveCubic(k3, k2, k1 + 8.0 * (-kH) * sin3 * sin, k0)
        val kappa6 = solveCubic(k3, k2, k1 + 8.0 * (-kV) * sin3 * sin, k0)

        val x3 = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + sin) / kappa3
        val y3 = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - cos) / kappa3
        val x2 = x3 - y3 * cot
        val x1 = x2 - 1.5 * kappa3 * y3 * y3 / sin3
        val x0 = -kH

        val x3p = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + sin) / kappa6
        val y3p = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - cos) / kappa6
        val x2p = x3p - y3p * cot
        val x1p = x2p - 1.5 * kappa6 * y3p * y3p / sin3
        val x0p = -kV
        val x6 = 1.0 - y3p
        val y6 = 1.0 - x3p
        val y7 = 1.0 - x2p
        val y8 = 1.0 - x1p
        val y9 = 1.0 - x0p

        val a = 1.5 * kappa3
        val b = 1.5 * kappa6
        val g = cos2 - sin2
        val x36 = x6 - x3
        val y36 = y6 - y3
        val c = -(cos * y36 - sin * x36)
        val d = sin * y36 - cos * x36
        val p = 2.0 * (d / b)
        val q = g * g * g / (a * b * b)
        val r = (a * d * d + c * g * g) / (a * b * b)
        
        val lambda6 = solveQuartic(p, q, r)
        val lambda3 = (-d - b * lambda6 * lambda6) / g
        val x4 = x3 + lambda3 * cos
        val y4 = y3 + lambda3 * sin
        val x5 = x6 - lambda6 * sin
        val y5 = y6 - lambda6 * cos

        return doubleArrayOf(x0, 0.0, x1, 0.0, x2, 0.0, x3, y3, x4, y4, x5, y5, x6, y6, 1.0, y7, 1.0, y8, 1.0, y9)
    }

    private fun solveCubic(a: Double, b: Double, c: Double, d: Double): Double {
        val f = ((3.0 * c / a) - (b * b) / (a * a)) / 3.0
        val g = ((2.0 * b * b * b) / (a * a * a) - (9.0 * b * c) / (a * a) + (27.0 * d) / a) / 27.0
        val h = g * g / 4.0 + f * f * f / 27.0
        val sH = sqrt(h)
        return cbrt(-g / 2.0 + sH) + cbrt(-g / 2.0 - sH) - b / (3.0 * a)
    }

    private fun solveQuartic(p: Double, q: Double, r: Double): Double {
        val b = -p / 2.0
        val c = -r
        val d = r * p / 2.0 - q * q / 8.0
        val f = ((3.0 * c) - (b * b)) / 3.0
        val g = ((2.0 * b * b * b) - (9.0 * b * c) + (27.0 * d)) / 27.0
        val rootR = sqrt(-f * f * f / 27.0)
        val phi = acos(-g / (2.0 * rootR))
        val y = 2.0 * sqrt(-f / 3.0) * cos(phi / 3.0)
        val z = y - b / 3.0
        val u = sqrt(2.0 * z - p)
        return (u - sqrt(u * u - 4.0 * (z + q / (2.0 * u)))) / 2.0
    }

    companion object {
        val Default = G2CornerBuilder()
        private const val SQRT_2 = 1.4142135623730951
        private const val FRAC_1_SQRT_2 = 0.7071067811865476
    }
}

package com.github.noamm9.utils

import java.text.NumberFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

object NumbersUtils {
    private val suffixMultipliers = TreeMap<Char, Long>()
    private val romanNumbers = TreeMap<Char, Int>()
    private val suffixes = TreeMap<Long, Char>()

    init {
        suffixes[1000000000000000000L] = 'e'
        suffixes[1000000000000000L] = 'p'
        suffixes[1000000000000L] = 't'
        suffixes[1000000000L] = 'b'
        suffixes[1000000L] = 'm'
        suffixes[1000L] = 'k'

        suffixes.entries.onEach {
            suffixMultipliers[it.value] = it.key
        }

        romanNumbers['m'] = 1000
        romanNumbers['d'] = 500
        romanNumbers['c'] = 100
        romanNumbers['l'] = 50
        romanNumbers['x'] = 10
        romanNumbers['v'] = 5
        romanNumbers['i'] = 1
    }

    @Suppress("NAME_SHADOWING")
    fun format(value: Number): String {
        val value = value.toLong()
        if (value == Long.MIN_VALUE) return format(Long.MIN_VALUE + 1)
        if (value < 0L) return "-" + format(- value)
        if (value < 1000) return value.toString()
        val (divideBy, suffix) = suffixes.floorEntry(value)
        val truncated = value / (divideBy / 10)
        val hasDecimal = truncated < 100 && truncated / 10.0 != (truncated / 10).toDouble()
        return if (hasDecimal) (truncated / 10.0).toString() + suffix else (truncated / 10).toString() + suffix
    }

    fun format(value: String) = format(value.filter { it.isDigit() }.toLong())

    /// fork: this rebuilt the number from `Double.toString`, which has no way to print zero decimals -
    /// `toFixed(0)` came back as "30.0", which is what every whole-step float slider in the gui showed -
    /// and which switches to scientific notation at 1e7. It also rounded through `roundToInt`, so anything
    /// past about 2.1e9 / 10^precision clamped to Int.MAX_VALUE and printed as 21474836.47. The digits
    /// are now cut from the rounded Long directly, with the same `Math.round` rounding as before: over
    /// 8,000,000 values at precision 0-3 the only outputs that changed were the precision-0 ".0" and
    /// the overflowed ones.
    fun Double.toFixed(precision: Int): String {
        if (this.isNaN() || this.isInfinite()) return toString()
        val scaled = (this * 10.0.pow(precision)).roundToLong()
        if (precision <= 0) return scaled.toString()
        val unit = 10.0.pow(precision).toLong()
        val magnitude = abs(scaled)
        val decimals = (magnitude % unit).toString().padStart(precision, '0')
        return "${if (scaled < 0) "-" else ""}${magnitude / unit}.$decimals"
    }

    fun Float.toFixed(precision: Int): String = toDouble().toFixed(precision)

    fun String.toFixed(precision: Int): String = toDoubleOrNull()?.toFixed(precision) ?: this

    fun String.romanToDecimal(): Int {
        var lastValue = 0
        var decimal = 0

        for (i in lastIndex downTo 0) {
            val value = romanNumbers[get(i).lowercaseChar()] ?: continue
            decimal += if (value < lastValue) - value else value
            lastValue = value
        }

        return decimal
    }

    operator fun Number.div(number: Number) = toDouble() / number.toDouble()
    operator fun Number.times(number: Number) = toDouble() * number.toDouble()
    operator fun Number.minus(number: Number) = toDouble() - number.toDouble()
    operator fun Number.plus(number: Number) = toDouble() + number.toDouble()

    fun formatTime(milliseconds: Number): String {
        val totalSecs = milliseconds.toLong() / 1000
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60

        return buildList {
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0) add("${s}s")
        }.joinToString(" ")
    }

    fun formatComma(value: Number?): String {
        return value?.let { NumberFormat.getNumberInstance(Locale.US).format(it) }.orEmpty()
    }

    fun parseCompactNumber(value: String): Long? {
        if (value.isBlank()) return null
        val cleanValue = value.lowercase().replace(",", "").trim()
        cleanValue.toLongOrNull()?.let { return it }
        val multiplier = suffixMultipliers[cleanValue.lastOrNull()?.lowercaseChar()] ?: return null
        val number = cleanValue.dropLast(1).toDoubleOrNull() ?: return null
        return (number * multiplier).toLong()
    }

    fun parseCompactNumberDouble(value: String): Double? {
        if (value.isBlank()) return null
        val cleanValue = value.lowercase().replace(",", "").trim()
        cleanValue.toDoubleOrNull()?.let { return it }
        val multiplier = suffixMultipliers[cleanValue.lastOrNull()?.lowercaseChar()] ?: return null
        val number = cleanValue.dropLast(1).toDoubleOrNull() ?: return null
        return number * multiplier
    }
}
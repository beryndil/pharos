package com.beryndil.pharos.ui.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Formats up to 10 digits as (###) ###-#### while the user types. */
class PhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.filter { it.isDigit() }.take(10)
        val formatted = buildString {
            digits.forEachIndexed { i, c ->
                when (i) {
                    0 -> append("($c")
                    3 -> append(") $c")
                    6 -> append("-$c")
                    else -> append(c)
                }
            }
        }
        return TransformedText(AnnotatedString(formatted), phoneOffsetMapping(digits.length))
    }
}

private fun phoneOffsetMapping(digitCount: Int): OffsetMapping = object : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val n = offset.coerceIn(0, digitCount)
        return when {
            n == 0 -> 0
            n <= 3 -> n + 1      // add '('
            n <= 6 -> n + 3      // add '(', ')', ' '
            else -> n + 4        // add '(', ')', ' ', '-'
        }
    }

    override fun transformedToOriginal(offset: Int): Int = when {
        offset <= 1 -> 0         // in the '(' zone
        offset <= 4 -> offset - 1
        offset <= 6 -> 3         // in the ') ' zone
        offset <= 9 -> offset - 3
        offset <= 10 -> 6        // in the '-' zone
        else -> offset - 4
    }.coerceIn(0, digitCount)
}

/** Formats a raw string as (###) ###-####. Safe to call on any string; non-digits are stripped. */
fun formatPhoneDisplay(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(10)
    if (digits.isEmpty()) return raw
    return buildString {
        digits.forEachIndexed { i, c ->
            when (i) {
                0 -> append("($c")
                3 -> append(") $c")
                6 -> append("-$c")
                else -> append(c)
            }
        }
    }
}

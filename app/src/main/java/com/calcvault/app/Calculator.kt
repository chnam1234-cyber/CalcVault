package com.calcvault.app

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Tiny expression evaluator for + − × ÷ and %, with normal operator precedence. */
object Calculator {
    private val MC = MathContext(16, RoundingMode.HALF_UP)

    fun evaluate(expr: String): String {
        val tokens = tokenize(expr)
        if (tokens.isEmpty()) return "0"
        // Pass 1: × and ÷
        val terms = mutableListOf<Any>(tokens[0])
        var i = 1
        while (i < tokens.size - 1) {
            val op = tokens[i] as Char
            val n = tokens[i + 1] as BigDecimal
            when (op) {
                '×' -> terms[terms.lastIndex] = (terms.last() as BigDecimal).multiply(n, MC)
                '÷' -> {
                    if (n.signum() == 0) throw ArithmeticException("div0")
                    terms[terms.lastIndex] = (terms.last() as BigDecimal).divide(n, MC)
                }
                else -> { terms.add(op); terms.add(n) }
            }
            i += 2
        }
        // Pass 2: + and −
        var result = terms[0] as BigDecimal
        var j = 1
        while (j < terms.size - 1) {
            val n = terms[j + 1] as BigDecimal
            result = if (terms[j] == '+') result.add(n, MC) else result.subtract(n, MC)
            j += 2
        }
        return format(result)
    }

    private fun tokenize(expr: String): List<Any> {
        val out = mutableListOf<Any>()
        val num = StringBuilder()
        fun flush() {
            if (num.isEmpty()) return
            var s = num.toString()
            var percent = false
            if (s.endsWith("%")) { percent = true; s = s.dropLast(1) }
            var v = BigDecimal(if (s == "." || s.isEmpty()) "0" else s)
            if (percent) v = v.divide(BigDecimal(100), MC)
            out.add(v); num.clear()
        }
        for ((idx, c) in expr.withIndex()) {
            val isUnaryMinus = c == '−' && (idx == 0 || expr[idx - 1] in "+−×÷")
            if (c.isDigit() || c == '.' || c == '%' || isUnaryMinus) {
                num.append(if (c == '−') '-' else c)
            } else if (c in "+−×÷") {
                flush(); out.add(c)
            }
        }
        flush()
        // Drop a trailing operator, e.g. "5+" -> "5"
        if (out.isNotEmpty() && out.last() is Char) out.removeAt(out.lastIndex)
        return out
    }

    private fun format(v: BigDecimal): String {
        val s = v.round(MC).stripTrailingZeros()
        return if (s.abs() >= BigDecimal("1E15") || (s.signum() != 0 && s.abs() < BigDecimal("1E-9")))
            s.toString().replace("E+", "e")
        else s.toPlainString().replace('-', '−')
    }
}

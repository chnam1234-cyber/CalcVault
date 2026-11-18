package com.calcvault.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import android.view.ViewGroup

/**
 * Looks and works like an ordinary calculator.
 * Typing the 6-digit code and pressing "=" opens the hidden vault.
 * On first launch the user sets the code here (type 6 digits, press "=", repeat to confirm).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private lateinit var display: TextView
    private lateinit var preview: TextView
    private lateinit var hint: TextView

    private var expr = ""
    private var justEvaluated = false
    private var pendingNewPin: String? = null   // first entry during setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PinStore(this)
        display = findViewById(R.id.display)
        preview = findViewById(R.id.preview)
        hint = findViewById(R.id.setupHint)

        wireButtons(findViewById(R.id.keypad))
        refreshSetupHint()
        render()
    }

    private fun wireButtons(group: ViewGroup) {
        group.children.forEach { v ->
            when (v) {
                is Button -> v.setOnClickListener { onKey(v.text.toString()) }
                is ViewGroup -> wireButtons(v)
            }
        }
    }

    private fun onKey(key: String) {
        when (key) {
            "C" -> { expr = ""; justEvaluated = false }
            "⌫" -> { expr = expr.dropLast(1); justEvaluated = false }
            "=" -> { onEquals(); return }
            "+", "−", "×", "÷" -> {
                justEvaluated = false
                if (expr.isEmpty()) { if (key == "−") expr = "−" }
                else if (expr.last() in "+−×÷") expr = expr.dropLast(1) + key
                else expr += key
            }
            "%" -> if (expr.isNotEmpty() && expr.last().isDigit()) expr += "%"
            "." -> {
                if (justEvaluated) { expr = ""; justEvaluated = false }
                val current = expr.takeLastWhile { it !in "+−×÷" }
                if ('.' !in current && '%' !in current) expr += if (current.isEmpty()) "0." else "."
            }
            else -> { // digit
                if (justEvaluated) { expr = ""; justEvaluated = false }
                if (expr.length < 40) expr += key
            }
        }
        render()
    }

    private fun onEquals() {
        val entered = expr

        // First-time setup: user must create a 6-digit code
        if (!store.hasPin) {
            handleSetup(entered); return
        }

        // Secret unlock: exactly the 6-digit code, nothing else
        if (PinStore.isValidPin(entered) && store.checkPin(entered)) {
            expr = ""; justEvaluated = false; render()
            startActivity(Intent(this, VaultActivity::class.java))
            return
        }

        // Otherwise: behave like a normal calculator
        expr = try { Calculator.evaluate(entered) } catch (e: Exception) { "Error" }
        justEvaluated = true
        render()
        if (expr == "Error") expr = ""
    }

    private fun handleSetup(entered: String) {
        if (!PinStore.isValidPin(entered)) {
            toast(getString(R.string.setup_need_6))
            return
        }
        val first = pendingNewPin
        if (first == null) {
            pendingNewPin = entered
            toast(getString(R.string.setup_confirm))
        } else if (first == entered) {
            store.setPin(entered)
            pendingNewPin = null
            toast(getString(R.string.setup_done))
        } else {
            pendingNewPin = null
            toast(getString(R.string.setup_mismatch))
        }
        expr = ""
        refreshSetupHint()
        render()
    }

    private fun refreshSetupHint() {
        if (store.hasPin) { hint.visibility = View.GONE; return }
        hint.visibility = View.VISIBLE
        hint.setText(if (pendingNewPin == null) R.string.setup_hint_1 else R.string.setup_hint_2)
    }

    private fun render() {
        display.text = expr.ifEmpty { "0" }
        // Live result preview (only when there's an operator)
        preview.text = if (!justEvaluated && expr.any { it in "+×÷%" || (it == '−' && expr.indexOf(it) > 0) }) {
            try { Calculator.evaluate(expr) } catch (e: Exception) { "" }
        } else ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

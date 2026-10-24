package com.autosend.computer

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small local diagnostic history, separate from task/send logs and exported configuration. */
object ComputerTransferLog {
    private const val PREFS = "autosend_computer_diagnostics"
    private const val TAG = "AutoSendComputer"

    @Synchronized
    fun record(context: Context, summary: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val line = "$time ${summary.take(1800)}"
        Log.i(TAG, line)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = (prefs.getString("recent", "").orEmpty().lines().filter { it.isNotBlank() } + line)
            .takeLast(20).joinToString("\n")
        prefs.edit().putString("recent", recent).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "").orEmpty()
}

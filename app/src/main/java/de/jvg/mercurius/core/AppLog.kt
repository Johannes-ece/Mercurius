package de.jvg.mercurius.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text log kept in memory and in a file, so problems can be read back from the app. */
object AppLog {
    private const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 512_000L

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.GERMANY)
    private var file: File? = null

    fun init(context: Context) = synchronized(this) {
        if (file != null) return
        val f = File(context.filesDir, "mercurius.log")
        if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach(lines::addLast)
        file = f
    }

    fun i(message: String) = synchronized(this) {
        Log.i("Mercurius", message)
        val line = "${format.format(Date())}  $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        file?.let { f ->
            if (f.length() > MAX_FILE_BYTES) f.writeText(lines.joinToString("\n", postfix = "\n"))
            else f.appendText(line + "\n")
        }
    }

    /** Newest line first. */
    fun recent(): List<String> = synchronized(this) { lines.reversed() }
}

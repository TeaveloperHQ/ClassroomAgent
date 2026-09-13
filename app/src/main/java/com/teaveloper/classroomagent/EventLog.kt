package com.teaveloper.classroomagent

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Rolling in-memory log of consensus events so a teacher can review what
 * happened during class without having watched every second live.
 *
 * Kept small (last 100 entries) and unstructured — DIAG dumps it as text.
 * Not persisted: transient by design, cleared on service restart, which
 * matches the sliding-window nature of everything else in this branch.
 */
object EventLog {

    private const val CAPACITY = 100
    private val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun record(kind: String, detail: String) {
        val ts = fmt.format(Date())
        val line = "[$ts] $kind $detail"
        synchronized(entries) {
            entries.add(line)
            while (entries.size > CAPACITY) entries.removeAt(0)
        }
    }

    fun recent(n: Int = 20): List<String> = synchronized(entries) {
        entries.takeLast(n).toList()
    }

    fun clear() = synchronized(entries) { entries.clear() }
}

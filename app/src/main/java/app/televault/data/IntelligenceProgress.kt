package app.televault.data

internal data class PrivacyRestoreProgress(val phase: String = "waiting", val done: Int = 0, val total: Int = 0, val failure: String = "") {
    val percent: Int? get() = if (phase == "ready") {
        100
    } else if (total > 0) {
        ((done.toLong() * 100 / total).toInt()).coerceIn(0, 99)
    } else {
        null
    }
}

/** Counts only authenticated saved results, never attempts or an inference timer. RAM-only and scoped. */
internal data class IntelligenceProgress(val phase: String = "waiting", val total: Int = 0, val enabled: Set<String> = emptySet(), val completed: Map<String, Int> = emptyMap()) {
    fun percent(engine: String): Int? = if (engine !in enabled || total == 0 && phase != "complete") {
        null
    } else if (total == 0) {
        100
    } else {
        ((completed[engine] ?: 0).toLong() * 100 / total).toInt().coerceIn(0, 100)
    }
    val percent: Int? get() = if (enabled.isEmpty() || total == 0 && phase != "complete") {
        null
    } else if (total == 0) {
        100
    } else {
        (completed.filterKeys { it in enabled }.values.sumOf { it.toLong().coerceIn(0, total.toLong()) } * 100 / (total.toLong() * enabled.size)).toInt().coerceIn(0, 100)
    }
}

internal class IntelligenceProgressTracker(private val publish: (IntelligenceProgress) -> Unit) {
    private var expected = emptySet<Pair<String, String>>()
    private var enabled = emptySet<String>()
    private val saved = mutableMapOf<String, MutableSet<Pair<String, String>>>()
    private var phase = "preparing"
    private var scope = ""
    private val counts = mutableMapOf<String, Int>()
    private val excluded = hashSetOf<Pair<String, String>>()

    @Synchronized fun begin(engines: Set<String>, scopeKey: String = "") {
        if (scopeKey.isBlank() || scope != scopeKey) {
            expected = emptySet()
            saved.clear()
            counts.clear()
            excluded.clear()
        }
        scope = scopeKey
        enabled = engines
        phase = "preparing"
        emit()
    }

    @Synchronized fun candidates(items: Set<Pair<String, String>>) {
        expected = items - excluded
        for (engine in enabled) counts[engine] = saved[engine].orEmpty().count { it in expected }
        emit()
    }

    @Synchronized fun exclude(id: String, stamp: String) {
        excluded.add(id to stamp)
        candidates(expected - (id to stamp))
    }

    @Synchronized fun record(id: String, stamp: String, engines: Set<String>) {
        for (engine in engines.intersect(enabled)) {
            if (saved.getOrPut(engine) { hashSetOf() }.add(id to stamp) && (id to stamp) in expected) counts[engine] = (counts[engine] ?: 0) + 1
        }
        emit()
    }

    @Synchronized fun phase(value: String) {
        phase = value
        emit()
    }
    private fun emit() = publish(IntelligenceProgress(phase, expected.size, enabled, enabled.associateWith { counts[it] ?: 0 }))
}

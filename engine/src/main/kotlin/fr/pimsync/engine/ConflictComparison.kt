package fr.pimsync.engine

/** One competing version of an object, flattened into named text fields. A null [fields] means a deletion. */
data class ComparableVersion(val label: String, val fields: Map<String, String>?)

/** One field compared across competing versions; [values] keeps the order of the versions given. */
data class ComparisonRow(val field: String, val values: List<Pair<String, String>>) {
    val differs: Boolean get() = values.map { it.second }.distinct().size > 1
}

object ConflictComparison {
    /** Compares only the versions that still hold data; a deletion has no fields to compare. */
    fun compare(versions: List<ComparableVersion>): List<ComparisonRow> {
        val withData = versions.filter { it.fields != null }
        val names = linkedSetOf<String>()
        withData.forEach { names.addAll(it.fields.orEmpty().keys) }
        return names.map { name ->
            ComparisonRow(name, withData.map { it.label to it.fields.orEmpty()[name].orEmpty() })
        }
    }
}

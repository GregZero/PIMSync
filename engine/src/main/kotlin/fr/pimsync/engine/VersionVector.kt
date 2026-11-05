package fr.pimsync.engine

/** Causal counters for one PIMSync object. Wall-clock timestamps never decide which edit wins. */
class VersionVector(counters: Map<String, Long> = emptyMap()) {
    val counters: Map<String, Long> = counters.toSortedMap().toMap()

    init {
        require(this.counters.keys.none { it.isBlank() }) { "Device identifiers cannot be blank" }
        require(this.counters.values.all { it > 0L }) { "Version counters must be positive" }
    }

    operator fun get(deviceId: String): Long = counters[deviceId] ?: 0L

    fun increment(deviceId: String): VersionVector {
        require(deviceId.isNotBlank()) { "Device identifier cannot be blank" }
        return VersionVector(counters + (deviceId to (get(deviceId) + 1L)))
    }

    fun join(other: VersionVector): VersionVector {
        val keys = counters.keys + other.counters.keys
        return VersionVector(keys.associateWith { maxOf(get(it), other[it]) }.filterValues { it > 0L })
    }

    /** True only when this vector is strictly later than [other]. Equal vectors are concurrent/ambiguous. */
    fun strictlyDominates(other: VersionVector): Boolean {
        val keys = counters.keys + other.counters.keys
        return keys.all { get(it) >= other[it] } && keys.any { get(it) > other[it] }
    }

    override fun equals(other: Any?): Boolean = other is VersionVector && counters == other.counters
    override fun hashCode(): Int = counters.hashCode()
    override fun toString(): String = counters.toString()

    companion object {
        fun joinAll(vectors: Iterable<VersionVector>): VersionVector =
            vectors.fold(VersionVector()) { joined, vector -> joined.join(vector) }
    }
}

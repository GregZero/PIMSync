package fr.pimsync.engine

object DeviceIds {
    /** Suggests "tel-" followed by the last six letters or digits of a platform device identifier. */
    fun suggest(platformId: String?): String? {
        val tail = platformId.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.takeLast(6)
        return if (tail.length == 6) "tel-$tail" else null
    }
}

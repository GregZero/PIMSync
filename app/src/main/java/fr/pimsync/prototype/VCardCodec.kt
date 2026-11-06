package fr.pimsync.prototype

/** Small vCard 3.0 boundary between the Android contact provider and PIMSync journal payloads. */
data class PimContactCard(
    val displayName: String,
    val phones: List<String>,
    val emails: List<String>
)

object VCardCodec {
    fun encode(card: PimContactCard): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:${escape(card.displayName.ifBlank { "Contact sans nom" })}\r\n")
        if (card.displayName.isNotBlank()) append("N:${escape(card.displayName)};;;;\r\n")
        card.phones.forEach { append("TEL:${escape(it)}\r\n") }
        card.emails.forEach { append("EMAIL:${escape(it)}\r\n") }
        append("END:VCARD\r\n")
    }

    fun decode(value: String): PimContactCard {
        val lines = mutableListOf<String>()
        value.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] += line.drop(1)
            } else if (line.isNotBlank()) {
                lines += line
            }
        }
        val name = mutableListOf<String>()
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        for (line in lines) {
            val separator = line.indexOf(':')
            if (separator < 1) continue
            val property = line.substring(0, separator).substringAfterLast('.').substringBefore(';').uppercase()
            val content = unescape(line.substring(separator + 1))
            when (property) {
                "FN" -> name += content
                "TEL" -> phones += content
                "EMAIL" -> emails += content
            }
        }
        return PimContactCard(name.firstOrNull().orEmpty(), phones, emails)
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    private fun unescape(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character == '\\' && index < value.length) {
                when (val escaped = value[index++]) {
                    'n', 'N' -> result.append('\n')
                    else -> result.append(escaped)
                }
            } else {
                result.append(character)
            }
        }
        return result.toString()
    }
}

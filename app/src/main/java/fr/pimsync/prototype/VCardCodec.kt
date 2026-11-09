package fr.pimsync.prototype

/** Contact data shared in PIMSync's append-only journal using the vCard 3.0 format. */
data class PimContactCard(
    val displayName: String,
    /** Kept for the prototype UI; structured values below retain their labels and types. */
    val phones: List<String>,
    val emails: List<String>,
    val name: PimStructuredName = PimStructuredName(),
    val typedPhones: List<PimLabeledValue> = emptyList(),
    val typedEmails: List<PimLabeledValue> = emptyList(),
    val addresses: List<PimPostalAddress> = emptyList(),
    val organization: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val birthday: String = "",
    val notes: List<String> = emptyList(),
    val websites: List<PimLabeledValue> = emptyList(),
    val categories: List<String> = emptyList()
)

data class PimStructuredName(
    val family: String = "",
    val given: String = "",
    val additional: String = "",
    val prefix: String = "",
    val suffix: String = ""
)

data class PimLabeledValue(val value: String, val type: String = "other", val label: String = "")

data class PimPostalAddress(
    val type: String = "other",
    val label: String = "",
    val poBox: String = "",
    val extended: String = "",
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = ""
) {
    val formatted: String
        get() = listOf(poBox, extended, street, city, region, postcode, country)
            .filter(String::isNotBlank).joinToString(", ")
}

object VCardCodec {
    fun encode(card: PimContactCard): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:${escape(card.displayName.ifBlank { "Contact sans nom" })}\r\n")
        val name = card.name
        if (listOf(name.family, name.given, name.additional, name.prefix, name.suffix).any(String::isNotBlank)) {
            append("N:${listOf(name.family, name.given, name.additional, name.prefix, name.suffix).joinToString(";") { escape(it) }}\r\n")
        } else if (card.displayName.isNotBlank()) {
            // Backward compatibility for contacts created by early PIMSync versions.
            append("N:${escape(card.displayName)};;;;\r\n")
        }
        val phoneValues = card.typedPhones.ifEmpty { card.phones.map { PimLabeledValue(it) } }
        phoneValues.forEach { append(labeledLine("TEL", it)) }
        val emailValues = card.typedEmails.ifEmpty { card.emails.map { PimLabeledValue(it) } }
        emailValues.forEach { append(labeledLine("EMAIL", it)) }
        card.addresses.forEach { address ->
            append("ADR;TYPE=${parameter(address.type)}:")
            append(listOf(address.poBox, address.extended, address.street, address.city, address.region, address.postcode, address.country)
                .joinToString(";") { escape(it) })
            append("\r\n")
            if (address.label.isNotBlank()) append("LABEL;TYPE=${parameter(address.type)}:${escape(address.label)}\r\n")
        }
        if (card.organization.isNotBlank() || card.department.isNotBlank()) {
            append("ORG:${listOf(card.organization, card.department).joinToString(";") { escape(it) }}\r\n")
        }
        if (card.jobTitle.isNotBlank()) append("TITLE:${escape(card.jobTitle)}\r\n")
        if (card.birthday.isNotBlank()) append("BDAY:${escape(card.birthday)}\r\n")
        card.notes.forEach { append("NOTE:${escape(it)}\r\n") }
        card.websites.forEach { append(labeledLine("URL", it)) }
        if (card.categories.isNotEmpty()) append("CATEGORIES:${card.categories.joinToString(",") { escape(it) }}\r\n")
        append("END:VCARD\r\n")
    }

    fun decode(value: String): PimContactCard {
        val lines = mutableListOf<String>()
        value.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] += line.drop(1)
            } else if (line.isNotBlank()) lines += line
        }
        var displayName = ""
        var name = PimStructuredName()
        val phones = mutableListOf<PimLabeledValue>()
        val emails = mutableListOf<PimLabeledValue>()
        val addresses = mutableListOf<PimPostalAddress>()
        var organization = ""
        var department = ""
        var jobTitle = ""
        var birthday = ""
        val notes = mutableListOf<String>()
        val websites = mutableListOf<PimLabeledValue>()
        val categories = mutableListOf<String>()

        for (line in lines) {
            val separator = line.indexOf(':')
            if (separator < 1) continue
            val head = line.substring(0, separator)
            val property = head.substringAfterLast('.').substringBefore(';').uppercase()
            val params = head.substringAfter(';', "").split(';').mapNotNull { parameter ->
                val index = parameter.indexOf('=')
                if (index > 0) parameter.substring(0, index).uppercase() to parameter.substring(index + 1).trim('"') else null
            }.toMap()
            val raw = line.substring(separator + 1)
            when (property) {
                "FN" -> if (displayName.isBlank()) displayName = unescape(raw)
                "N" -> {
                    val parts = splitEscaped(raw, ';').map(::unescape)
                    name = PimStructuredName(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" }, parts.getOrElse(4) { "" })
                }
                "TEL" -> phones += PimLabeledValue(unescape(raw), normalizedType(params["TYPE"]), params["X-ABLABEL"].orEmpty())
                "EMAIL" -> emails += PimLabeledValue(unescape(raw), normalizedType(params["TYPE"]), params["X-ABLABEL"].orEmpty())
                "ADR" -> {
                    val p = splitEscaped(raw, ';').map(::unescape)
                    addresses += PimPostalAddress(
                        normalizedType(params["TYPE"]), params["X-ABLABEL"].orEmpty(),
                        p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" },
                        p.getOrElse(3) { "" }, p.getOrElse(4) { "" }, p.getOrElse(5) { "" }, p.getOrElse(6) { "" }
                    )
                }
                "ORG" -> {
                    val p = splitEscaped(raw, ';').map(::unescape)
                    organization = p.firstOrNull().orEmpty()
                    department = p.drop(1).filter(String::isNotBlank).joinToString(";")
                }
                "TITLE" -> jobTitle = unescape(raw)
                "BDAY" -> birthday = unescape(raw)
                "NOTE" -> notes += unescape(raw)
                "URL" -> websites += PimLabeledValue(unescape(raw), normalizedType(params["TYPE"]), params["X-ABLABEL"].orEmpty())
                "CATEGORIES" -> categories += splitEscaped(raw, ',').map(::unescape).filter(String::isNotBlank)
            }
        }
        return PimContactCard(
            displayName = displayName,
            phones = phones.map { it.value },
            emails = emails.map { it.value },
            name = name,
            typedPhones = phones,
            typedEmails = emails,
            addresses = addresses,
            organization = organization,
            department = department,
            jobTitle = jobTitle,
            birthday = birthday,
            notes = notes,
            websites = websites,
            categories = categories
        )
    }

    private fun labeledLine(property: String, value: PimLabeledValue): String = buildString {
        append("$property;TYPE=${parameter(value.type)}")
        if (value.label.isNotBlank()) append(";X-ABLABEL=\"${value.label.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        append(":${escape(value.value)}\r\n")
    }

    private fun normalizedType(value: String?): String {
        val types = value?.lowercase()?.split(',').orEmpty()
        return when {
            "cell" in types || "mobile" in types -> "mobile"
            "home" in types -> "home"
            "work" in types -> "work"
            "custom" in types || "x-custom" in types -> "custom"
            else -> "other"
        }
    }

    private fun parameter(value: String): String = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "OTHER" }

    private fun splitEscaped(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var escaped = false
        value.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == delimiter) {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
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
            } else result.append(character)
        }
        return result.toString()
    }
}

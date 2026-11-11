package fr.pimsync.prototype

/** Flattens a contact card into named text fields so competing versions can be compared. */
object ContactCardFields {
    fun of(card: PimContactCard): Map<String, String> = linkedMapOf(
        "Nom" to card.displayName,
        "Téléphones" to labeled(card.typedPhones.ifEmpty { card.phones.map { PimLabeledValue(it) } }),
        "E-mails" to labeled(card.typedEmails.ifEmpty { card.emails.map { PimLabeledValue(it) } }),
        "Adresses" to card.addresses.filter { it.formatted.isNotBlank() }
            .map { withType(it.formatted, it.type) }.sorted().joinToString(" ; "),
        "Organisation" to card.organization,
        "Service" to card.department,
        "Fonction" to card.jobTitle,
        "Anniversaire" to card.birthday,
        "Notes" to card.notes.filter(String::isNotBlank).joinToString(" / "),
        "Sites web" to card.websites.map { it.value }.filter(String::isNotBlank).sorted().joinToString(" ; "),
        "Catégories" to card.categories.filter(String::isNotBlank).distinct().sorted().joinToString(", ")
    )

    private fun labeled(values: List<PimLabeledValue>): String =
        values.filter { it.value.isNotBlank() }.map { withType(it.value, it.type) }.sorted().joinToString(" ; ")

    private fun withType(value: String, type: String): String =
        if (type.isBlank() || type == "other") value else "$value ($type)"
}

package fr.pimsync.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class VCardCodecTest {
    private fun roundTrip(card: PimContactCard) = VCardCodec.decode(VCardCodec.encode(card))

    private fun simple(
        displayName: String,
        phones: List<PimLabeledValue> = emptyList(),
        notes: List<String> = emptyList(),
        name: PimStructuredName = PimStructuredName()
    ) = PimContactCard(
        displayName = displayName,
        phones = phones.map { it.value },
        emails = emptyList(),
        name = name,
        typedPhones = phones,
        notes = notes
    )

    @Test
    fun `a complete card with accents survives a round trip`() {
        val phones = listOf(
            PimLabeledValue("+33 6 12 34 56 78", "mobile"),
            PimLabeledValue("+33 1 02 03 04 05", "home"),
            PimLabeledValue("0102030405", "work"),
            PimLabeledValue("+33 9 99 99 99 99", "other")
        )
        val emails = listOf(PimLabeledValue("elodie@example.org", "home"))
        val card = PimContactCard(
            displayName = "Dr Élodie Zoë Müller-Ñandú Jr",
            phones = phones.map { it.value },
            emails = emails.map { it.value },
            name = PimStructuredName("Müller-Ñandú", "Élodie", "Zoë", "Dr", "Jr"),
            typedPhones = phones,
            typedEmails = emails,
            addresses = listOf(PimPostalAddress("home", "", "BP 12", "Bât. B", "1 rue de l’Église", "Metz", "Grand Est", "57000", "France")),
            organization = "Société Générale",
            department = "Service A",
            jobTitle = "Chargée de mission",
            birthday = "1990-05-17",
            notes = listOf("Première note"),
            websites = listOf(PimLabeledValue("https://example.org/x?a=1&b=2", "work")),
            categories = listOf("Famille", "Amis")
        )
        assertEquals(card, roundTrip(card))
    }

    @Test
    fun `semicolons commas and backslashes are preserved`() {
        val card = simple(
            displayName = "Dupont; Jean, fils",
            notes = listOf("ligne 1\nligne 2; avec virgule, et un antislash \\ puis \\n littéral"),
            name = PimStructuredName(family = "Dupont;", given = "Jean, fils")
        )
        assertEquals(card, roundTrip(card))
    }

    @Test
    fun `windows line breaks in a note do not break the card`() {
        val decoded = roundTrip(simple("Test", notes = listOf("a\r\nb\rc")))
        assertEquals(listOf("a\nb\nc"), decoded.notes)
        assertEquals("Test", decoded.displayName)
    }

    @Test
    fun `several TYPE parameters from another application are all understood`() {
        val vcard = listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "FN:Jean Test",
            "item1.TEL;type=CELL;type=VOICE;type=pref:+33 6 00 00 00 00",
            "TEL;TYPE=HOME,VOICE:+33 1 00 00 00 00",
            "EMAIL;type=INTERNET;type=WORK:jean@example.org",
            "END:VCARD"
        ).joinToString("\n")
        val decoded = VCardCodec.decode(vcard)
        assertEquals(listOf("mobile", "home"), decoded.typedPhones.map { it.type })
        assertEquals(listOf("work"), decoded.typedEmails.map { it.type })
    }

    @Test
    fun `a custom label with quotes colon and semicolon survives a round trip`() {
        val phones = listOf(PimLabeledValue("+33 1 02 03 04 05", "custom", "Bureau; poste \"2\": étage 3"))
        val decoded = roundTrip(simple("Test", phones = phones))
        assertEquals(phones, decoded.typedPhones)
    }

    @Test
    fun `folded lines are joined`() {
        val decoded = VCardCodec.decode("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Test\r\nNOTE:Bon\r\n jour\r\nEND:VCARD\r\n")
        assertEquals(listOf("Bonjour"), decoded.notes)
    }

    @Test
    fun `a card without a name gets a placeholder`() {
        assertEquals("Contact sans nom", roundTrip(PimContactCard("", emptyList(), emptyList())).displayName)
    }

    @Test
    fun `unknown properties and a photo are ignored without error`() {
        val decoded = VCardCodec.decode(
            "BEGIN:VCARD\nVERSION:3.0\nFN:Test\nX-CUSTOM:foo\nPHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==\n\nEND:VCARD\n"
        )
        assertEquals("Test", decoded.displayName)
    }

    @Test
    fun `early cards with only a display name are still read`() {
        val decoded = VCardCodec.decode("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Test\r\nN:Test;;;;\r\nEND:VCARD\r\n")
        assertEquals("Test", decoded.name.family)
    }

    @Test
    fun `phone order is kept`() {
        val phones = listOf(PimLabeledValue("3", "other"), PimLabeledValue("1", "mobile"), PimLabeledValue("2", "home"))
        assertEquals(phones.map { it.value }, roundTrip(simple("Test", phones = phones)).phones)
    }
}

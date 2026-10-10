package com.jarvis.telefono

/**
 * Quale app si apre quando Jarvis sente un nome.
 *
 * Sta qui, fuori dal servizio di accessibilità, per una ragione pratica: un
 * AccessibilityService non si può creare in una prova sulla JVM, e questa è
 * una decisione che merita di essere provata. Dentro non c'è niente di
 * Android: solo nomi.
 */
object RisoluzioneApp {

    /**
     * Quando un nome indica UNA sola app per forza, qui si dice quale.
     * «posta», «mail» e «email» NON sono qui: aprono l'app di posta predefinita del
     * telefono (AppTelefono.postaPredefinita), qualunque sia la marca. Samsung Email
     * si apre solo se la si chiama per nome o se è scelta nelle impostazioni.
     */
    private val PREFERITE = mapOf(
        "samsung email" to "com.samsung.android.email.provider",
    )

    /** I nomi che valgono l'uno per l'altro. «gmail» non è qui: è un nome suo. */
    val ALIAS = listOf(
        setOf("mail", "email", "posta", "e-mail"),
        setOf("messaggi", "sms", "messages"),
        setOf("calendario", "calendar", "agenda"),
        setOf("telefono", "chiamate", "phone", "dialer"),
        setOf("foto", "galleria", "photos", "gallery"),
        setOf("mappe", "maps", "navigatore"),
    )

    /** Il pacchetto da aprire per questo nome, o null se si cerca per etichetta. */
    fun preferita(nome: String): String? = PREFERITE[nome.trim().lowercase()]

    /** I nomi equivalenti a questo, o il nome stesso se non è in nessun gruppo. */
    fun alias(nome: String): Set<String> {
        val q = nome.trim().lowercase()
        return ALIAS.firstOrNull { it.contains(q) } ?: setOf(q)
    }
}

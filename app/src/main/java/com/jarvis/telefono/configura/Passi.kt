package com.jarvis.telefono.configura

/**
 * La procedura guidata di JBoss (0.4.0, 2026-10-07): i 5 passi approvati da Boss in ASCII.
 * Logica pura (provata in PassiTest): cosa è fatto, cosa è saltato, se la procedura parte da sola.
 */
enum class Passo(val numero: Int, val titolo: String, val chiave: String) {
    BENVENUTO(1, "Benvenuto in JBoss", "benvenuto"),
    MODELLI(2, "Modelli vocali", "modelli"),
    CERVELLO(3, "Cervello", "cervello"),
    POSTA(4, "Account mail", "posta"),
    PROVA(5, "Prova guidata", "prova");

    companion object {
        const val TOTALE = 5
        fun da(chiave: String?): Passo? = values().firstOrNull { it.chiave == chiave }
    }
}

enum class StatoPasso { FATTO, DA_FARE, SALTATO }

/** Com'è il telefono adesso: si rilegge a ogni ritorno sulla schermata (i permessi si tolgono anche da fuori). */
data class Fatti(
    val microfono: Boolean = false,
    val notifiche: Boolean = false,
    val accessibilita: Boolean = false,
    val rubrica: Boolean = false,
    val batteria: Boolean = false,
    val modelliPronti: Boolean = false,
    val vpsPronta: Boolean = false,
    val cervelli: Int = 0,
    val caselle: Int = 0,
    val provaFatta: Boolean = false,
)

enum class ModoAvvio { NUOVA, RIPRESA, NESSUNA }

object Passi {

    /** Il passo è fatto se il telefono è a posto, non perché ci si è passati sopra. */
    fun fatto(p: Passo, f: Fatti): Boolean = when (p) {
        // Rubrica e batteria aiutano ma non bloccano: il passo è verde con i tre permessi che servono davvero.
        Passo.BENVENUTO -> f.microfono && f.notifiche && f.accessibilita
        Passo.MODELLI -> f.modelliPronti
        Passo.CERVELLO -> f.vpsPronta || f.cervelli > 0
        Passo.POSTA -> f.caselle > 0
        Passo.PROVA -> f.provaFatta
    }

    fun stato(p: Passo, f: Fatti, saltati: Set<Passo>): StatoPasso = when {
        fatto(p, f) -> StatoPasso.FATTO
        p in saltati -> StatoPasso.SALTATO
        else -> StatoPasso.DA_FARE
    }

    fun quantiFatti(f: Fatti): Int = Passo.values().count { fatto(it, f) }

    /** Il primo passo né fatto né saltato; se sono tutti a posto, l'ultimo (la prova). */
    fun primoDaFare(f: Fatti, saltati: Set<Passo>): Passo =
        Passo.values().firstOrNull { stato(it, f, saltati) == StatoPasso.DA_FARE } ?: Passo.PROVA

    /**
     * Parte da sola al primo avvio?
     * - NUOVA: cassaforte vuota e nessun passo mai completato (telefono nuovo, app appena ricevuta).
     * - RIPRESA: i dati vengono dalla migrazione di config-boss.json (o del token VPS) e la procedura non è mai
     *   stata chiusa: si riparte con i passi già verdi.
     * - NESSUNA: già chiusa una volta (Fine o «Chiudi»), oppure la cassaforte ha dati non migrati (importati, scritti a mano).
     */
    fun decidiAvvio(chiusa: Boolean, cassaforteVuota: Boolean, passiCompletati: Int, vociMigrate: Int): ModoAvvio = when {
        chiusa -> ModoAvvio.NESSUNA
        vociMigrate > 0 -> ModoAvvio.RIPRESA
        cassaforteVuota && passiCompletati == 0 -> ModoAvvio.NUOVA
        else -> ModoAvvio.NESSUNA
    }

    /** La frase sotto il titolo: chiara anche per chi non sa cosa sia una VPS. */
    fun sottotitolo(modo: ModoAvvio, f: Fatti): String = when (modo) {
        ModoAvvio.RIPRESA -> "Riprendiamo da dove eri: ${quantiFatti(f)} passi su ${Passo.TOTALE} sono già a posto."
        else -> "Cinque passi, tutti saltabili. Li ritrovi in Impostazioni → Configurazione guidata."
    }
}

package com.jarvis.telefono.postino

/**
 * Le frasi sulla posta dette o scritte a JBoss (Boss, 2026-10-09: «tramite JBoss devo poter leggere, cancellare e
 * aggiornare le mail, e procedere mail per mail»). Codice puro, provato sulla JVM.
 *
 * Prima queste frasi non arrivavano al Postino: «leggi le mail» non è una regola del telefono, quindi andava al cervello
 * della VPS (ponte delle mani, un'altra socket) che rispondeva con la SUA lettura della posta, numeri diversi da quelli
 * della pagina Postino. Adesso diventano comandi del canale unico del Postino ([PostaCondivisa]).
 *
 * Le frasi corte («avanti», «cancella questa», «aggiorna», «ripeti») valgono solo se [inPosta]: Boss sta già
 * scorrendo le mail con JBoss (una mail corrente da poco). Fuori da lì restano a JBoss.
 */
object ComandiPostaJBoss {

    sealed class Comando {
        /** «leggi le mail», «ci sono mail nuove?»: la mail corrente (o la prima da fare); senza resoconto lo chiede. */
        object Leggi : Comando()
        /** «aggiorna la posta»: un resoconto nuovo dalla VPS (rilegge le caselle). */
        object Aggiorna : Comando()
        object Avanti : Comando()
        object Indietro : Comando()
        /** «ripeti», «leggimela tutta»: la mail corrente per intero (la scheda della VPS). */
        object Rileggi : Comando()
        /** «leggi la mail 5», «apri la 5». */
        data class Apri(val numero: Int) : Comando()
        /** «cancella questa», «cancella la mail 5»: SEMPRE con la conferma nel filo. null = la corrente. */
        data class Cancella(val numero: Int?) : Comando()
        /** 09/10 (Boss: «gestire più mail insieme»): «cancella le mail 3, 5 e 7». Anche questo SOLO con la conferma. */
        data class CancellaPiu(val numeri: List<Int>) : Comando()
    }

    private fun pulito(t: String): String = ComandiPostino.senzaAccenti(t.lowercase())
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("^\\s*(hey |ehi |ok )?(j ?boss|jarvis|postino)\\b"), " ")
        .replace(Regex("\\b(per favore|grazie|adesso|ora)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private const val POSTA = "(mail|email|e mail|posta|pec)"

    private val LEGGI = listOf(
        Regex("^(leggi|leggimi|leggere|fammi leggere|mostrami|fammi sentire) (tutte )?(le |la )?(mie )?(nuove |ultime )?$POSTA( nuove| di oggi| arrivate)?$"),
        Regex("^(che|quali) $POSTA (ho|sono arrivate|mi sono arrivate|ci sono)$"),
        Regex("^(ci sono|ho|e arrivata|sono arrivate) (delle |altre )?(nuove )?$POSTA( nuove)?$"),
        Regex("^(controlla|guarda|apri) (la |le )?(mia |mie )?$POSTA$"),
        Regex("^(novita|cosa c e) (nella|in) posta$"),
        Regex("^procediamo con le mail$|^andiamo con le mail$|^passiamo alle mail$"),
    )
    private val AGGIORNA = Regex("^(aggiorna|riaggiorna|rinfresca|ricarica|rileggi) (la |le )?$POSTA$|^rifai il giro( della posta)?$|^nuovo giro della posta$")
    private val AGGIORNA_CORTO = Regex("^(aggiorna|aggiornala|rinfresca|ricarica)$")
    /**
     * 09/10 (verificatore sul telefono: «resoconto» nella pagina del Postino diventava «istruisci 1: resoconto», cioè una
     * bozza): il resoconto delle mail da fare. «dimmi le mail» e «resoconto della posta» valgono sempre; «resoconto» e
     * «riassunto» da soli solo quando si sta già parlando di posta (pagina del Postino, conversazione passata a lui).
     */
    private val RESOCONTO = Regex("^dimmi (tutte )?(le |la )?(mie )?(nuove |ultime )?$POSTA( nuove| di oggi| arrivate| da fare)?$|" +
        "^(fammi |dammi |fai )?(il |un )?(resoconto|riassunto)( nuovo)? (della |delle |di )?$POSTA$")
    private val RESOCONTO_CORTO = Regex("^(fammi |dammi |fai )?(il |un )?(resoconto|riassunto)( nuovo)?$")
    private val AVANTI = Regex("^(la )?(prossima|successiva) (mail|email)$|^(leggi|leggimi|passa alla|vai alla) (la )?(prossima|successiva)( mail)?$|^mail successiva$")
    private val AVANTI_CORTO = Regex("^(avanti|vai avanti|prossima|la prossima|successiva|la successiva|next|e poi|altra|un altra|la seguente)$")
    private val INDIETRO = Regex("^(la )?mail precedente$|^(torna alla|leggi la) precedente( mail)?$")
    private val INDIETRO_CORTO = Regex("^(indietro|torna indietro|precedente|la precedente|quella prima)$")
    private val RILEGGI_CORTO = Regex("^(ripeti|rileggi|rileggila|ripetila|leggila|leggimela|leggimela tutta|leggila tutta|leggi tutto|tutta|dimmi tutto|di piu|leggi questa)$")
    private val APRI = Regex("^(leggi|leggimi|apri|aprimi|mostrami|fammi sentire) (la )?(mail|email|numero)( numero)? (\\w+)$")
    private val CANCELLA_NUMERO = Regex("^(cancella|elimina|cestina|butta) (la )?(mail|email)( numero)? (\\w+)$")
    private val CANCELLA_PIU = Regex("^(cancella|elimina|cestina|butta) (le )?(mail|email)( numeri| numero)? (.+)$")
    private val CANCELLA_QUESTA = Regex("^(cancella|elimina|cestina|butta) (questa|questa mail|la mail|questa email)$|^(cancellala|eliminala|cestinala|buttala)$")
    private val CANCELLA_CORTO = Regex("^(cancella|elimina|cestina)$")

    /** La frase → un comando del Postino, o null (non è posta: va avanti il giro normale di JBoss). */
    /**
     * «apri la posta», «apri le mail», «apri email»: chi NON ha la VPS vuole l'app di posta del telefono, non il Postino
     * (che senza VPS risponde solo «non collegato»). Vale su ogni marca: si apre l'app predefinita.
     */
    fun apreLaPosta(frase: String): Boolean =
        Regex("^(apri|aprimi|apra) (la |le |l )?(mia |mie )?(app (della |di )?)?$POSTA$").matches(pulito(frase))

    fun capisci(frase: String, inPosta: Boolean): Comando? {
        val t = pulito(frase)
        if (t.isEmpty() || frase.contains(':')) return null
        numero(APRI, t)?.let { return Comando.Apri(it) }
        numero(CANCELLA_NUMERO, t)?.let { return Comando.Cancella(it) }
        CANCELLA_PIU.find(t)?.let { m ->
            val nn = runCatching { ComandiPostino.estraiNumeri(m.groupValues.last()) }.getOrNull().orEmpty()
            if (nn.size > 1) return Comando.CancellaPiu(nn)
        }
        if (CANCELLA_QUESTA.matches(t)) return Comando.Cancella(null)
        if (AGGIORNA.matches(t) || RESOCONTO.matches(t)) return Comando.Aggiorna
        if (LEGGI.any { it.matches(t) }) return Comando.Leggi
        if (AVANTI.matches(t)) return Comando.Avanti
        if (INDIETRO.matches(t)) return Comando.Indietro
        if (!inPosta) return null
        if (AVANTI_CORTO.matches(t)) return Comando.Avanti
        if (INDIETRO_CORTO.matches(t)) return Comando.Indietro
        if (RILEGGI_CORTO.matches(t)) return Comando.Rileggi
        if (CANCELLA_CORTO.matches(t)) return Comando.Cancella(null)
        if (AGGIORNA_CORTO.matches(t) || RESOCONTO_CORTO.matches(t)) return Comando.Aggiorna
        return null
    }

    private fun numero(re: Regex, t: String): Int? {
        val m = re.find(t) ?: return null
        return runCatching { ComandiPostino.estraiNumeri(m.groupValues.last()) }.getOrNull()?.singleOrNull()
    }
}

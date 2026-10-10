package com.jarvis.telefono

import android.content.Context

/**
 * Il modo tecnico: serve a **noi** per capire cosa non va, non a Jarvis per lavorare.
 *
 * Decisione dell'utente del 20/09/2026. L'app deve vivere dentro il telefono e
 * parlare con Jarvis attraverso la nostra server: quello è il suo mestiere. Il
 * collegamento col computer serve soltanto quando stiamo cercando un guasto —
 * registrare audio, schermate, diagnosi. Due cose diverse che prima erano
 * mescolate, e mescolate portano a credere che l'app funzioni perché funziona
 * il computer. Perciò le diagnosi partono solo con il modo tecnico acceso.
 *
 * **Non si accende dal telefono, e non c'è nessuna password.** Lo comanda la
 * sezione «Tecnico» del Command Center, che scrive qui la preferenza via ADB.
 * Fino alla 0.7.2 c'era un pulsante nell'app con la password dell'utente, e in
 * questo file stava la sua impronta SHA-256 con un sale fisso. Due motivi per
 * toglierla: il pulsante era già codice morto dalla 0.7.1, e un'impronta SHA-256
 * a un giro, con sale noto, di una password scelta da una persona si riapre
 * offline in poco tempo da chiunque legga il repository — cioè pubblicarla
 * equivale a pubblicare la password. Chi accende il modo tecnico deve già avere
 * il computer dell'utente e il debug ADB autorizzato sul telefono: è una porta più
 * stretta di quanto fosse quella password.
 */
object Debug {

    /** Il modo tecnico è acceso su questo telefono? */
    fun acceso(context: Context): Boolean = Prefs.isDebug(context)

    /** Si spegne senza cerimonie: chiudere una porta non deve costare fatica. */
    fun spegni(context: Context) {
        Prefs.setDebug(context, false)
    }
}

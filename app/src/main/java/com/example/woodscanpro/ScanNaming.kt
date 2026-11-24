package com.example.woodscanpro

/** Regole per il codice della scansione, che diventa il nome del tar e dei file che contiene. */
object ScanCode {
    const val MAX_LENGTH = 64
    private val VALID = Regex("[A-Za-z0-9_-]+")

    /** Messaggio di errore, oppure null se il codice è valido. [used] = codici già usati su questo dispositivo. */
    fun error(code: String, used: Collection<String>): String? = when {
        code.isEmpty() -> "Inserisci il codice della scansione"
        !VALID.matches(code) -> "Usa solo lettere (senza accenti), cifre, - e _"
        code.length > MAX_LENGTH -> "Massimo $MAX_LENGTH caratteri"
        used.any { it.equals(code, ignoreCase = true) } -> "Codice già usato: inseriscine un altro"
        else -> null
    }
}

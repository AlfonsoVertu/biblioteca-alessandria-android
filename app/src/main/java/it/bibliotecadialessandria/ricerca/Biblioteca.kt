package it.bibliotecadialessandria.ricerca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * La ricerca nella Biblioteca di Alessandria: l'indice dei video di storia del
 * canale, catalogati a mano dai volontari per tipo, categoria, serie, periodo,
 * zona, personaggi, luoghi e altro.
 *
 * ── NON E' UN'API ───────────────────────────────────────────────────────
 *
 * Il sito non offre un'API: offre una pagina con un modulo, e qui si fa
 * quello che fa il browser. Verificato a mano il 21/09/2026:
 *
 *   1. GET /Search/FiltriSearch  -> la pagina col modulo: dentro ci sono i
 *      valori di tutti i filtri (id e nome), il token antiforgery di
 *      ASP.NET e il cookie di sessione.
 *   2. POST /Search/FiltriSearch -> gli stessi campi del modulo, codificati
 *      come un form; la risposta e' HTML con un blocco per ogni video.
 *
 * I FILTRI NON SONO SCRITTI QUI. Tipi, categorie, 88 serie, periodi, zone e
 * attributi si leggono ogni volta dalla pagina: se domani aggiungono una
 * serie, l'app la vede senza aggiornarsi. Gli id non si deducono dalla
 * posizione (il sesto tipo, Gameplay, vale 9).
 *
 * LE CASELLE COME IN ASP.NET MVC. Ogni filtro e' una coppia
 * «Tipi[0].Selected»: la casella (manda "true" se accesa) e un campo
 * nascosto con lo stesso nome che manda sempre "false". Il server prende il
 * primo valore: si rimandano nell'ordine della pagina, e il risultato e'
 * identico a quello del browser.
 *
 * COSA NON ARRIVA. La risposta dice titolo, durata, tipo, categoria, serie,
 * periodo, zona e il video YouTube. Personaggi, luoghi, anno e tag sono
 * filtri che si possono usare ma non tornano indietro nel risultato; la data
 * e' quasi sempre 01/01/2000, cioe' non compilata.
 *
 * SE IL SITO CAMBIA l'HTML, si aggiorna solo questo file: il resto dell'app
 * vede `Modulo`, `Video` e due funzioni.
 */
object Biblioteca {

    const val SITO = "https://www.bibliotecadialessandria.it"
    private const val PAGINA = "$SITO/Search/FiltriSearch"
    private const val AGENTE =
        "Mozilla/5.0 (Linux; Android) BibliotecaDiAlessandria-App/1.0"

    /** Il modulo si rilegge dopo mezz'ora: token e cookie invecchiano. */
    private const val DURATA_MODULO_MS = 30 * 60 * 1000L

    /** Una casella di un filtro: `campo` e' il nome da rimandare («Tipi[3].Selected»). */
    data class Opzione(val campo: String, val etichetta: String, val predefinita: Boolean)

    data class Gruppo(val titolo: String, val opzioni: List<Opzione>)

    /** Un campo di testo: `campo` e' il nome da rimandare, `valore` quello proposto dal sito. */
    data class Testo(val campo: String, val etichetta: String, val valore: String)

    /** Un campo del modulo, nell'ordine della pagina. */
    internal data class Campo(val nome: String, val tipo: String, val valore: String)

    class Modulo internal constructor(
        val gruppi: List<Gruppo>,
        /** Descrizione, date, durata, numero, quanti risultati. */
        val testi: List<Testo>,
        /** Gli attributi extra: anno evento, personaggi, luoghi, tag... */
        val attributi: List<Testo>,
        internal val campi: List<Campo>,
        internal val sessione: Connection,
        internal val preso: Long,
    )

    data class Video(
        val youtubeId: String,
        val titolo: String,
        val durata: String,
        val data: String,
        val tipo: String,
        val categoria: String,
        val serie: String,
        val periodo: String,
        val zona: String,
    ) {
        val link: String get() = "https://www.youtube.com/watch?v=$youtubeId"
        val anteprima: String get() = "https://i.ytimg.com/vi/$youtubeId/mqdefault.jpg"

        /** «11' · Aperistoria · Storia · Europa»: solo cio' che c'e'. */
        fun dettagli(): String = listOf(durata, tipo.lowercase().replaceFirstChar { it.uppercase() },
            categoria, serie, periodo, zona)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "-" }
            .joinToString(" · ")
    }

    data class Esito(val video: List<Video>, val totale: Int)

    private var cache: Modulo? = null

    private val NOMI_GRUPPI = linkedMapOf(
        "Tipi" to "Tipo",
        "Categorie" to "Categoria",
        "Periodi" to "Periodo storico",
        "Aree" to "Zona",
        "SottoCategorie" to "Serie",
    )

    /** Come la pagina appena aperta: tutti i tipi e tutte le categorie. */
    private val ACCESI_DI_SERIE = setOf("Tipi", "Categorie")

    private val ETICHETTE_TESTI = linkedMapOf(
        "Descrizione" to "Titolo contiene",
        "DataInizio" to "Dal (gg/mm/aaaa)",
        "DataFine" to "Al (gg/mm/aaaa)",
        "LunghezzaDa" to "Durata da (minuti)",
        "LunghezzaA" to "Durata fino a (minuti)",
        "NumeroDa" to "Numero video da",
        "NumeroA" to "Numero video fino a",
        "MaxRisultati" to "Quanti risultati al massimo",
    )

    private fun nuovaSessione(): Connection =
        Jsoup.newSession().userAgent(AGENTE).timeout(25_000).followRedirects(true)

    /** Il modulo con i filtri, riletto se e' vecchio o se `forza`. */
    suspend fun modulo(forza: Boolean = false): Modulo = withContext(Dispatchers.IO) {
        val c = cache
        if (!forza && c != null && System.currentTimeMillis() - c.preso < DURATA_MODULO_MS) {
            return@withContext c
        }
        val sessione = nuovaSessione()
        val doc = sessione.newRequest().url(PAGINA).method(Connection.Method.GET).execute().parse()
        leggiModulo(doc, sessione).also { cache = it }
    }

    private fun leggiModulo(doc: Document, sessione: Connection): Modulo {
        val form = doc.selectFirst("form[action*=FiltriSearch]")
            ?: throw IllegalStateException("La pagina della Biblioteca e' cambiata: non trovo il modulo.")
        val campi = mutableListOf<Campo>()
        val perGruppo = linkedMapOf<String, MutableList<Opzione>>()
        val attributi = mutableListOf<Testo>()
        val testi = mutableListOf<Testo>()

        for (el in form.select("input[name]")) {
            val nome = el.attr("name")
            val tipo = el.attr("type").ifBlank { "text" }.lowercase()
            // i pulsanti «seleziona tutti» e la casella nascosta della pagina
            // non sono dati: il browser li manda ma il server non li legge
            if (nome.startsWith("site-btn-") || nome == "checkDefault") continue
            campi += Campo(nome, tipo, el.attr("value"))

            if (tipo == "checkbox") {
                val prefisso = nome.substringBefore('[')
                val etichetta = el.parent()?.selectFirst("label")?.text()?.trim().orEmpty()
                perGruppo.getOrPut(prefisso) { mutableListOf() } +=
                    Opzione(nome, etichetta.ifBlank { nome }, prefisso in ACCESI_DI_SERIE)
            } else if (tipo == "text") {
                if (nome.startsWith("AttributiExtra[") && nome.endsWith(".ValoreAttributo")) {
                    val i = nome.substringAfter('[').substringBefore(']')
                    val desc = form.selectFirst("input[name=AttributiExtra[$i].DescrizioneAttributo]")
                        ?.attr("value").orEmpty()
                    attributi += Testo(nome, desc.ifBlank { "Attributo $i" }, el.attr("value"))
                } else if (nome in ETICHETTE_TESTI) {
                    testi += Testo(nome, ETICHETTE_TESTI.getValue(nome), el.attr("value"))
                }
            }
        }
        val gruppi = NOMI_GRUPPI.mapNotNull { (prefisso, titolo) ->
            perGruppo[prefisso]?.let { Gruppo(titolo, it) }
        }
        if (gruppi.isEmpty() || testi.none { it.campo == "Descrizione" }) {
            throw IllegalStateException("La pagina della Biblioteca e' cambiata: non trovo i filtri.")
        }
        return Modulo(gruppi, testi.sortedBy { ETICHETTE_TESTI.keys.indexOf(it.campo) },
            attributi, campi, sessione, System.currentTimeMillis())
    }

    /**
     * Cerca.
     *
     * `accese`: i nomi delle caselle accese. `valori`: i campi di testo
     * cambiati (gli altri restano quelli proposti dal sito, date comprese).
     * Se la sessione e' scaduta si rilegge il modulo e si riprova una volta.
     */
    suspend fun cerca(accese: Set<String>, valori: Map<String, String>): Esito =
        withContext(Dispatchers.IO) {
            val primo = modulo()
            runCatching { invia(primo, accese, valori) }.getOrNull()
                ?: invia(modulo(forza = true), accese, valori)
        }

    private fun invia(m: Modulo, accese: Set<String>, valori: Map<String, String>): Esito {
        val dati = mutableListOf<Connection.KeyVal>()
        for (c in m.campi) {
            val valore = when (c.tipo) {
                "checkbox" -> if (c.nome in accese) "true" else continue
                "text" -> valori[c.nome] ?: c.valore
                else -> c.valore
            }
            dati += org.jsoup.helper.HttpConnection.KeyVal.create(c.nome, valore)
        }
        val risposta = m.sessione.newRequest().url(PAGINA)
            .method(Connection.Method.POST)
            .header("Referer", PAGINA)
            .data(dati)
            .execute()
        val doc = risposta.parse()
        val titolo = doc.select("h2").text() + " " + doc.title()
        val totale = Regex("""\((\d+) videos? found\)""").find(titolo)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("Risposta inattesa dalla Biblioteca")
        return Esito(leggiVideo(doc), totale)
    }

    private val ETICHETTE = listOf("Length", "Date", "Open video in YouTube", "Type", "Category",
        "Serie", "Interval", "Zone")

    private fun leggiVideo(doc: Document): List<Video> =
        doc.select(".site-container-horizontal-block").mapNotNull { b ->
            val sorgente = b.selectFirst("iframe[src*=youtube]")?.attr("src")
                ?: b.selectFirst("a[href*=youtube.com/watch]")?.attr("href")
                ?: return@mapNotNull null
            val id = idYoutube(sorgente)
                ?: sorgente.substringAfter("/embed/", "").substringBefore('?').ifBlank { null }
                ?: return@mapNotNull null
            val testo = b.text()
            fun dopo(etichetta: String): String {
                val altre = ETICHETTE.filter { it != etichetta }.joinToString("|") { Regex.escape(it) }
                return Regex(Regex.escape(etichetta) + """:\s*(.*?)\s*(?=(?:$altre)(?::|\s|$)|$)""")
                    .find(testo)?.groupValues?.get(1)?.trim().orEmpty()
            }
            Video(
                youtubeId = id,
                titolo = b.selectFirst("b")?.text()?.trim().orEmpty().ifBlank { "Video senza titolo" },
                durata = dopo("Length"),
                data = dopo("Date").takeIf { it != "01/01/2000" }.orEmpty(),
                tipo = dopo("Type"),
                categoria = dopo("Category"),
                serie = dopo("Serie"),
                periodo = dopo("Interval"),
                zona = dopo("Zone"),
            )
        }

    /** L'id di un video YouTube da un link watch, youtu.be, embed o shorts. */
    fun idYoutube(url: String): String? {
        val m = Regex("""(?:v=|youtu\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})""").find(url)
        return m?.groupValues?.get(1)
    }
}

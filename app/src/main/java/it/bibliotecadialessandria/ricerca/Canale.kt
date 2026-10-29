package it.bibliotecadialessandria.ricerca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Il canale YouTube della Biblioteca, senza chiavi e senza server nostri (29/10/2026, richiesta di Alfonso:
 * «le categorie video, short e live come YouTube e la tab dedicata ai post»).
 *
 * - Video, Short e Live: i flussi RSS pubblici delle tre playlist che YouTube tiene per ogni canale
 *   (UULF = video lunghi, UUSH = short, UULV = dirette), gli ultimi 15 di ciascuno.
 * - Post: la pagina «/posts» del canale, dal JSON `ytInitialData` che la pagina porta con se'.
 *   Non e' un'interfaccia ufficiale: se YouTube cambia la pagina, la scheda mostra un avviso e il link al canale.
 */
object Canale {
    /** L'id del canale (dal link `channel_id` della sua pagina), senza il prefisso «UC». */
    private const val ID = "-Idufifk1hamoBzkZngr1w"
    const val PAGINA = "https://www.youtube.com/@laBibliotecadiAlessandria"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
    private const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"

    enum class Tipo(val nome: String, val playlist: String) {
        VIDEO("Video", "UULF"), SHORT("Short", "UUSH"), LIVE("Live", "UULV")
    }

    data class Post(val id: String, val testo: String, val quando: String, val immagine: String?, val mipiace: String) {
        val link: String get() = "https://www.youtube.com/post/$id"
    }

    /** Gli ultimi video di un tipo, nel formato delle righe della ricerca (stesse miniature e stesso «Condividi»). */
    suspend fun ultimi(tipo: Tipo): List<Biblioteca.Video> = withContext(Dispatchers.IO) {
        val doc = Jsoup.connect("https://www.youtube.com/feeds/videos.xml?playlist_id=${tipo.playlist}$ID")
            .userAgent(UA).timeout(15_000).ignoreContentType(true).parser(Parser.xmlParser()).get()
        doc.select("entry").mapNotNull { e ->
            val id = e.selectFirst("yt|videoId")?.text() ?: e.getElementsByTag("yt:videoId").firstOrNull()?.text()
            val titolo = e.getElementsByTag("title").firstOrNull()?.text().orEmpty()
            val data = e.getElementsByTag("published").firstOrNull()?.text().orEmpty().take(10)
                .split("-").takeIf { it.size == 3 }?.let { (a, m, g) -> "$g/$m/$a" }.orEmpty()
            if (id.isNullOrBlank()) null
            else Biblioteca.Video(id, titolo, "", data, tipo.nome, "", "", "", "")
        }
    }

    /** I post della scheda «Post» del canale (di solito gli ultimi 7-10). */
    suspend fun post(): List<Post> = withContext(Dispatchers.IO) {
        // la pagina per PC: quella per telefono porta ytInitialData come stringa JavaScript, non come JSON;
        // pesa circa 1 MB, oltre il limite predefinito di jsoup
        val html = Jsoup.connect("$PAGINA/posts").userAgent(UA_PC).timeout(20_000).maxBodySize(0)
            .header("Accept-Language", "it-IT,it;q=0.9").cookie("SOCS", "CAI").get().html()
        val inizio = html.indexOf("var ytInitialData = ").takeIf { it >= 0 } ?: return@withContext emptyList()
        val da = inizio + "var ytInitialData = ".length
        val fine = html.indexOf(";</script>", da).takeIf { it > da } ?: return@withContext emptyList()
        val radice = JSONObject(html.substring(da, fine))
        val trovati = mutableListOf<JSONObject>()
        cerca(radice, "backstagePostRenderer", trovati)
        trovati.mapNotNull { p ->
            val id = p.optString("postId").ifBlank { return@mapNotNull null }
            val testo = p.optJSONObject("contentText")?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }.orEmpty()
            val quando = p.optJSONObject("publishedTimeText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            val mipiace = p.optJSONObject("voteCount")?.optString("simpleText").orEmpty()
            val immagine = p.optJSONObject("backstageAttachment")?.let { allegato ->
                val img = allegato.optJSONObject("backstageImageRenderer")?.optJSONObject("image")
                    ?: allegato.optJSONObject("postMultiImageRenderer")?.optJSONArray("images")
                        ?.optJSONObject(0)?.optJSONObject("backstageImageRenderer")?.optJSONObject("image")
                img?.optJSONArray("thumbnails")?.let { t -> t.optJSONObject(t.length() - 1)?.optString("url") }
                    ?: allegato.optJSONObject("videoRenderer")?.optString("videoId")?.takeIf { it.isNotBlank() }
                        ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
            }?.let { if (it.startsWith("//")) "https:$it" else it }
            Post(id, testo, quando, immagine, mipiace)
        }.distinctBy { it.id }
    }

    private fun cerca(nodo: Any?, chiave: String, dove: MutableList<JSONObject>) {
        when (nodo) {
            is JSONObject -> nodo.keys().forEach { k ->
                val v = nodo.opt(k)
                if (k == chiave && v is JSONObject) dove.add(v) else cerca(v, chiave, dove)
            }
            is JSONArray -> for (i in 0 until nodo.length()) cerca(nodo.opt(i), chiave, dove)
        }
    }
}

package it.bibliotecadialessandria.ricerca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * «I miei canali» (10/11/2026, idea di Alfonso): altri canali YouTube da seguire, ognuno con i suoi avvisi.
 * Come per il canale della Biblioteca ([Canale], [Notifiche]) niente chiavi e niente server: i flussi RSS
 * pubblici delle playlist video / short / dirette di ogni canale.
 *
 * Si aggiunge un canale incollando il link del canale o di un suo video, oppure condividendo un video
 * da YouTube con l'app. Tutto resta su questo telefono.
 */
object Seguiti {
    private const val PREF = "seguiti"
    private const val CANALE_ID = "canali_seguiti"
    private const val LAVORO = 7312
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"

    /** Gli intervalli fra cui si sceglie, in minuti. 15 minuti e' il minimo che Android concede. */
    val INTERVALLI = listOf(15 to "15 min", 30 to "30 min", 60 to "1 ora", 180 to "3 ore", 360 to "6 ore", 720 to "12 ore", 1440 to "1 giorno")
    fun nomeIntervallo(min: Int) = INTERVALLI.firstOrNull { it.first == min }?.second ?: "$min min"

    data class Seguito(
        val id: String,            // l'id del canale, «UC...»
        val nome: String,
        val minuti: Int = 60,
        val video: Boolean = true,
        val short: Boolean = true,
        val live: Boolean = true,
        val attivo: Boolean = true,
        val ultimo: Long = 0L,     // quando e' stato controllato l'ultima volta
    ) {
        val link: String get() = "https://www.youtube.com/channel/$id"
        fun tipi() = Canale.Tipo.values().filter { when (it) { Canale.Tipo.VIDEO -> video; Canale.Tipo.SHORT -> short; Canale.Tipo.LIVE -> live } }
        fun descrizione() = if (!attivo) "Avvisi in pausa" else
            "Ogni ${nomeIntervallo(minuti)} · " + (tipi().joinToString(", ") { it.nome.lowercase() }.ifEmpty { "nessun avviso scelto" })
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun tutti(c: Context): List<Seguito> = runCatching {
        val a = JSONArray(p(c).getString("canali", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Seguito(o.getString("id"), o.optString("nome"), o.optInt("min", 60), o.optBoolean("v", true),
                o.optBoolean("s", true), o.optBoolean("l", true), o.optBoolean("on", true), o.optLong("ult", 0L))
        } }
    }.getOrDefault(emptyList())

    fun di(c: Context, id: String) = tutti(c).firstOrNull { it.id == id }

    private fun salva(c: Context, l: List<Seguito>) {
        val a = JSONArray()
        l.forEach { s -> a.put(JSONObject().put("id", s.id).put("nome", s.nome).put("min", s.minuti).put("v", s.video)
            .put("s", s.short).put("l", s.live).put("on", s.attivo).put("ult", s.ultimo)) }
        p(c).edit().putString("canali", a.toString()).apply()
    }

    /** Aggiunge o aggiorna un canale e rimette in calendario il controllo. */
    fun metti(c: Context, s: Seguito) {
        val l = tutti(c).toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l.add(s)
        salva(c, l); programma(c)
    }

    fun togli(c: Context, id: String) {
        salva(c, tutti(c).filter { it.id != id })
        p(c).edit().remove("visti_$id").apply()
        programma(c)
    }

    // ── dal link al canale ───────────────────────────────────────────

    /** Il primo link YouTube dentro un testo (YouTube condivide «titolo + link»). */
    fun linkIn(testo: String?): String? =
        Regex("""https?://(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/\S+""").find(testo.orEmpty())?.value

    /** L'id del video, se il link e' di un video (watch, youtu.be, shorts, live). */
    fun idVideo(link: String): String? {
        val u = Uri.parse(link)
        val h = u.host.orEmpty()
        val seg = u.pathSegments
        return when {
            h.endsWith("youtu.be") -> seg.firstOrNull()
            seg.firstOrNull() == "watch" -> u.getQueryParameter("v")
            seg.firstOrNull() in setOf("shorts", "live", "embed") -> seg.getOrNull(1)
            else -> null
        }?.takeIf { Regex("""[\w-]{11}""").matches(it) }
    }

    data class VideoCondiviso(val id: String, val titolo: String, val canale: String, val linkCanale: String)

    /** Titolo e canale di un video, dall'oEmbed pubblico di YouTube. */
    suspend fun infoVideo(idVideo: String): VideoCondiviso = withContext(Dispatchers.IO) {
        val u = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$idVideo", "UTF-8")
        val o = JSONObject(leggi(u, 200_000))
        VideoCondiviso(idVideo, o.optString("title"), o.optString("author_name"), o.optString("author_url"))
    }

    /**
     * Dal link di un canale (o di un video) al canale da seguire, con il nome preso dal suo flusso RSS.
     * Torna null se il link non porta a un canale.
     */
    suspend fun risolvi(link: String): Seguito? = withContext(Dispatchers.IO) {
        val pulito = link.trim().let { if (it.startsWith("http")) it else "https://www.youtube.com/" + it.removePrefix("/") }
        val paginaCanale = idVideo(pulito)?.let { infoVideo(it).linkCanale } ?: pulito
        val id = Regex("""/channel/(UC[\w-]{22})""").find(paginaCanale)?.groupValues?.get(1)
            ?: idDallaPagina(paginaCanale)
            ?: return@withContext null
        val feed = Jsoup.connect("https://www.youtube.com/feeds/videos.xml?channel_id=$id").userAgent(UA)
            .timeout(15_000).ignoreContentType(true).parser(Parser.xmlParser()).get()
        val nome = feed.getElementsByTag("title").firstOrNull()?.text().orEmpty().ifBlank { "Canale YouTube" }
        Seguito(id, nome)
    }

    /** L'id «UC...» dalla pagina del canale (@nome, /c/, /user/): sta nel link «canonical» in cima alla pagina. */
    private fun idDallaPagina(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "it-IT")
        c.setRequestProperty("Cookie", "SOCS=CAI"); c.connectTimeout = 15_000; c.readTimeout = 20_000
        val cerca = Regex("""(?:canonical" href="https://www\.youtube\.com/channel/|"externalId":"|itemprop="identifier" content=")(UC[\w-]{22})""")
        try {
            c.inputStream.bufferedReader().use { r ->
                // la pagina pesa piu' di 2 MB: si legge a pezzi e ci si ferma appena si trova l'id
                val buf = CharArray(32_768); val coda = StringBuilder(); var letti = 0
                while (letti < 4_000_000) {
                    val n = r.read(buf); if (n < 0) break
                    letti += n; coda.append(buf, 0, n)
                    cerca.find(coda)?.let { return it.groupValues[1] }
                    if (coda.length > 4_000) coda.delete(0, coda.length - 4_000)
                }
            }
        } finally { c.disconnect() }
        return null
    }

    private fun leggi(url: String, max: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15_000; c.readTimeout = 20_000
        try { return c.inputStream.bufferedReader().use { it.readText().take(max) } } finally { c.disconnect() }
    }

    /** Gli ultimi video di un tipo di un canale seguito (stesse righe della ricerca). */
    suspend fun ultimi(s: Seguito, tipo: Canale.Tipo) = Canale.ultimiDi(s.id.removePrefix("UC"), tipo)

    // ── il controllo periodico ───────────────────────────────────────

    /**
     * Un solo lavoro per tutti i canali: gira ogni [minuti] del canale piu' frequente e controlla
     * soltanto quelli il cui intervallo e' passato. Nessun canale attivo: nessun lavoro.
     */
    fun programma(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        val attivi = tutti(c).filter { it.attivo && it.tipi().isNotEmpty() }
        if (attivi.isEmpty()) { js.cancel(LAVORO); return }
        val ogni = attivi.minOf { it.minuti }.coerceAtLeast(15) * 60_000L
        val gia = js.allPendingJobs.firstOrNull { it.id == LAVORO }
        if (gia != null && gia.intervalMillis == ogni) return
        if (gia != null) js.cancel(LAVORO)
        runCatching {
            js.schedule(JobInfo.Builder(LAVORO, ComponentName(c, ControlloSeguiti::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(ogni)
                .setPersisted(true)
                .build())
        }
    }

    /** Ricorda i video che ci sono gia', senza avvisare: si chiama quando si inizia a seguire un canale. */
    suspend fun ricordaEsistenti(c: Context, s: Seguito) {
        val visti = mutableSetOf<String>()
        for (t in Canale.Tipo.values()) runCatching { ultimi(s, t) }.getOrNull()?.forEach { visti.add(it.youtubeId) }
        p(c).edit().putStringSet("visti_${s.id}", visti).apply()
    }

    internal suspend fun controlla(c: Context, forza: Boolean = false) {
        val ora = System.currentTimeMillis()
        for (s in tutti(c)) {
            if (!s.attivo || s.tipi().isEmpty()) continue
            // un minuto di margine: Android sveglia il lavoro un po' prima o un po' dopo
            if (!forza && ora - s.ultimo < s.minuti * 60_000L - 60_000L) continue
            val chiave = "visti_${s.id}"
            val prima = p(c).getStringSet(chiave, null)
            val visti = prima?.toMutableSet() ?: mutableSetOf()
            val nuovi = mutableListOf<Video>()
            var letto = false
            for (t in Canale.Tipo.values()) {
                val lista = runCatching { ultimi(s, t) }.getOrNull() ?: continue
                letto = true
                lista.forEach { v -> if (visti.add(v.youtubeId) && prima != null && t in s.tipi()) nuovi.add(v) }
            }
            if (!letto) continue
            p(c).edit().putStringSet(chiave, visti.toList().takeLast(300).toSet()).apply()
            di(c, s.id)?.let { salva(c, tutti(c).map { x -> if (x.id == s.id) x.copy(ultimo = ora) else x }) }
            if (nuovi.isNotEmpty()) avvisa(c, s, nuovi)
        }
    }

    private fun avvisa(c: Context, s: Seguito, nuovi: List<Video>) {
        creaCanale(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nuovi.take(3).forEach { v ->
            val apri = PendingIntent.getActivity(c, v.youtubeId.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(v.link)), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CANALE_ID)
                .setSmallIcon(R.drawable.ic_notifica)
                .setContentTitle(when (v.tipo) { "Short" -> "Nuovo short"; "Live" -> "Diretta"; else -> "Nuovo video" } + " · " + s.nome)
                .setContentText(v.titolo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(v.titolo))
                .setContentIntent(apri).setAutoCancel(true)
                .build()
            try { nm.notify(v.youtubeId.hashCode(), n) } catch (_: SecurityException) { return }
        }
    }

    private fun creaCanale(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANALE_ID, "I miei canali", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Video, short e dirette dei canali YouTube che segui nell'app"
                })
        }
    }
}

/** Il lavoro periodico dei canali seguiti. */
class ControlloSeguiti : JobService() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(p: JobParameters): Boolean {
        ambito.launch { runCatching { Seguiti.controlla(applicationContext) }; jobFinished(p, false) }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

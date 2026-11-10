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
        val casa: String = "",     // il pacchetto dell'app di cui e' il canale «di casa» ("" = seguito a mano)
        val mod: Long = 0L,        // quando l'utente l'ha cambiato l'ultima volta: fra le app sorelle vince il piu' recente
        val tolto: Boolean = false, // tolto dall'utente: resta segnato cosi', perche' la rimozione arrivi alle sorelle
        val parole: List<String> = emptyList(), // app per argomento: avvisa solo dei titoli con una di queste parole
    ) {
        val link: String get() = "https://www.youtube.com/channel/$id"
        fun tipi() = Canale.Tipo.values().filter { when (it) { Canale.Tipo.VIDEO -> video; Canale.Tipo.SHORT -> short; Canale.Tipo.LIVE -> live } }
        fun descrizione() = if (!attivo) "Avvisi in pausa" else
            "Ogni ${nomeIntervallo(minuti)} · " + (tipi().joinToString(", ") { it.nome.lowercase() }.ifEmpty { "nessun avviso scelto" }) +
                (if (parole.isNotEmpty()) " · solo sull'argomento dell'app" else "")
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun daJson(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i).let { o ->
        Seguito(o.getString("id"), o.optString("nome"), o.optInt("min", 60), o.optBoolean("v", true), o.optBoolean("s", true),
            o.optBoolean("l", true), o.optBoolean("on", true), o.optLong("ult", 0L), o.optString("casa"), o.optLong("mod", 0L),
            o.optBoolean("tolto", false), o.optJSONArray("par")?.let { p -> (0 until p.length()).map { p.getString(it) } }.orEmpty())
    } } }.orEmpty()

    /** Tutti i canali, anche quelli tolti (servono alla famiglia). */
    private fun compresiTolti(c: Context): List<Seguito> =
        runCatching { daJson(JSONArray(p(c).getString("canali", "[]"))) }.getOrDefault(emptyList())

    /** I canali seguiti. */
    fun tutti(c: Context): List<Seguito> = compresiTolti(c).filter { !it.tolto }

    fun di(c: Context, id: String) = tutti(c).firstOrNull { it.id == id }

    private fun inJson(l: List<Seguito>) = JSONArray().apply {
        l.forEach { s -> put(JSONObject().put("id", s.id).put("nome", s.nome).put("min", s.minuti).put("v", s.video)
            .put("s", s.short).put("l", s.live).put("on", s.attivo).put("ult", s.ultimo).put("casa", s.casa)
            .put("mod", s.mod).put("tolto", s.tolto).put("par", JSONArray(s.parole))) }
    }

    /** Salva l'elenco (tolti compresi). `cambio` = l'ha cambiato l'utente: la novita' va alle app sorelle. */
    private fun salva(c: Context, l: List<Seguito>, cambio: Boolean = true) {
        p(c).edit().putString("canali", inJson(l).toString()).commit()
        if (cambio) Famiglia.annuncia(c)
    }

    /** Aggiunge o aggiorna un canale (con la data della modifica) e rimette in calendario il controllo. */
    fun metti(c: Context, s: Seguito) {
        val l = compresiTolti(c).toMutableList()
        val nuovo = s.copy(mod = System.currentTimeMillis(), tolto = false)
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = nuovo else l.add(nuovo)
        salva(c, l); programma(c)
    }

    /** Un canale «di casa» di un'app installata non si toglie: si mette in pausa. */
    fun diCasa(c: Context, s: Seguito) = s.casa.isNotEmpty() && Famiglia.installata(c, s.casa)

    fun togli(c: Context, id: String) {
        di(c, id)?.let { if (diCasa(c, it)) return }
        salva(c, compresiTolti(c).map { if (it.id == id) it.copy(tolto = true, mod = System.currentTimeMillis()) else it })
        programma(c)
    }

    // ── la famiglia: i canali di casa e lo stato condiviso ───────────

    /** I canali di questa app ([Marca.CASA]) stanno sempre fra quelli seguiti, segnati come «di casa». */
    fun assicuraCasa(c: Context) {
        val l = compresiTolti(c).toMutableList(); var cambiato = false
        val ora = System.currentTimeMillis()
        val parole = Catalogo.paroleCasa(c)
        for ((id, nome) in Marca.CASA) {
            val i = l.indexOfFirst { it.id == id }
            if (i < 0 || l[i].tolto) {
                if (i >= 0) l.removeAt(i)
                l.add(Seguito(id, nome, attivo = Notifiche.attiveVecchie(c), casa = c.packageName, mod = ora, parole = parole))
                // i video gia' visti dal vecchio controllo del canale di casa: niente raffica all'aggiornamento
                Notifiche.vistiVecchi(c)?.let { if (Marca.CASA.size == 1) p(c).edit().putStringSet("visti_$id", it).apply() }
                cambiato = true
            } else if (l[i].casa.isEmpty() || !Famiglia.installata(c, l[i].casa)) {
                l[i] = l[i].copy(casa = c.packageName, mod = ora, parole = parole); cambiato = true
            } else if (l[i].casa == c.packageName && l[i].parole != parole) {
                l[i] = l[i].copy(parole = parole, mod = ora); cambiato = true
            }
        }
        if (cambiato) { salva(c, l); programma(c) }
    }

    /** Lo stato da dare alle sorelle: tutti i canali (tolti compresi, con la data della modifica) e i video visti. */
    fun stato(c: Context): JSONObject {
        val l = compresiTolti(c)
        val visti = JSONObject()
        l.forEach { s -> p(c).getStringSet("visti_${s.id}", null)?.let { visti.put(s.id, JSONArray(it.toList())) } }
        return JSONObject().put("canali", inJson(l)).put("visti", visti)
    }

    /**
     * Unisce lo stato di una sorella, canale per canale: vince la versione cambiata piu' di recente dall'utente
     * (anche se e' una rimozione); l'ultimo controllo e' il piu' recente dei due; i video visti si sommano.
     */
    @Synchronized
    fun unisci(c: Context, altro: JSONObject) {
        val mio = compresiTolti(c).associateBy { it.id }
        val loro = daJson(altro.optJSONArray("canali")).associateBy { it.id }
        var cambiato = false
        val unito = (mio.keys + loro.keys).map { id ->
            val m = mio[id]; val l = loro[id]
            val scelto = when {
                m == null -> { cambiato = true; l!! }
                l == null -> m
                l.mod > m.mod -> { cambiato = true; l }
                else -> m
            }
            scelto.copy(ultimo = maxOf(m?.ultimo ?: 0L, l?.ultimo ?: 0L))
        }
        val e = p(c).edit().putString("canali", inJson(unito).toString())
        val visti = altro.optJSONObject("visti")
        if (visti != null) for (s in unito) {
            val suoi = visti.optJSONArray(s.id) ?: continue
            val miei = p(c).getStringSet("visti_${s.id}", null)?.toMutableSet() ?: mutableSetOf()
            for (k in 0 until suoi.length()) miei.add(suoi.getString(k))
            e.putStringSet("visti_${s.id}", miei.toList().takeLast(400).toSet())
        }
        e.commit()
        if (cambiato) programma(c)
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
        // prima si guarda cosa hanno gia' controllato e visto le app sorelle: niente doppi controlli, niente doppi avvisi
        runCatching { assicuraCasa(c); Famiglia.allinea(c) }
        val ora = System.currentTimeMillis()
        var controllati = 0
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
                lista.forEach { v -> if (visti.add(v.youtubeId) && prima != null && t in s.tipi() && pertinente(s, v.titolo)) nuovi.add(v) }
            }
            if (!letto) continue
            p(c).edit().putStringSet(chiave, visti.toList().takeLast(300).toSet()).apply()
            di(c, s.id)?.let { salva(c, tutti(c).map { x -> if (x.id == s.id) x.copy(ultimo = ora) else x }, cambio = false) }
            if (nuovi.isNotEmpty()) avvisa(c, s, nuovi)
            controllati++
        }
        if (controllati > 0) Famiglia.annuncia(c)
    }

    /** Per i canali di un'app per argomento: il titolo deve contenere una delle parole dell'argomento. */
    private fun pertinente(s: Seguito, titolo: String): Boolean {
        if (s.parole.isEmpty()) return true
        val t = " " + titolo.lowercase() + " "
        return s.parole.any { it in t }
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

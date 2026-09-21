package it.bibliotecadialessandria.ricerca

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * App NON UFFICIALE per cercare nell'indice dei video di storia della
 * Biblioteca di Alessandria (bibliotecadialessandria.it).
 *
 * Quattro schede: Cerca (casella + filtri del sito + risultati), Preferiti e
 * Playlist (salvati solo sul telefono, vedi Libreria), Crediti. Il tasto
 * «Condividi» di ogni video offre YouTube, la libreria di Somnio o altre app.
 */
class RicercaActivity : Activity() {

    private enum class Scheda { CERCA, PREFERITI, PLAYLIST, CREDITI }

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var corpo: LinearLayout
    private lateinit var scorrimento: ScrollView

    private var scheda = Scheda.CERCA
    private var playlistAperta: String? = null

    private var testo = ""
    private var modulo: Biblioteca.Modulo? = null
    private var accese: MutableSet<String>? = null
    private val valori = mutableMapOf<String, String>()
    private var trovati: List<Biblioteca.Video> = emptyList()
    private var totale: Int? = null
    private var cercando = false
    private var caricando = false
    private var errore: String? = null
    private var filtriAperti = false

    private val anteprime = HashMap<String, Bitmap?>()

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        scorrimento = ScrollView(this).apply { isFillViewport = true }
        corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scorrimento.addView(corpo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scorrimento)
        disegna()
        caricaFiltri()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    // ── disegno ──────────────────────────────────────────────────────

    private fun disegna() {
        corpo.removeAllViews()
        corpo.addView(testata())

        val dentro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        corpo.addView(dentro)
        dentro.addView(navBar(), sotto(12))

        when (scheda) {
            Scheda.CERCA -> disegnaCerca(dentro)
            Scheda.PREFERITI -> disegnaPreferiti(dentro)
            Scheda.PLAYLIST -> disegnaPlaylist(dentro)
            Scheda.CREDITI -> disegnaCrediti(dentro)
        }
    }

    private fun testata(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.banner_sito)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            contentDescription = "La Biblioteca di Alessandria"
            setOnClickListener { apri(Biblioteca.SITO + "/") }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    private fun navBar(): View {
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun voce(t: String, s: Scheda) {
            val attiva = scheda == s
            val b = Button(this).apply {
                text = t; isAllCaps = false; textSize = 13f; stateListAnimator = null
                setTextColor(colore(if (attiva) R.color.su_oro else R.color.testo))
                background = if (attiva) sfondo(colore(R.color.oro), colore(R.color.oro), 10)
                             else sfondo(colore(R.color.card), colore(R.color.bordo), 10)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { scheda = s; if (s != Scheda.PLAYLIST) playlistAperta = null; disegna() }
            }
            riga.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        voce("Cerca", Scheda.CERCA)
        voce("Preferiti", Scheda.PREFERITI)
        voce("Playlist", Scheda.PLAYLIST)
        voce("Crediti", Scheda.CREDITI)
        return riga
    }

    // ── scheda: cerca ────────────────────────────────────────────────

    private fun disegnaCerca(dentro: LinearLayout) {
        val cerca = card()
        val campo = campo("Cerca un video: Napoleone, Roma, peste...", testo)
        campo.addTextChangedListener(ricorda { testo = it })
        campo.imeOptions = EditorInfo.IME_ACTION_SEARCH
        campo.setOnEditorActionListener { _, azione, ev ->
            val invio = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (azione == EditorInfo.IME_ACTION_SEARCH || invio) { cercaOra(); true } else false
        }
        cerca.addView(campo, sotto(10))
        cerca.addView(bottone(if (cercando) "Cerco..." else "Cerca", pieno = true) { cercaOra() })
        dentro.addView(cerca, sotto(12))

        // filtri: SUBITO sotto la ricerca
        val filtri = card()
        val intestazione = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { filtriAperti = !filtriAperti; disegna() }
        }
        intestazione.addView(etichetta("Filtri"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        intestazione.addView(nota(if (filtriAperti) "Nascondi ▲" else "Mostra ▼"))
        filtri.addView(intestazione)
        if (filtriAperti) disegnaFiltri(filtri)
        dentro.addView(filtri, sotto(12))

        // risultati sotto
        if (errore != null || totale != null || cercando) {
            val ris = card()
            errore?.let { ris.addView(nota(it), sotto(8)) }
            when {
                cercando -> ris.addView(nota("Cerco nella Biblioteca..."))
                totale == 0 -> ris.addView(nota("Nessun video con questi filtri."))
                totale != null -> ris.addView(etichetta(
                    "$totale video trovati" +
                        (if (trovati.size < (totale ?: 0)) " · ne vedi ${trovati.size}" else "")), sotto(8))
            }
            trovati.forEach { ris.addView(rigaVideo(it), sotto(14)) }
            if (trovati.isNotEmpty() && trovati.size < (totale ?: 0)) {
                ris.addView(nota("Per vederne di piu' aumenta \"Quanti risultati al massimo\" nei filtri."))
            }
            dentro.addView(ris, sotto(12))
        }

        dentro.addView(nota("Ricerca nell'indice dei video di bibliotecadialessandria.it · app non ufficiale"))
    }

    private fun disegnaFiltri(filtri: LinearLayout) {
        val m = modulo
        val acc = accese
        if (m == null || acc == null) {
            filtri.addView(nota(if (caricando) "Carico i filtri dal sito..." else "Filtri non disponibili."),
                sopra(10))
            if (!caricando) filtri.addView(bottone("Riprova") { caricaFiltri(forza = true) }, sopra(8))
            return
        }
        m.gruppi.forEach { g ->
            filtri.addView(titoletto(g.titolo), sopra(14))
            val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            riga.addView(bottone("Tutti") { g.opzioni.forEach { acc.add(it.campo) }; disegna() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            riga.addView(bottone("Nessuno") { g.opzioni.forEach { acc.remove(it.campo) }; disegna() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
            filtri.addView(riga, sopra(6))
            val caselle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            g.opzioni.chunked(2).forEach { coppia ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                coppia.forEach { o ->
                    r.addView(casella(o.etichetta, o.campo in acc) { on ->
                        if (on) acc.add(o.campo) else acc.remove(o.campo)
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                if (coppia.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
                caselle.addView(r)
            }
            if (g.opzioni.size > 16) {
                val finestra = ScrollView(this).apply {
                    addView(caselle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
                }
                filtri.addView(finestra, LinearLayout.LayoutParams(MATCH_PARENT, dp(260)).apply { topMargin = dp(6) })
            } else {
                filtri.addView(caselle, sopra(6))
            }
        }
        filtri.addView(titoletto("Personaggi, luoghi e altro"), sopra(14))
        m.attributi.forEach { filtri.addView(campoFiltro(it), sopra(8)) }
        filtri.addView(titoletto("Date, durata, quantita'"), sopra(14))
        m.testi.filter { it.campo != "Descrizione" }.forEach { filtri.addView(campoFiltro(it), sopra(8)) }
        filtri.addView(bottone(if (cercando) "Cerco..." else "Cerca con questi filtri", pieno = true) {
            cercaOra()
        }, sopra(14))
        filtri.addView(bottone("Rimetti i filtri come all'inizio") {
            accese = predefinite(m); valori.clear(); disegna()
        }, sopra(8))
    }

    // ── scheda: preferiti ────────────────────────────────────────────

    private fun disegnaPreferiti(dentro: LinearLayout) {
        val lista = Libreria.preferiti(this)
        val card = card()
        card.addView(etichetta("I tuoi preferiti"), sotto(8))
        if (lista.isEmpty()) {
            card.addView(nota("Ancora nessun preferito. Tocca la stella ☆ su un video per salvarlo qui."))
        } else {
            card.addView(nota("${lista.size} video · salvati su questo telefono"), sotto(6))
            lista.forEach { card.addView(rigaVideo(it), sotto(14)) }
        }
        dentro.addView(card, sotto(12))
    }

    // ── scheda: playlist ─────────────────────────────────────────────

    private fun disegnaPlaylist(dentro: LinearLayout) {
        val aperta = playlistAperta
        if (aperta != null) { disegnaPlaylistAperta(dentro, aperta); return }

        val card = card()
        card.addView(etichetta("Le tue playlist"), sotto(8))
        val nomi = Libreria.nomiPlaylist(this)
        if (nomi.isEmpty()) {
            card.addView(nota("Nessuna playlist. Creane una qui, oppure con \"+ Playlist\" su un video."), sotto(10))
        } else {
            nomi.forEach { nome ->
                val riga = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = sfondo(colore(R.color.fondo), colore(R.color.bordo), 10)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { playlistAperta = nome; disegna() }
                }
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                box.addView(titoletto(nome))
                box.addView(nota("${Libreria.quanti(this@RicercaActivity, nome)} video"))
                riga.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                riga.addView(nota("Apri ›"))
                card.addView(riga, sotto(8))
            }
        }
        card.addView(bottone("+ Nuova playlist", pieno = true) { chiediNuovaPlaylist(null) }, sopra(6))
        dentro.addView(card, sotto(12))
    }

    private fun disegnaPlaylistAperta(dentro: LinearLayout, nome: String) {
        val card = card()
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { playlistAperta = null; disegna() }
        }
        cap.addView(nota("‹ Playlist"))
        card.addView(cap, sotto(8))
        card.addView(titoletto(nome), sotto(2))
        val video = Libreria.contenuto(this, nome)
        card.addView(nota("${video.size} video · su questo telefono"), sotto(8))
        if (video.isEmpty()) {
            card.addView(nota("Vuota. Aggiungi video con \"+ Playlist\" dalla ricerca o dai preferiti."))
        } else {
            video.forEach { card.addView(rigaVideo(it, inPlaylist = nome), sotto(14)) }
        }
        card.addView(bottone("Elimina questa playlist") { chiediElimina(nome) }, sopra(10))
        dentro.addView(card, sotto(12))
    }

    // ── scheda: crediti ──────────────────────────────────────────────

    private fun disegnaCrediti(dentro: LinearLayout) {
        val card = card()
        // logo della community (l'icona/tempio dell'app)
        card.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            adjustViewBounds = true
            contentDescription = "La Biblioteca di Alessandria"
            setOnClickListener { apri(Biblioteca.SITO + "/") }
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        card.addView(TextView(this).apply {
            text = "La Biblioteca di Alessandria"
            textSize = 19f; gravity = Gravity.CENTER
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(colore(R.color.testo))
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "App non ufficiale — fan della community"
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(colore(R.color.tenue))
            setPadding(0, dp(2), 0, dp(4))
        })

        card.addView(nota("Contenuti, catalogo e video sono della Biblioteca di Alessandria e dei suoi " +
            "volontari. Questa app li rende solo piu' comodi da cercare; non e' affiliata al canale."), sopra(8))

        card.addView(titoletto("La community"), sopra(16))
        card.addView(nota("Il sito e il motore di ricerca dei video di storia del canale."))
        card.addView(bottone("Vai al sito · bibliotecadialessandria.it", pieno = true) {
            apri(Biblioteca.SITO + "/")
        }, sopra(8))

        card.addView(titoletto("Chi ha fatto l'app"), sopra(16))
        card.addView(nota("Sviluppata da WorkingWithWeb come app della community, gratuita e a codice aperto."))
        card.addView(bottone("WorkingWithWeb · workingwithweb.it/webagency", pieno = true) {
            apri("https://workingwithweb.it/webagency")
        }, sopra(8))
        card.addView(bottone("Codice sorgente su GitHub") {
            apri("https://github.com/AlfonsoVertu/biblioteca-alessandria-android")
        }, sopra(8))

        dentro.addView(card, sotto(12))
    }

    // ── riga di un video ─────────────────────────────────────────────

    private fun rigaVideo(v: Biblioteca.Video, inPlaylist: String? = null): View {
        val riga = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val anteprima = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colore(R.color.bordo))
            contentDescription = v.titolo
            setOnClickListener { apri(v.link) }
        }
        riga.addView(anteprima, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)))
        caricaAnteprima(v, anteprima)
        riga.addView(TextView(this).apply {
            text = v.titolo
            textSize = 16f
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(colore(R.color.testo))
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { apri(v.link) }
        })
        val dettagli = v.dettagli()
        if (dettagli.isNotBlank()) riga.addView(nota(dettagli))

        val bottoni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottoni.addView(bottone("▶  Guarda", pieno = true) { apri(v.link) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        bottoni.addView(bottone("Condividi") { condividi(v) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        riga.addView(bottoni, sopra(8))

        val azioni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val preferito = Libreria.ePreferito(this, v.youtubeId)
        azioni.addView(bottone(if (preferito) "★ Nei preferiti" else "☆ Preferito") {
            Libreria.cambiaPreferito(this, v)
            disegna()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        if (inPlaylist == null) {
            azioni.addView(bottone("+ Playlist") { chiediAggiungiAPlaylist(v) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        } else {
            azioni.addView(bottone("Togli dalla playlist") {
                Libreria.rimuoviDa(this, inPlaylist, v.youtubeId); disegna()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        }
        riga.addView(azioni, sopra(6))
        return riga
    }

    // ── dialoghi playlist ────────────────────────────────────────────

    private fun chiediAggiungiAPlaylist(v: Biblioteca.Video) {
        val nomi = Libreria.nomiPlaylist(this)
        val voci = (nomi + "+ Nuova playlist...").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Aggiungi a una playlist")
            .setItems(voci) { _, i ->
                if (i < nomi.size) {
                    Libreria.aggiungiA(this, nomi[i], v)
                    avvisa("Aggiunto a \"${nomi[i]}\".")
                    if (scheda == Scheda.PLAYLIST) disegna()
                } else {
                    chiediNuovaPlaylist(v)
                }
            }
            .show()
    }

    private fun chiediNuovaPlaylist(v: Biblioteca.Video?) {
        val campo = EditText(this).apply {
            hint = "Nome della playlist"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Nuova playlist")
            .setView(campo)
            .setPositiveButton("Crea") { _, _ ->
                val nome = campo.text?.toString()?.trim().orEmpty()
                if (nome.isEmpty()) { avvisa("Serve un nome."); return@setPositiveButton }
                Libreria.creaPlaylist(this, nome)
                if (v != null) { Libreria.aggiungiA(this, nome, v); avvisa("Aggiunto a \"$nome\".") }
                disegna()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun chiediElimina(nome: String) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare \"$nome\"?")
            .setMessage("La playlist sara' rimossa da questo telefono. I video restano sul sito.")
            .setPositiveButton("Elimina") { _, _ ->
                Libreria.eliminaPlaylist(this, nome); playlistAperta = null; disegna()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ── azioni ───────────────────────────────────────────────────────

    private val SOMNIO = "eu.workingwithweb.somnio"

    private fun somnioInstallata(): Boolean = runCatching {
        packageManager.getPackageInfo(SOMNIO, 0); true
    }.getOrDefault(false)

    private fun condividi(v: Biblioteca.Video) {
        val somnio = somnioInstallata()
        val voci = mutableListOf("Guarda su YouTube")
        voci += if (somnio) "Aggiungi alla libreria Somnio" else "Aggiungi alla libreria Somnio (non installata)"
        voci += "Invia il link a un'altra app..."
        AlertDialog.Builder(this)
            .setTitle(v.titolo)
            .setItems(voci.toTypedArray()) { _, i ->
                when (i) {
                    0 -> apri(v.link)
                    1 -> if (somnio) mandaASomnio(v) else
                        avvisa("Somnio non e' installata su questo telefono.")
                    else -> startActivity(Intent.createChooser(testoCondiviso(v), "Condividi il video"))
                }
            }
            .show()
    }

    private fun testoCondiviso(v: Biblioteca.Video) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, v.titolo)
        putExtra(Intent.EXTRA_TEXT, v.link)
    }

    private fun mandaASomnio(v: Biblioteca.Video) {
        runCatching { startActivity(testoCondiviso(v).setPackage(SOMNIO)) }
            .onFailure { avvisa("Somnio non ha accettato il link.") }
    }

    private fun apri(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { avvisa("Nessuna app per aprire il link.") }
    }

    private fun cercaOra() {
        if (cercando) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(corpo.windowToken, 0)
        cercando = true
        errore = null
        disegna()
        ambito.launch {
            runCatching {
                val m = modulo ?: Biblioteca.modulo().also { modulo = it }
                val scelte = accese ?: predefinite(m).also { accese = it }
                Biblioteca.cerca(scelte, valori + ("Descrizione" to testo.trim()))
            }.onSuccess {
                trovati = it.video; totale = it.totale
            }.onFailure {
                trovati = emptyList(); totale = null
                errore = "La Biblioteca non risponde. Controlla la connessione e riprova."
            }
            cercando = false
            disegna()
            scorrimento.post { scorrimento.smoothScrollTo(0, 0) }
        }
    }

    private fun caricaFiltri(forza: Boolean = false) {
        if (caricando) return
        caricando = true
        disegna()
        ambito.launch {
            runCatching { Biblioteca.modulo(forza) }
                .onSuccess { m -> modulo = m; if (accese == null) accese = predefinite(m) }
            caricando = false
            disegna()
        }
    }

    private fun predefinite(m: Biblioteca.Modulo) =
        m.gruppi.flatMap { it.opzioni }.filter { it.predefinita }.map { it.campo }.toMutableSet()

    private fun caricaAnteprima(v: Biblioteca.Video, dove: ImageView) {
        if (anteprime.containsKey(v.youtubeId)) { anteprime[v.youtubeId]?.let(dove::setImageBitmap); return }
        ambito.launch {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(v.anteprima).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
                }.getOrNull()
            }
            anteprime[v.youtubeId] = b
            b?.let(dove::setImageBitmap)
        }
    }

    // ── mattoncini dell'interfaccia ──────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun colore(id: Int) = ContextCompat.getColor(this, id)
    private fun sotto(m: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(m) }
    private fun sopra(m: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(m) }

    private fun sfondo(pieno: Int, bordo: Int, raggio: Int = 14) = GradientDrawable().apply {
        setColor(pieno); cornerRadius = dp(raggio).toFloat(); setStroke(dp(1), bordo)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = sfondo(colore(R.color.card), colore(R.color.bordo))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun etichetta(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.12f
        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(colore(R.color.oro))
    }

    private fun titoletto(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextColor(colore(R.color.testo))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(colore(R.color.tenue))
        setPadding(0, dp(2), 0, 0)
    }

    private fun campo(suggerimento: String, valore: String) = EditText(this).apply {
        hint = suggerimento; setText(valore); isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT; textSize = 16f
        setTextColor(colore(R.color.testo)); setHintTextColor(colore(R.color.tenue))
        background = sfondo(colore(R.color.fondo), colore(R.color.bordo), 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun campoFiltro(t: Biblioteca.Testo): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(nota(t.etichetta))
        val e = campo(t.etichetta, valori[t.campo] ?: t.valore)
        e.addTextChangedListener(ricorda { valori[t.campo] = it })
        box.addView(e)
        return box
    }

    private fun bottone(t: String, pieno: Boolean = false, vai: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; stateListAnimator = null
        setTextColor(colore(if (pieno) R.color.su_oro else R.color.testo))
        background = if (pieno) sfondo(colore(R.color.oro), colore(R.color.oro), 10)
                     else sfondo(colore(R.color.card), colore(R.color.oro), 10)
        setOnClickListener { vai() }
    }

    private fun casella(t: String, on: Boolean, cambia: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; textSize = 13f; isChecked = on
        setTextColor(colore(R.color.testo))
        buttonTintList = ColorStateList.valueOf(colore(R.color.oro))
        setOnCheckedChangeListener { _, v -> cambia(v) }
    }

    private fun ricorda(salva: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun afterTextChanged(s: Editable?) { salva(s?.toString().orEmpty()) }
    }

    private fun avvisa(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}

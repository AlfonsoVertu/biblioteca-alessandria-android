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
 * La ricerca della Biblioteca di Alessandria, in un'app.
 *
 * Una schermata sola: la casella di ricerca, i risultati con l'anteprima del
 * video, e sotto tutti i filtri del sito (letti ogni volta dal sito, cosi' se
 * aggiungono una serie compare da sola). Niente account, niente dati salvati:
 * l'app fa quello che fa la pagina «Filtri» del sito.
 *
 * Il tasto «Condividi» di ogni video offre: guardarlo su YouTube, aggiungerlo
 * alla libreria di Somnio (se l'app Somnio e' installata: le si passa il link
 * come farebbe il Condividi di YouTube), oppure mandarlo a qualunque altra app.
 */
class RicercaActivity : Activity() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var corpo: LinearLayout
    private lateinit var scorrimento: ScrollView

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

        // ricerca
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

        // risultati
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

        // filtri
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

        dentro.addView(nota("Ricerca nell'indice dei video di bibliotecadialessandria.it"))
    }

    private fun testata(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.banner_sito)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            contentDescription = "La Biblioteca di Alessandria"
            setOnClickListener { apri("https://www.bibliotecadialessandria.it/") }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
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
                // le 88 serie in una finestra che scorre, come sul sito
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

    private fun rigaVideo(v: Biblioteca.Video): View {
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
        return riga
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

    /** Come il Condividi di YouTube verso Somnio: Somnio riceve il link e lo mette in libreria. */
    private fun mandaASomnio(v: Biblioteca.Video) {
        runCatching { startActivity(testoCondiviso(v).setPackage(SOMNIO)) }
            .onFailure { avvisa("Somnio non ha accettato il link.") }
    }

    /** Nell'app YouTube se c'e', altrimenti nel browser: decide Android. */
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

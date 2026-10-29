package it.bibliotecadialessandria.ricerca

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
/** Donazioni allo sviluppatore dell'app (Crediti → Sostieni l'app). */
private const val DONAZIONI = "https://www.paypal.com/biz/profile/alfonsovertucci"
/** Il canale YouTube della Biblioteca (lo stesso link del loro sito). */
private const val CANALE = "https://www.youtube.com/@laBibliotecadiAlessandria"
/** Il gruppo Facebook e l'Instagram della Biblioteca (dalla pagina del loro canale). */
private const val COMMUNITY = "https://www.facebook.com/groups/624562554783646"
private const val INSTAGRAM = "https://www.instagram.com/bibliotecadialessandria/"

class RicercaActivity : Activity() {

    private enum class Scheda { HOME, CERCA, NOVITA, POST, PREFERITI, PLAYLIST, CREDITI }

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var corpo: LinearLayout
    private lateinit var scorrimento: ScrollView

    private var scheda = Scheda.HOME
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

    // (29/10) le novità del canale, come su YouTube: video, short e live, e la scheda dei post
    private var tipoNovita = Canale.Tipo.VIDEO
    private val novita = mutableMapOf<Canale.Tipo, List<Biblioteca.Video>>()
    private val novitaInCarico = mutableSetOf<Canale.Tipo>()
    private var erroreNovita: String? = null
    private var post: List<Canale.Post>? = null
    private var postInCarico = false
    private var errorePost: String? = null

    /** Insieme degli id preferiti, aggiornato una volta per disegno (evita N letture di SharedPreferences). */
    private var idPreferiti: Set<String> = emptySet()

    private val anteprime = HashMap<String, Bitmap?>()

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        scorrimento = ScrollView(this).apply { isFillViewport = true }
        corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scorrimento.addView(corpo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scorrimento)
        disegna()
        caricaFiltri()
        // le notifiche dei video nuovi: il controllo periodico e, da Android 13, il permesso
        Notifiche.programma(this)
        if (Build.VERSION.SDK_INT >= 33 && Notifiche.attive(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Dalle sezioni il tasto indietro torna alla home; dalla home chiude l'app. */
    @Deprecated("Activity semplice senza OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (scheda != Scheda.HOME) { scheda = Scheda.HOME; playlistAperta = null; disegna(); scorrimento.scrollTo(0, 0) }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    // ── disegno ──────────────────────────────────────────────────────

    private fun disegna() {
        idPreferiti = Libreria.idPreferiti(this)
        corpo.removeAllViews()
        // (29/10, Alfonso) sulla home, e in Cerca quando non c'e' nessuna ricerca e i filtri sono chiusi, sotto
        // resta spazio vuoto: li' il banner si allarga invece di lasciare il buco
        val vuota = scheda == Scheda.CERCA && !filtriAperti && totale == null && !cercando && errore == null
        corpo.addView(testata(grande = scheda == Scheda.HOME || vuota))

        val dentro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        corpo.addView(dentro)
        dentro.addView(navBar(), sotto(12))

        when (scheda) {
            Scheda.HOME -> disegnaHome(dentro)
            Scheda.CERCA -> disegnaCerca(dentro)
            Scheda.NOVITA -> disegnaNovita(dentro)
            Scheda.POST -> disegnaPost(dentro)
            Scheda.PREFERITI -> disegnaPreferiti(dentro)
            Scheda.PLAYLIST -> disegnaPlaylist(dentro)
            Scheda.CREDITI -> disegnaCrediti(dentro)
        }
    }

    /** Ridisegna mantenendo la posizione di scorrimento: per i toggle (stella, playlist)
     *  dove ricostruire da capo e saltare in cima sarebbe fastidioso. */
    private fun ridisegna() {
        val y = scorrimento.scrollY
        disegna()
        scorrimento.post { scorrimento.scrollTo(0, y) }
    }

    private fun testata(grande: Boolean = false): View {
        if (!grande) {
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
        // (29/10, «fai qualcosa di moderno») la testata grande: il banner fa da sfondo velato (non si legge più
        // tagliato), sfuma nel fondo dell'app, e sopra ci sono l'icona, il nome e la riga di presentazione
        val eroe = FrameLayout(this)
        eroe.addView(ImageView(this).apply {
            setImageResource(R.drawable.banner_sito)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.16f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        eroe.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, colore(R.color.fondo)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val testi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        testi.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            contentDescription = "La Biblioteca di Alessandria"
            setOnClickListener { apri(Biblioteca.SITO + "/") }
        }, LinearLayout.LayoutParams(dp(112), dp(112)))
        testi.addView(TextView(this).apply {
            text = "La Biblioteca di Alessandria"
            textSize = 24f; gravity = Gravity.CENTER
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(colore(R.color.testo))
            setPadding(0, dp(12), 0, 0)
        })
        testi.addView(TextView(this).apply {
            text = "I video di storia della community, a portata di mano"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(colore(R.color.tenue))
            setPadding(0, dp(4), 0, 0)
        })
        eroe.addView(testi, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        eroe.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(250))
        return eroe
    }

    // ── home: mattonelle per le sezioni, poi le donazioni ───────────

    private fun disegnaHome(dentro: LinearLayout) {
        class Voce(val icona: Int, val nome: String, val sotto: String, val vai: () -> Unit)
        val voci = listOf(
            Voce(R.drawable.ic_novita, "Novità", "Video, short e live") { scheda = Scheda.NOVITA; disegna() },
            Voce(R.drawable.ic_post, "Post", "Dal canale") { scheda = Scheda.POST; disegna() },
            Voce(R.drawable.ic_cerca, "Cerca", "Tutti i filtri del sito") { scheda = Scheda.CERCA; disegna() },
            Voce(R.drawable.ic_preferiti, "Preferiti", "I video salvati") { scheda = Scheda.PREFERITI; disegna() },
            Voce(R.drawable.ic_playlist, "Playlist", "Le tue raccolte") { playlistAperta = null; scheda = Scheda.PLAYLIST; disegna() },
            Voce(R.drawable.ic_canale, "YouTube", "Il canale") { apri(CANALE) },
            Voce(R.drawable.ic_community, "Community", "Il gruppo Facebook") { apri(COMMUNITY) },
            Voce(R.drawable.ic_instagram, "Instagram", "@bibliotecadialessandria") { apri(INSTAGRAM) },
            Voce(R.drawable.ic_sito, "Il sito", "bibliotecadialessandria.it") { apri(Biblioteca.SITO + "/") },
            Voce(R.drawable.ic_crediti, "Crediti", "Chi ha fatto l'app") { scheda = Scheda.CREDITI; disegna() },
        )
        voci.chunked(2).forEach { coppia ->
            val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            coppia.forEachIndexed { i, v ->
                riga.addView(mattonella(v.icona, v.nome, v.sotto, v.vai), LinearLayout.LayoutParams(0, dp(132), 1f).apply {
                    if (i == 0) rightMargin = dp(6) else leftMargin = dp(6)
                })
            }
            dentro.addView(riga, sotto(12))
        }
        dentro.addView(cartaNotifiche(), sotto(12))
        dentro.addView(cartaDona(), sopra(4))
        dentro.addView(nota("App non ufficiale della community · i video sono della Biblioteca di Alessandria").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        })
    }

    /** Mattonella moderna: icona in un cerchio dorato in alto, nome e riga breve in basso, angoli ampi. */
    private fun mattonella(icona: Int, nome: String, sotto: String, vai: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(colore(R.color.card), colore(R.color.fondo))).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), colore(R.color.bordo))
            }
            isClickable = true; isFocusable = true
            foreground = getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { vai() }
            contentDescription = nome
            val cerchio = FrameLayout(this@RicercaActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.argb(46, 212, 164, 82))
                }
            }
            cerchio.addView(ImageView(this@RicercaActivity).apply { setImageResource(icona) },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            addView(cerchio, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(View(this@RicercaActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(TextView(this@RicercaActivity).apply {
                text = nome; textSize = 17f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(colore(R.color.testo))
            })
            addView(TextView(this@RicercaActivity).apply {
                text = sotto; textSize = 12.5f; maxLines = 1
                setTextColor(colore(R.color.tenue))
            })
        }

    /** Le donazioni: una card dorata col cuore e il bottone a pillola. */
    private fun cartaDona(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(70, 212, 164, 82), colore(R.color.card))).apply {
            cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 212, 164, 82))
        }
        val riga = LinearLayout(this@RicercaActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        riga.addView(ImageView(this@RicercaActivity).apply { setImageResource(R.drawable.ic_dona) },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) })
        riga.addView(TextView(this@RicercaActivity).apply {
            text = "Sostieni l'app"; textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(colore(R.color.testo))
        })
        addView(riga)
        addView(TextView(this@RicercaActivity).apply {
            text = "Gratuita e senza pubblicità. Se ti è utile, una donazione aiuta chi l'ha sviluppata " +
                "(Alfonso Vertucci) a mantenerla. Va allo sviluppo dell'app, non alla Biblioteca di Alessandria."
            textSize = 14f; setTextColor(colore(R.color.tenue))
            setPadding(0, dp(8), 0, dp(14))
        })
        addView(Button(this@RicercaActivity).apply {
            text = "Dona con PayPal"; isAllCaps = false; textSize = 16f; stateListAnimator = null
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(colore(R.color.su_oro))
            background = GradientDrawable().apply { setColor(colore(R.color.oro)); cornerRadius = dp(28).toFloat() }
            setOnClickListener { apri(DONAZIONI) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
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
        // (29/10) Preferiti e Playlist restano sulle mattonelle della home: la barra tiene le cinque più usate
        voce("Home", Scheda.HOME)
        voce("Cerca", Scheda.CERCA)
        voce("Novità", Scheda.NOVITA)
        voce("Post", Scheda.POST)
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
                ris.addView(nota("Per vederne di più aumenta \"Quanti risultati al massimo\" nei filtri."))
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
            "volontari. Questa app li rende solo più comodi da cercare; non è affiliata al canale."), sopra(8))

        card.addView(titoletto("La community"), sopra(16))
        card.addView(nota("Il sito e il motore di ricerca dei video di storia del canale."))
        card.addView(bottone("Vai al sito · bibliotecadialessandria.it", pieno = true) {
            apri(Biblioteca.SITO + "/")
        }, sopra(8))
        card.addView(bottone("Il canale YouTube della Biblioteca") { apri(CANALE) }, sopra(8))
        card.addView(bottone("La community su Facebook") { apri(COMMUNITY) }, sopra(8))
        card.addView(bottone("Instagram · @bibliotecadialessandria") { apri(INSTAGRAM) }, sopra(8))

        card.addView(titoletto("Chi ha fatto l'app"), sopra(16))
        card.addView(nota("Sviluppata da WorkingWithWeb come app della community, gratuita e a codice aperto."))
        card.addView(bottone("WorkingWithWeb · workingwithweb.it/webagency", pieno = true) {
            apri("https://workingwithweb.it/webagency")
        }, sopra(8))
        card.addView(bottone("Codice sorgente su GitHub") {
            apri("https://github.com/AlfonsoVertu/biblioteca-alessandria-android")
        }, sopra(8))

        // (29/10/2026, con il permesso di Gioele Sasso della Biblioteca) le donazioni vanno allo sviluppatore,
        // non alla Biblioteca: lo dice il testo, perche' l'app porta il loro nome
        card.addView(titoletto("Sostieni l'app"), sopra(16))
        card.addView(nota("L'app è gratuita e senza pubblicità. Se ti è utile puoi fare una donazione a chi " +
            "l'ha sviluppata (Alfonso Vertucci): va allo sviluppo dell'app, non alla Biblioteca di Alessandria."))
        card.addView(bottone("Dona con PayPal", pieno = true) { apri(DONAZIONI) }, sopra(8))

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
        val preferito = v.youtubeId in idPreferiti
        azioni.addView(bottone(if (preferito) "★ Nei preferiti" else "☆ Preferito") {
            Libreria.cambiaPreferito(this, v)
            ridisegna()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        if (inPlaylist == null) {
            azioni.addView(bottone("+ Playlist") { chiediAggiungiAPlaylist(v) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        } else {
            azioni.addView(bottone("Togli dalla playlist") {
                Libreria.rimuoviDa(this, inPlaylist, v.youtubeId); ridisegna()
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
            .setMessage("La playlist sarà rimossa da questo telefono. I video restano sul sito.")
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
                        avvisa("Somnio non è installata su questo telefono.")
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

    // ── novità: video, short e live del canale ───────────────────────

    private fun disegnaNovita(dentro: LinearLayout) {
        val pillole = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Canale.Tipo.values().forEach { t ->
            val attiva = t == tipoNovita
            pillole.addView(Button(this).apply {
                text = t.nome; isAllCaps = false; textSize = 14f; stateListAnimator = null
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(colore(if (attiva) R.color.su_oro else R.color.testo))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    if (attiva) setColor(colore(R.color.oro)) else { setColor(colore(R.color.card)); setStroke(dp(1), colore(R.color.bordo)) }
                }
                setOnClickListener { tipoNovita = t; disegna(); caricaNovita(t) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        dentro.addView(pillole, sotto(14))
        val lista = novita[tipoNovita]
        val ris = card()
        when {
            lista == null && erroreNovita != null -> {
                ris.addView(nota(erroreNovita!!), sotto(8))
                ris.addView(bottone("Riprova") { erroreNovita = null; caricaNovita(tipoNovita, forza = true) })
            }
            lista == null -> { ris.addView(nota("Carico gli ultimi ${tipoNovita.nome.lowercase()} del canale...")); caricaNovita(tipoNovita) }
            lista.isEmpty() -> ris.addView(nota("Nessun ${tipoNovita.nome.lowercase()} per ora."))
            else -> {
                ris.addView(etichetta("Gli ultimi ${lista.size} · ${tipoNovita.nome}"), sotto(8))
                lista.forEach { ris.addView(rigaVideo(it), sotto(14)) }
            }
        }
        dentro.addView(ris, sotto(12))
        dentro.addView(bottone("Apri il canale su YouTube") { apri(CANALE) })
    }

    private fun caricaNovita(t: Canale.Tipo, forza: Boolean = false) {
        if (t in novitaInCarico || (!forza && novita.containsKey(t))) return
        novitaInCarico.add(t)
        ambito.launch {
            runCatching { Canale.ultimi(t) }
                .onSuccess { novita[t] = it }
                .onFailure { erroreNovita = "Il canale non risponde. Controlla la connessione e riprova." }
            novitaInCarico.remove(t)
            if (scheda == Scheda.NOVITA) ridisegna()
        }
    }

    // ── post del canale ──────────────────────────────────────────────

    private fun disegnaPost(dentro: LinearLayout) {
        val elenco = post
        when {
            elenco == null && errorePost != null -> {
                val c = card(); c.addView(nota(errorePost!!), sotto(8))
                c.addView(bottone("Riprova") { errorePost = null; caricaPost() }); dentro.addView(c, sotto(12))
            }
            elenco == null -> { val c = card(); c.addView(nota("Carico i post del canale...")); dentro.addView(c, sotto(12)); caricaPost() }
            elenco.isEmpty() -> { val c = card(); c.addView(nota("Nessun post da mostrare: guardali sul canale.")); dentro.addView(c, sotto(12)) }
            else -> elenco.forEach { dentro.addView(cartaPost(it), sotto(12)) }
        }
        dentro.addView(bottone("Tutti i post sul canale") { apri(Canale.PAGINA + "/posts") })
    }

    private fun cartaPost(p: Canale.Post): View {
        val c = card()
        val testa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        testa.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_foreground) },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
        val nomi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nomi.addView(TextView(this).apply { text = "La Biblioteca di Alessandria"; textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(colore(R.color.testo)) })
        nomi.addView(TextView(this).apply { text = p.quando; textSize = 12f; setTextColor(colore(R.color.tenue)) })
        testa.addView(nomi)
        c.addView(testa, sotto(10))
        if (p.testo.isNotBlank()) c.addView(TextView(this).apply {
            text = p.testo; textSize = 15f; setTextColor(colore(R.color.testo)); setLineSpacing(0f, 1.15f)
        }, sotto(10))
        p.immagine?.let { url ->
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(colore(R.color.bordo)); contentDescription = "Immagine del post" }
            c.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(220)).apply { bottomMargin = dp(10) })
            caricaImmagine(url, img)
        }
        val piede = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        piede.addView(TextView(this).apply { text = if (p.mipiace.isNotBlank()) "♥ ${p.mipiace}" else ""
            textSize = 13f; setTextColor(colore(R.color.tenue)) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        piede.addView(bottone("Apri il post") { apri(p.link) })
        c.addView(piede)
        return c
    }

    private fun caricaPost() {
        if (postInCarico) return
        postInCarico = true
        ambito.launch {
            runCatching { Canale.post() }
                .onSuccess { post = it }
                .onFailure { errorePost = "Non riesco a leggere i post del canale. Puoi vederli direttamente su YouTube." }
            postInCarico = false
            if (scheda == Scheda.POST) ridisegna()
        }
    }

    private fun caricaImmagine(url: String, dove: ImageView) {
        if (anteprime.containsKey(url)) { anteprime[url]?.let(dove::setImageBitmap); return }
        ambito.launch {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
                }.getOrNull()
            }
            anteprime[url] = b
            b?.let(dove::setImageBitmap)
        }
    }

    /** L'interruttore delle notifiche sulla home. */
    private fun cartaNotifiche(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = sfondo(colore(R.color.card), colore(R.color.bordo), 22)
        addView(ImageView(this@RicercaActivity).apply { setImageResource(R.drawable.ic_campanella) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        val testi = LinearLayout(this@RicercaActivity).apply { orientation = LinearLayout.VERTICAL }
        testi.addView(TextView(this@RicercaActivity).apply { text = "Avvisami dei video nuovi"; textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(colore(R.color.testo)) })
        testi.addView(TextView(this@RicercaActivity).apply { text = "Video, short e dirette del canale"; textSize = 12.5f
            setTextColor(colore(R.color.tenue)) })
        addView(testi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(Switch(this@RicercaActivity).apply {
            isChecked = Notifiche.attive(this@RicercaActivity)
            thumbTintList = ColorStateList.valueOf(colore(R.color.oro))
            setOnCheckedChangeListener { _, on ->
                Notifiche.imposta(this@RicercaActivity, on)
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
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

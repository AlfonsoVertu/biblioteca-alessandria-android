package it.bibliotecadialessandria.ricerca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * La libreria personale dell'app: preferiti e playlist, salvati SOLO su questo
 * telefono (SharedPreferences). Niente account, niente rete: e' roba dell'app,
 * non del sito.
 *
 * Un video si salva per intero - i nove campi che il sito restituisce - cosi'
 * la scheda si ridisegna identica anche offline, senza dover ricercare.
 */
object Libreria {

    private const val PREF = "biblioteca_libreria"
    private const val K_PREF = "preferiti"
    private const val K_PLAY = "playlist"

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun aJson(v: Biblioteca.Video) = JSONObject().apply {
        put("id", v.youtubeId); put("t", v.titolo); put("d", v.durata); put("da", v.data)
        put("ti", v.tipo); put("c", v.categoria); put("s", v.serie); put("p", v.periodo); put("z", v.zona)
    }

    private fun daJson(o: JSONObject) = Biblioteca.Video(
        o.optString("id"), o.optString("t"), o.optString("d"), o.optString("da"),
        o.optString("ti"), o.optString("c"), o.optString("s"), o.optString("p"), o.optString("z"),
    )

    private fun leggiLista(s: String?): MutableList<Biblioteca.Video> {
        val out = mutableListOf<Biblioteca.Video>()
        if (s.isNullOrBlank()) return out
        runCatching {
            val a = JSONArray(s)
            for (i in 0 until a.length()) out += daJson(a.getJSONObject(i))
        }
        return out
    }

    private fun scriviLista(l: List<Biblioteca.Video>): String {
        val a = JSONArray(); l.forEach { a.put(aJson(it)) }; return a.toString()
    }

    // ── preferiti ────────────────────────────────────────────────────

    fun preferiti(c: Context): MutableList<Biblioteca.Video> = leggiLista(p(c).getString(K_PREF, null))

    fun ePreferito(c: Context, id: String): Boolean = preferiti(c).any { it.youtubeId == id }

    /** Solo gli id, letti in un colpo: per marcare la stella su tante righe senza ri-parsare ogni volta. */
    fun idPreferiti(c: Context): Set<String> = preferiti(c).mapTo(HashSet()) { it.youtubeId }

    /** Aggiunge o toglie. Torna true se ORA e' fra i preferiti. */
    fun cambiaPreferito(c: Context, v: Biblioteca.Video): Boolean {
        val l = preferiti(c)
        val cera = l.removeAll { it.youtubeId == v.youtubeId }
        if (!cera) l.add(0, v)
        p(c).edit().putString(K_PREF, scriviLista(l)).apply()
        return !cera
    }

    // ── playlist ─────────────────────────────────────────────────────
    // Salvate come un oggetto { "nome playlist": [ video, video... ] }.

    private fun tutte(c: Context): JSONObject =
        runCatching { JSONObject(p(c).getString(K_PLAY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun salvaTutte(c: Context, o: JSONObject) =
        p(c).edit().putString(K_PLAY, o.toString()).apply()

    fun nomiPlaylist(c: Context): List<String> =
        tutte(c).keys().asSequence().toList().sortedBy { it.lowercase() }

    fun creaPlaylist(c: Context, nome: String) {
        val n = nome.trim(); if (n.isEmpty()) return
        val o = tutte(c); if (!o.has(n)) { o.put(n, JSONArray()); salvaTutte(c, o) }
    }

    fun eliminaPlaylist(c: Context, nome: String) {
        val o = tutte(c); o.remove(nome); salvaTutte(c, o)
    }

    fun contenuto(c: Context, nome: String): MutableList<Biblioteca.Video> {
        val a = tutte(c).optJSONArray(nome) ?: return mutableListOf()
        val out = mutableListOf<Biblioteca.Video>()
        for (i in 0 until a.length()) out += daJson(a.getJSONObject(i))
        return out
    }

    fun quanti(c: Context, nome: String): Int = tutte(c).optJSONArray(nome)?.length() ?: 0

    /** Mette il video in cima alla playlist, senza doppioni. Crea la playlist se manca. */
    fun aggiungiA(c: Context, nome: String, v: Biblioteca.Video) {
        val o = tutte(c)
        val vecchia = o.optJSONArray(nome) ?: JSONArray()
        val nuova = JSONArray()
        nuova.put(aJson(v))
        for (i in 0 until vecchia.length()) {
            val x = vecchia.getJSONObject(i)
            if (x.optString("id") != v.youtubeId) nuova.put(x)
        }
        o.put(nome, nuova); salvaTutte(c, o)
    }

    fun rimuoviDa(c: Context, nome: String, id: String) {
        val o = tutte(c); val a = o.optJSONArray(nome) ?: return
        val nuova = JSONArray()
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i); if (x.optString("id") != id) nuova.put(x)
        }
        o.put(nome, nuova); salvaTutte(c, o)
    }
}

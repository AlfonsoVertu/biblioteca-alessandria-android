package it.bibliotecadialessandria.ricerca

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * L'app avvisa da sola quando c'e' una versione nuova (29/10/2026, Alfonso: «facciamo il servizio di
 * aggiornamento come soway»). Stesso formato di /app/versione/froway di SoWay, ma in un file statico accanto
 * all'APK su mondo (`versione.json`, scritto da `pubblica.sh` insieme al file).
 *
 * PERCHE' NON SI INSTALLA DA SOLA COME FROWAY. Con il permesso REQUEST_INSTALL_PACKAGES la 1.4.3 scaricata
 * dall'app e' stata fermata da Google Play Protect come «App dannosa bloccata» (provato sul tablet il 29/10):
 * un'app nuova, fuori dal Play Store, che scarica e installa APK, per Google e' un «downloader». Quindi niente
 * permesso: «Aggiorna» apre il file nel browser, la stessa installazione con cui la persona ha messo l'app.
 */
object Aggiornamento {

    private const val INDIRIZZO = "https://mondo.workingwithweb.eu/biblioteca-alessandria/versione.json"
    private const val PAGINA = "https://mondo.workingwithweb.eu/biblioteca-alessandria/"
    private const val PREF = "aggiornamento"
    private const val OGNI = 6 * 3600_000L

    data class Nuova(val codice: Int, val nome: String, val url: String, val dimensione: Long, val note: String)

    @Suppress("DEPRECATION")
    private fun codiceInstallato(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    fun nomeInstallato(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    /** La versione pubblicata, se e' piu' nuova di questa. Fuori dal filo dell'interfaccia. */
    private fun cerca(ctx: Context): Nuova? {
        // il parametro che cambia ogni minuto scavalca le cache di proxy e Cloudflare (come in FroWay)
        val c = (URL(INDIRIZZO + "?t=" + System.currentTimeMillis() / 60_000).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; useCaches = false
        }
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val codice = j.optInt("codice")
            val url = j.optString("url")
            if (!j.optBoolean("disponibile") || codice <= codiceInstallato(ctx) ||
                !url.startsWith("https://mondo.workingwithweb.eu/")) return null
            return Nuova(codice, j.optString("nome"), url, j.optLong("dimensione"), j.optString("note"))
        } finally {
            c.disconnect()
        }
    }

    /** Ogni tanto, non a ogni apertura. */
    fun daGuardare(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ora = System.currentTimeMillis()
        if (ora - p.getLong("guardato", 0L) < OGNI) return false
        p.edit().putLong("guardato", ora).apply()
        return true
    }

    private fun rimandata(ctx: Context, codice: Int) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("rimandata", 0) == codice

    private fun rimanda(ctx: Context, codice: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("rimandata", codice).apply()
    }

    /** La domanda, da una schermata. `daSola` = trovato al controllo di routine. */
    fun proponi(a: Activity, daSola: Boolean) {
        val ctx = a.applicationContext
        Thread {
            val n = runCatching { cerca(ctx) }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (n == null) {
                    if (!daSola) Toast.makeText(a, "L'app è già aggiornata.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (daSola && rimandata(ctx, n.codice)) return@runOnUiThread
                val mega = if (n.dimensione > 0) " (%.1f MB)".format(n.dimensione / 1048576.0) else ""
                AlertDialog.Builder(a).setTitle("C'è un aggiornamento")
                    .setMessage("Versione ${n.nome.ifBlank { n.codice.toString() }}$mega." +
                        (if (n.note.isNotBlank()) "\n\n${n.note}" else "") +
                        "\n\nTocca Scarica, poi apri il file e installalo sopra questa: preferiti e playlist restano.")
                    .setNegativeButton("Più tardi") { _, _ -> rimanda(ctx, n.codice) }
                    .setNeutralButton("La pagina") { _, _ -> apri(a, PAGINA) }
                    .setPositiveButton("Scarica") { _, _ -> apri(a, n.url) }
                    .show()
            }
        }.start()
    }

    private fun apri(a: Activity, url: String) {
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(a, "Apri $PAGINA dal browser.", Toast.LENGTH_LONG).show() }
    }
}

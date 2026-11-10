package it.bibliotecadialessandria.ricerca

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * La famiglia delle app dei canali (10/11/2026, Alfonso: «condividendo con le altre app la lista di canali»).
 *
 * Ogni app della famiglia (Biblioteca, Entropy, le app per argomento...) mette a disposizione delle sorelle lo stato
 * di «I miei canali»: i canali seguiti con le loro impostazioni, i video gia' visti e l'ultimo controllo di ogni
 * canale. Lo legge solo chi e' firmato con la stessa chiave (permesso di livello signature), come il login condiviso
 * di Somnio, Creator e Store.
 *
 * Regole, senza un'app «capo»:
 * - l'ELENCO dei canali: vince la copia con la modifica piu' recente (`aggiornato`, cambia solo per mano dell'utente);
 * - i VIDEO VISTI si uniscono, l'ULTIMO CONTROLLO di un canale e' il piu' recente fra tutte le app:
 *   cosi' un'app non ricontrolla un canale appena controllato da un'altra e un video avvisa una volta sola.
 * Le app si trovano con l'azione [AZIONE] (un ricevitore che non fa niente, serve solo a farsi vedere).
 */
object Famiglia {
    const val AZIONE = "eu.workingwithweb.canali.FAMIGLIA"
    private const val PERMESSO = "eu.workingwithweb.canali.permesso.FAMIGLIA"

    /** I pacchetti delle app sorelle installate (esclusa questa). */
    fun sorelle(c: Context): List<String> = runCatching {
        c.packageManager.queryBroadcastReceivers(Intent(AZIONE), 0)
            .map { it.activityInfo.packageName }.filter { it != c.packageName }.distinct()
    }.getOrDefault(emptyList())

    fun installata(c: Context, pacchetto: String) = pacchetto == c.packageName ||
        runCatching { c.packageManager.getPackageInfo(pacchetto, 0); true }.getOrDefault(false)

    private fun uri(pacchetto: String) = Uri.parse("content://$pacchetto.famiglia")

    /** Prende lo stato da tutte le sorelle e lo unisce al proprio. Da chiamare fuori dal thread principale. */
    fun allinea(c: Context) {
        for (p in sorelle(c)) {
            val testo = runCatching { c.contentResolver.call(uri(p), "stato", null, null)?.getString("json") }.getOrNull() ?: continue
            runCatching { Seguiti.unisci(c, JSONObject(testo)) }
        }
    }

    /** Manda il proprio stato a tutte le sorelle (ognuna lo unisce al suo). */
    fun annuncia(c: Context) {
        val app = c.applicationContext
        Thread {
            val stato = Seguiti.stato(app).toString()
            for (p in sorelle(app)) runCatching { app.contentResolver.call(uri(p), "unisci", stato, null) }
        }.start()
    }

    internal fun autorizzato(c: Context) = c.checkCallingPermission(PERMESSO) == PackageManager.PERMISSION_GRANTED
}

/** Lo sportello per le sorelle: «stato» per leggere, «unisci» per mandare il proprio. Solo con la firma comune. */
class FamigliaProvider : ContentProvider() {
    override fun call(metodo: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        // call() non e' coperto dai permessi di lettura/scrittura del provider: il controllo si fa qui
        if (!Famiglia.autorizzato(c)) throw SecurityException("solo per le app della famiglia")
        return when (metodo) {
            "stato" -> Bundle().apply { putString("json", Seguiti.stato(c).toString()) }
            "unisci" -> { arg?.let { runCatching { Seguiti.unisci(c, JSONObject(it)) } }; Bundle() }
            else -> null
        }
    }
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** Non fa niente: c'e' perche' le app sorelle si trovino cercando chi risponde a [Famiglia.AZIONE]. */
class FamigliaRicevitore : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {}
}

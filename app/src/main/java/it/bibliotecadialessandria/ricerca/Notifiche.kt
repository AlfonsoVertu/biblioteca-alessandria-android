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

/**
 * Le notifiche dei video nuovi (29/10/2026): senza server e senza Firebase. Ogni ora circa (fino alla 1.42.42 ogni 3 ore, dalla 2.42.42 ogni ora), quando c'e' rete,
 * Android sveglia [ControlloNovita] che legge i flussi di [Canale] e avvisa dei video, short e live mai visti.
 * Al primo controllo ricorda soltanto cosa c'e' gia' (niente raffica di notifiche all'installazione).
 */
object Notifiche {
    private const val LAVORO = 7311
    private const val PREF = "notifiche"

    /**
     * (10/11/2026) Gli avvisi del canale di casa passano da «I miei canali» ([Seguiti], canali segnati «di casa»):
     * un solo controllo per tutti i canali, condiviso con le app sorelle ([Famiglia]). Qui restano l'interruttore
     * della home e il passaggio dal vecchio controllo (lavoro 7311 e video gia' visti) al nuovo.
     */
    fun attive(c: Context): Boolean {
        val casa = Seguiti.tutti(c).filter { s -> Marca.CASA.any { it.first == s.id } }
        return if (casa.isEmpty()) attiveVecchie(c) else casa.any { it.attivo }
    }

    internal fun attiveVecchie(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("attive", true)
    internal fun vistiVecchi(c: Context): Set<String>? =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet("visti", null)?.takeIf { it.isNotEmpty() }

    fun imposta(c: Context, attive: Boolean) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("attive", attive).apply()
        Seguiti.assicuraCasa(c)
        Seguiti.tutti(c).filter { s -> Marca.CASA.any { it.first == s.id } }.forEach { Seguiti.metti(c, it.copy(attivo = attive)) }
    }

    /** Si chiama a ogni apertura: toglie il vecchio lavoro, mette i canali di casa fra i seguiti e programma il controllo. */
    fun programma(c: Context) {
        runCatching { c.getSystemService(JobScheduler::class.java).cancel(LAVORO) }
        Seguiti.assicuraCasa(c)
        Thread { runCatching { Famiglia.allinea(c); Seguiti.programma(c) } }.start()
        Seguiti.programma(c)
    }

    internal suspend fun controlla(c: Context) {
        // i video nuovi entrano nel catalogo locale (cosi' si trovano subito con «Cerca»), poi il controllo comune
        runCatching { Catalogo.integra(c) }
        Seguiti.controlla(c)
    }
}

/** Il lavoro periodico: un controllo e basta. */
class ControlloNovita : JobService() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(p: JobParameters): Boolean {
        ambito.launch { runCatching { Notifiche.controlla(applicationContext) }; jobFinished(p, false) }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

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
    private const val CANALE_ID = "video_nuovi"
    private const val LAVORO = 7311
    private const val PREF = "notifiche"
    /** Ogni quanto si controlla il canale: un'ora (Alfonso, 09/11/2026). Android puo' spostarlo un po' per la batteria. */
    private const val INTERVALLO = 60 * 60 * 1000L

    fun attive(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("attive", true)

    fun imposta(c: Context, attive: Boolean) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("attive", attive).apply()
        if (attive) programma(c) else c.getSystemService(JobScheduler::class.java).cancel(LAVORO)
    }

    /** Mette in calendario il controllo periodico (idempotente: si chiama a ogni apertura dell'app). */
    fun programma(c: Context) {
        if (!attive(c)) return
        val js = c.getSystemService(JobScheduler::class.java)
        // gia' in calendario con l'intervallo giusto: niente da fare. Con un intervallo vecchio (3 ore, fino alla
        // 1.42.42) lo si toglie e si rimette, altrimenti chi aggiorna resterebbe al controllo di prima.
        val gia = js.allPendingJobs.firstOrNull { it.id == LAVORO }
        if (gia != null && gia.intervalMillis == INTERVALLO) return
        if (gia != null) js.cancel(LAVORO)
        // le notifiche non devono mai far chiudere l'app: se il sistema rifiuta il lavoro, l'app va avanti senza
        runCatching {
            js.schedule(JobInfo.Builder(LAVORO, ComponentName(c, ControlloNovita::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVALLO)
                .setPersisted(true)
                .build())
        }
    }

    internal suspend fun controlla(c: Context) {
        val pref = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val visti = pref.getStringSet("visti", emptySet())!!.toMutableSet()
        val primaVolta = visti.isEmpty()
        val nuovi = mutableListOf<Video>()
        // (10/11) i video nuovi entrano anche nel catalogo locale, cosi' si trovano subito con «Cerca»
        runCatching { Catalogo.integra(c) }
        for (tipo in Canale.Tipo.values()) {
            runCatching { Canale.ultimi(tipo) }.getOrNull()?.forEach { v ->
                if (visti.add(v.youtubeId) && !primaVolta) nuovi.add(v)
            }
        }
        pref.edit().putStringSet("visti", visti.toList().takeLast(300).toSet()).apply()
        if (nuovi.isEmpty() || !attive(c)) return
        creaCanale(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nuovi.take(5).forEachIndexed { i, v ->
            val apri = PendingIntent.getActivity(c, v.youtubeId.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(v.link)), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CANALE_ID)
                .setSmallIcon(R.drawable.ic_notifica)
                .setContentTitle(when (v.tipo) { "Short" -> "Nuovo short"; "Live" -> "Diretta"; else -> "Nuovo video" } +
                    " · ${Marca.NOME}")
                .setContentText(v.titolo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(v.titolo))
                .setContentIntent(apri).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try { nm.notify(1000 + i, n) } catch (_: SecurityException) { return }
        }
    }

    fun creaCanale(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANALE_ID, "Video nuovi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = Marca.AVVISI_NOTA
                })
        }
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

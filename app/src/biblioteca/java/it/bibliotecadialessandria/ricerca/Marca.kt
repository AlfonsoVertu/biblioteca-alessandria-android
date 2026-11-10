package it.bibliotecadialessandria.ricerca

/** Tutto quello che cambia da un canale all'altro, per La Biblioteca di Alessandria. */
object Marca {
    const val NOME = "La Biblioteca di Alessandria"
    /** Tema scuro e chiaro a scelta (scuro di partenza); false = un solo tema, quello dei colori in values. */
    const val DOPPIO_TEMA = true
    const val MOTTO = "I video di storia della community, a portata di mano"
    /** Il canale YouTube e il suo id senza «UC». */
    const val CANALE = "https://www.youtube.com/@laBibliotecadiAlessandria"
    const val CANALE_ID = "-Idufifk1hamoBzkZngr1w"
    const val SITO = "https://www.bibliotecadialessandria.it/"
    /** Il codice pubblico dell'app (licenza: tutti i diritti riservati). */
    const val CODICE = "https://github.com/AlfonsoVertu/biblioteca-alessandria-android"
    /** La cartella su mondo.workingwithweb.eu con l'APK e versione.json. */
    const val MONDO = "biblioteca-alessandria"
    const val CERCA_SOTTO = "Tutti i filtri del sito"
    const val CERCO = "Cerco nella Biblioteca..."
    const val NON_RISPONDE = "La Biblioteca non risponde. Controlla la connessione e riprova."
    const val NOTA_CERCA = "Ricerca nell'indice dei video di bibliotecadialessandria.it · app non ufficiale"
    const val PIEDE = "App non ufficiale della community · i video sono della Biblioteca di Alessandria"
    const val CREDITI = "Contenuti, catalogo e video sono della Biblioteca di Alessandria e dei suoi " +
        "volontari. Questa app li rende solo più comodi da cercare; non è affiliata al canale."
    const val AVVISI_NOTA = "Un avviso quando la Biblioteca pubblica un video, uno short o una diretta"

    /** I link del canale, in quest'ordine in home (dopo le sezioni dell'app) e nei crediti. */
    val LINK = listOf(
        Collegamento(R.drawable.ic_canale, "YouTube", "Il canale", CANALE),
        Collegamento(R.drawable.ic_community, "Community", "Il gruppo Facebook", "https://www.facebook.com/groups/624562554783646"),
        Collegamento(R.drawable.ic_instagram, "Instagram", "@bibliotecadialessandria", "https://www.instagram.com/bibliotecadialessandria/"),
        Collegamento(R.drawable.ic_sito, "Il sito", "bibliotecadialessandria.it", SITO),
    )
}

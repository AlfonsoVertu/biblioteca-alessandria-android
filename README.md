# La Biblioteca di Alessandria — app Android

App Android per cercare i video di storia nell'indice di
[bibliotecadialessandria.it](https://www.bibliotecadialessandria.it/), con tutti i
filtri del sito (tipo, categoria, periodo, zona, serie, personaggi, luoghi, date,
durata).

- Nessun account, nessun dato salvato, nessun server intermedio: l'app legge la
  pagina di ricerca del sito, come fa il browser.
- Ogni video si guarda su YouTube, si condivide con un'altra app, oppure si
  aggiunge alla libreria di [Somnio](https://somnio.workingwithweb.eu) se
  installata.

## Come funziona la ricerca

Il sito non espone un'API: `GET /Search/FiltriSearch` restituisce il modulo con
i filtri (letti ogni volta, cosi' le serie nuove compaiono da sole) e
`POST /Search/FiltriSearch` restituisce l'HTML dei risultati. Tutto il protocollo
sta in `app/src/main/java/it/bibliotecadialessandria/ricerca/Biblioteca.kt`: se
il sito cambia pagina, si aggiorna solo quel file.

## Compilare

Serve l'SDK Android e JDK 17. La firma di rilascio si configura in
`local.properties` (non versionato):

```
sdk.dir=/percorso/android-sdk
firma.keystore=/percorso/chiave.jks
firma.alias=...
firma.password=...
```

L'APK pronto da installare è in `consegna/`.

Senza firma l'APK di rilascio esce non firmato; per provare basta
`./gradlew assembleDebug`.

Il banner e il nome appartengono alla Biblioteca di Alessandria.

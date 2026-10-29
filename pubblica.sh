#!/usr/bin/env bash
# Pubblica una versione nuova dell'app (29/10/2026), come froway-android/pubblica.sh: in un colpo solo
# l APK su mondo, `versione.json` che le app installate leggono per avvisare della versione nuova, la pagina di mondo
# e quella di Somnio sul numero nuovo, e la copia in consegna/. File e versione insieme, apposta: un file
# nuovo con la versione vecchia non lo scarica nessuno, una versione nuova col file vecchio viene rifiutata.
#
# Uso:  ./pubblica.sh "note per chi aggiorna"
# Prima: ./gradlew assembleRelease (con la firma di local.properties) e versionCode/versionName alzati.
set -euo pipefail
cd "$(dirname "$0")"

APK="app/build/outputs/apk/release/app-release.apk"
META="app/build/outputs/apk/release/output-metadata.json"
NOTE="${1:-}"
MONDO="/d/minecraft/mindcraft_prod/vista/public/sito/biblioteca-alessandria"
SOMNIO="M:/somnio"
[ -f "$APK" ] || { echo "manca $APK: compila prima il rilascio"; exit 1; }

# FIRMA v1 + v2: con minSdk 24 Gradle firma solo v2, e su ColorOS (OPPO) l'aggiornamento dall'app la rifiutava
# (FroWay, 06/10/2026). Si rifirma con la stessa chiave; la password passa per una variabile, mai a schermo.
BT=$(ls -d "${ANDROID_HOME:-/d/AndroidSdk}"/build-tools/* | tail -1)
prop() { grep "^$1=" local.properties | head -1 | cut -d= -f2-; }
export FIRMA_PASS="$(prop firma.password)"
"$BT/apksigner.bat" sign --ks "$(prop firma.keystore)" --ks-key-alias "$(prop firma.alias)" \
  --ks-pass env:FIRMA_PASS --key-pass env:FIRMA_PASS --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 21 "$APK"
unset FIRMA_PASS
"$BT/apksigner.bat" verify --verbose --min-sdk-version 21 "$APK" | grep -E "v1 scheme|v2 scheme"

CODICE=$(python -c "import json;print(json.load(open('$META'))['elements'][0]['versionCode'])")
NOME=$(python -c "import json;print(json.load(open('$META'))['elements'][0]['versionName'])")
FILE="La-Biblioteca-di-Alessandria-$NOME.apk"
SHA256=$(sha256sum "$APK" | cut -d' ' -f1)
DIM=$(stat -c %s "$APK")
echo "Biblioteca $NOME (codice $CODICE), sha256 $SHA256"

cp "$APK" "consegna/$FILE"
# la pagina punta alla versione nuova; su mondo resta solo l'APK attuale
VECCHIA=$(grep -o 'La-Biblioteca-di-Alessandria-[0-9.]*\.apk' sito/index.html | head -1 | sed 's/La-Biblioteca-di-Alessandria-//; s/\.apk//')
sed -i "s/$VECCHIA/$NOME/g" sito/index.html
cp "$APK" "$MONDO/$FILE"
cp sito/index.html "$MONDO/index.html"
find "$MONDO" -name 'La-Biblioteca-di-Alessandria-*.apk' ! -name "$FILE" -delete
python - "$MONDO/versione.json" "$CODICE" "$NOME" "$SHA256" "$DIM" "$FILE" "$NOTE" <<'EOF'
import json, sys
uscita, codice, nome, sha, dim, file, note = sys.argv[1:8]
json.dump({'app': 'biblioteca-alessandria', 'disponibile': True, 'codice': int(codice), 'nome': nome,
           'sha256': sha, 'dimensione': int(dim), 'note': note,
           'url': 'https://mondo.workingwithweb.eu/biblioteca-alessandria/' + file},
          open(uscita, 'w', encoding='utf-8'), ensure_ascii=False)
EOF

# la pagina per Somnio (in public/ per il prossimo build, e subito in dist/ senza le vecchie copie compresse)
python sito/per_somnio.py sito/index.html "$SOMNIO/public/biblioteca-alessandria/index.html"
cp "$SOMNIO/public/biblioteca-alessandria/index.html" "$SOMNIO/dist/biblioteca-alessandria/index.html"
rm -f "$SOMNIO/dist/biblioteca-alessandria/index.html.br" "$SOMNIO/dist/biblioteca-alessandria/index.html.gz"

# la prova: quello che le app leggono e il file che scaricano
sleep 2
curl -s -m 30 "https://mondo.workingwithweb.eu/biblioteca-alessandria/versione.json?t=$(date +%s)"; echo
SCARICATA=$(curl -sL -m 120 "https://mondo.workingwithweb.eu/biblioteca-alessandria/$FILE" | sha256sum | cut -d' ' -f1)
[ "$SCARICATA" = "$SHA256" ] && echo "OK: mondo serve la $NOME" || { echo "ATTENZIONE: il file scaricato non coincide"; exit 1; }

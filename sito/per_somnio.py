# (29/10/2026) La pagina dell'app per il sito di Somnio, ricavata da quella di mondo (sito/index.html):
# stessa identità, il file si scarica sempre da mondo e c'è in più la sezione «La storia, prima di dormire».
# Uso: python sito/per_somnio.py sito/index.html M:/somnio/public/biblioteca-alessandria/index.html
import re
import sys

s = open(sys.argv[1], encoding='utf-8').read()

def rep(a, b):
    global s
    assert a in s, a[:70]
    s = s.replace(a, b, 1)

s = re.sub(r'href="(La-Biblioteca-di-Alessandria-[0-9.]+\.apk)" download',
           r'href="https://mondo.workingwithweb.eu/biblioteca-alessandria/\1"', s)
rep("<title>La Biblioteca di Alessandria · l'app</title>", '<title>La Biblioteca di Alessandria · Somnio</title>')
s = s.replace('https://mondo.workingwithweb.eu/biblioteca-alessandria/schermata-home.webp',
              'https://somnio.workingwithweb.eu/biblioteca-alessandria/schermata-home.webp')
rep('''<section>
  <div class="largo">
    <div class="capo sale"><span class="incisa">Cosa fa</span>''', '''<section>
  <div class="largo">
    <div class="dona sale" style="background:linear-gradient(130deg, rgba(120,140,220,.18), var(--lastra) 55%);border-color:rgba(160,175,240,.35)">
      <div class="cuore" style="animation:none;background:rgba(160,175,240,.16)"><svg viewBox="0 0 24 24" aria-hidden="true" style="fill:#c9d2ff"><path d="M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02 -7.51,-0.25 -12.09,-8.43 -8.32,-14.96z"/></svg></div>
      <div><h2>La storia, prima di dormire</h2>
        <p>Apri un video nell'app, tocca «Condividi» e scegli «Aggiungi alla libreria di Somnio». Lo ritrovi nel cuscino:
          parte a volume basso, una campanellina chiede se dormi e, se non rispondi, tutto si spegne da solo.</p></div>
      <a class="btn vetro" href="https://somnio.workingwithweb.eu/">Scopri Somnio</a>
    </div>
  </div>
</section>

<section style="padding-top:0">
  <div class="largo">
    <div class="capo sale"><span class="incisa">Cosa fa</span>''')
open(sys.argv[2], 'w', encoding='utf-8').write(s)
print('pagina per Somnio scritta')

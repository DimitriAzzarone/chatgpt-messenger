# ChatGPT Radio v0.9

Versione telefono con controllo vocale stile WhatsApp.

## Funzioni
- tieni premuto il microfono per parlare
- rilascia per trascrivere e inviare automaticamente
- lettura automatica della risposta tramite TextToSpeech Android
- scelta della voce TTS installata sul telefono tramite pulsante 🔊
- anteprima immediata di ogni voce selezionata
- **🧔 Saggio maschile** usa la voce che hai scelto tu e applica:
  - tono leggermente più grave
  - velocità più lenta e calma
- nessuna scelta automatica di una voce "maschile" non verificata
- se il nome della voce dichiara esplicitamente male/uomo/maschile, viene mostrata prima
- la voce/modalità scelta viene ricordata
- caricamento immagini e file dalla WebView
- download dei file nella cartella Download di Android
- Enter = invia
- Shift+Enter = nuova riga
- nessuna API OpenAI
- nessun server
- nessun Node

## Come scegliere la voce
1. Tocca 🔊
2. Prova le voci dell'elenco: ogni tocco riproduce un'anteprima.
3. Scegli quella che percepisci chiaramente come maschile.
4. Poi seleziona **🧔 Saggio maschile** per renderla più calda, lenta e profonda.

## Nota importante
Android TextToSpeech non espone in modo standard e affidabile il genere di una voce.
Per questo la v0.9 non indovina più automaticamente: usa la voce che scegli tu.

## Nota WebView
L'individuazione del campo di scrittura e delle risposte dipende dalla struttura HTML corrente di ChatGPT Web.

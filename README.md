# ChatGPT Radio v0.8

Versione telefono con controllo vocale stile WhatsApp.

## Funzioni
- tieni premuto il microfono per parlare
- rilascia per trascrivere e inviare automaticamente
- lettura automatica della risposta tramite TextToSpeech Android
- scelta della voce TTS installata sul telefono tramite pulsante 🔊
- modalità **🧔 Saggio – caldo e calmo**
- tutte le altre voci restano disponibili
- la voce/modalità scelta viene ricordata
- caricamento immagini e file dalla WebView
- **download dei file dalla WebView nella cartella Download di Android**
- Enter = invia
- Shift+Enter = nuova riga
- nessuna API OpenAI
- nessun server
- nessun Node

## Nota download
La v0.8 intercetta i normali link di download HTTP/HTTPS della WebView e usa il Download Manager di Android, mantenendo cookie e user-agent della sessione ChatGPT.
Se un sito usa esclusivamente URL temporanei di tipo `blob:`, quel caso può richiedere un adattamento separato.

## Nota TTS
Il timbro reale dipende dalle voci installate nel motore TextToSpeech del dispositivo Android.

## Nota WebView
L'individuazione del campo di scrittura e delle risposte dipende dalla struttura HTML corrente di ChatGPT Web.

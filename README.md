# ChatGPT Radio v0.3

Versione telefono semplificata.

Flusso:
1. premi PARLA
2. Android registra e trascrive
3. il testo viene inserito nella casella di ChatGPT nella WebView
4. viene premuto automaticamente INVIA
5. ChatGPT genera la risposta
6. quando compare "Leggi ad alta voce", l'app prova a premerlo automaticamente

Nessuna API OpenAI.
Nessun server.
Nessun Node.
Il browser serve solo come ponte verso chatgpt.com.

Nota: i selettori JavaScript dipendono dalla struttura corrente della pagina ChatGPT e potrebbero richiedere aggiornamenti se l'interfaccia web cambia.

/* Native launcher for the account's official ChatGPT voice UI. No API tokens. */
(() => {
 'use strict';
 if (location.origin !== 'https://chatgpt.com' || window.__danWebVoice) return;
 let wanted=false, active=false, sawEnd=false, missingSince=0, token='', timer=null;
 let dictationUntil=0, streamGeneration=0;
 const streams=new Set(), peers=new Set();
 const label=b=>(b.getAttribute('aria-label')||b.getAttribute('title')||b.textContent||'').trim().toLowerCase().replace(/\s+/g,' ');
 const visible=b=>b && !b.disabled && b.getAttribute('aria-disabled')!=='true' && b.getClientRects().length>0 && getComputedStyle(b).visibility!=='hidden' && getComputedStyle(b).display!=='none';
 const startLabels=new Set(['start voice mode','use voice mode','voice mode','start voice chat','use voice','enter voice mode','avvia modalità vocale','modalità vocale','usa la voce','avvia chat vocale','avvia conversazione vocale']);
 const isStart=b=>startLabels.has(label(b)) || (!/dictat|dettat|read aloud|leggi ad alta/.test(label(b)) && (/^(?:start|use|enter|open|enable) (?:the )?voice(?: mode| chat| conversation)?$/.test(label(b)) || /^(?:avvia|inizia|apri|attiva|usa) (?:la |una )?(?:modalit[aà] vocale|chat vocale|conversazione vocale|voce)$/.test(label(b)) || ['composer-speech-button','voice-mode-button','composer-voice-button'].includes(b.getAttribute('data-testid'))));
 const endLabels=new Set(['end voice chat','end voice mode','exit voice mode','close voice mode','close voice chat','end conversation','termina chat vocale','termina conversazione vocale','esci dalla modalità vocale','chiudi modalità vocale','termina conversazione']);
 function button(kind) {
  return Array.from(document.querySelectorAll('button,[role=button]')).find(b=>visible(b) && (kind==='start' ? isStart(b) : endLabels.has(label(b)) || ['voice-close-button','close-voice-button','end-voice-button'].includes(b.getAttribute('data-testid'))));
 }
 function notify(state) {try {window.AndroidRadio?.webVoiceState(location.href,token,state);}catch(_){} }
 function release() {
  clearTimeout(timer);timer=null;
  streams.forEach(s=>s.getTracks().forEach(t=>{try{t.stop();}catch(_){}}));streams.clear();
  peers.forEach(p=>{try{p.close();}catch(_){}});peers.clear();
 }
 function stop(report=true) {
  const wasWanted=wanted;wanted=false;active=false;
  dictationUntil=0;streamGeneration++;
  if(wasWanted) {try{button('end')?.click();}catch(_){} }
  release();
  if(wasWanted)document.querySelectorAll('audio').forEach(a=>{try{a.pause();}catch(_){}});
  if(report)notify('closed');
 }
 // Real taps on the website's voice control go through Android permission flow.
 // Programmatic clicks from start() pass through normally, without recursion.
 document.addEventListener('click',event=>{
  if(!event.isTrusted)return;
  const target=event.target?.closest?.('button,[role=button]');
  if(target && visible(target) && /^(?:dictate|start dictation|dettatura|detta|avvia dettatura|dettare)$/i.test(label(target))) {
   dictationUntil=Date.now()+5000;return;
  }
  if(wanted)return;
  if(!target || !visible(target) || !isStart(target) || !window.AndroidRadio?.startWebVoiceFromPage)return;
  // Preserve the trusted click: replacing it with evaluateJavascript().click()
  // loses browser user activation required by voice playback on some WebViews.
  token='page-'+Date.now();wanted=true;active=false;sawEnd=false;missingSince=0;
  window.AndroidRadio.startWebVoiceFromPage(location.href,token);
  watch();
 },true);
 const media=navigator.mediaDevices;
 if(media?.getUserMedia) {
  const original=media.getUserMedia.bind(media);
  media.getUserMedia=async constraints=>{
   // WebView may cache a previously granted origin permission. Gate every new
   // audio stream too, so the website cannot reacquire the microphone after X.
   const pageDictation=!!constraints?.audio && !wanted && Date.now()<=dictationUntil;
   if(constraints?.audio && !wanted && !pageDictation)throw new DOMException('Tocca il microfono per dettare o avvia LIVE','NotAllowedError');
   if(pageDictation)dictationUntil=0;
   const capture=wanted?token:null;
   const generation=streamGeneration;
   let stream;
   try {stream=await original(constraints);}catch(e){if(capture && wanted && capture===token) {notify(e.name==='NotAllowedError'?'capture-denied':e.name==='NotFoundError'?'capture-missing':e.name==='NotReadableError'?'capture-busy':'capture-error');stop(false);}throw e;}
   if(pageDictation) {
    if(generation!==streamGeneration){stream.getTracks().forEach(t=>t.stop());throw new DOMException('Dettatura chiusa','AbortError');}
    streams.add(stream);
   }
   if(capture) {
    if(!wanted || token!==capture){stream.getTracks().forEach(t=>t.stop());throw new DOMException('Sessione vocale chiusa','AbortError');}
    if(stream.getAudioTracks().some(t=>t.readyState==='live')) {
     streams.add(stream);active=true;notify('active');
     stream.getAudioTracks().forEach(t=>t.addEventListener('ended',()=>{if(wanted && !Array.from(streams).some(s=>s.getAudioTracks().some(t=>t.readyState==='live'))){stop();}}));
    }
   }
   return stream;
  };
 }
 if(window.RTCPeerConnection) {
  const Original=window.RTCPeerConnection;
  window.RTCPeerConnection=class extends Original {constructor(...args){super(...args);if(wanted)peers.add(this);}};
 }
 function watch() {
   const poll=()=>{
    if(!wanted)return;
    // The official close button confirms that the account's voice UI opened.
    // Only an actual live microphone stream confirms that capture started.
    if(button('end')) {sawEnd=true;missingSince=0;if(!active)notify('opened');}
    else if(active && sawEnd){if(!missingSince)missingSince=Date.now();else if(Date.now()-missingSince>2000){stop();return;}}
    timer=setTimeout(poll,600);
   };
   timer=setTimeout(poll,600);
 }
 window.__danWebVoice={
  start(nextToken) {
   if(wanted)stop(false);
   token=String(nextToken);
   if(!media?.getUserMedia){notify('unsupported');return 'unsupported';}
   const start=button('start');
   if(!start){notify('unavailable');return 'unavailable';}
   wanted=true;active=false;sawEnd=false;missingSince=0;notify('connecting');
   try {start.click();}catch(_){stop(false);notify('unavailable');return 'unavailable';}
   watch();
   return 'connecting';
  },
  stop:()=>stop(),
  isActive:()=>wanted && active
 };
 window.addEventListener('pagehide',()=>stop());
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
})();

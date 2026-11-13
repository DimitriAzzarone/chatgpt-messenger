(() => {
 const extract = data => {
  if (!data || !data.mapping || !data.current_node) throw Error('Cronologia completa non disponibile');
  const nodes=[],seen=new Set();let id=data.current_node;
  while(id){if(seen.has(id))throw Error('Cronologia non valida');seen.add(id);const n=data.mapping[id];if(!n)throw Error('Cronologia incompleta');nodes.unshift(n);id=n.parent;}
  const entries=nodes.map(n=>n.message).filter(m=>m&&['assistant','user'].includes(m.author?.role)&&(!m.channel||m.channel==='final')&&Array.isArray(m.content?.parts)).map(m=>({role:m.author.role,text:(m.content.parts||[]).filter(p=>typeof p==='string').join('\n').trim()})).filter(m=>m.text);
  if(!entries.length)throw Error('Nessuna risposta testuale trovata');
  return {complete:true,count:entries.length,title:String(data.title||'Diari'),text:entries.map(m=>`${m.role==='user'?'TU':'ASSISTENTE'}\n\n${m.text}`).join('\n\n────────────────────\n\n')};
 };
 window.__diaryExtract=extract;
 window.__diaryStart=async()=>{
  if(location.origin!=='https://chatgpt.com')return;
  window.__diaryResult={pending:true};
  const url=location.href,match=location.pathname.match(/\/c\/([a-zA-Z0-9-]+)/);
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),25000);
  try {
   if(!match)throw Error('Apri prima la chat dei diari');
   const session=await fetch('/api/auth/session',{credentials:'include',signal:controller.signal}).then(r=>{if(!r.ok)throw Error('Accesso non disponibile');return r.json()});
   if(typeof session.accessToken!=='string')throw Error('Accesso non disponibile');
   const data=await fetch('/backend-api/conversation/'+encodeURIComponent(match[1]),{credentials:'include',headers:{Authorization:'Bearer '+session.accessToken},signal:controller.signal}).then(r=>{if(!r.ok)throw Error('Cronologia completa non accessibile');return r.json()});
   if(location.href!==url)throw Error('Chat cambiata: ripeti la lettura');
   const result=extract(data);if(result.text.length>3000000)throw Error('Chat troppo grande: esporta una parte');window.__diaryResult=result;
  } catch(e) {
   if(location.href!==url){window.__diaryResult={error:'Chat cambiata: ripeti la lettura'};return;}
   const messages=[...document.querySelectorAll('[data-message-author-role="assistant"], [data-message-author-role="user"]')].filter(n=>n.innerText.trim()).map(n=>(n.getAttribute('data-message-author-role')==='user'?'TU':'ASSISTENTE')+'\n\n'+n.innerText.trim());
   window.__diaryResult=messages.length?{complete:false,count:messages.length,title:'Diari — estratto parziale',text:messages.join('\n\n────────────────────\n\n'),warning:'Cronologia completa non accessibile. Sono incluse soltanto i messaggi caricati nella pagina; potrebbero mancare diari precedenti.'}:{error:match?'Non riesco a leggere i diari. Attendi il caricamento della chat e riprova.':'Apri prima la chat dei diari.'};
  } finally {clearTimeout(timer);}
 };
})();

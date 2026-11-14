const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const script=fs.readFileSync('app/src/main/assets/dan-web-voice.js','utf8');
function setup({origin='https://chatgpt.com',deferred=false,startLabel='Start Voice mode',failure=null}={}) {
 let wantedButton=true,endedButton=false,resolve,stops=0,paused=0,closes=0;const states=[],timers=new Map();let seq=0;
 const listeners={},track={readyState:'live',stop(){this.readyState='ended';stops++;},addEventListener(name,fn){listeners[name]=fn;}};
 const stream={getAudioTracks:()=>[track],getTracks:()=>[track]};
 const media={getUserMedia:()=>failure?Promise.reject(Object.assign(new Error('capture failed'),{name:failure})):deferred?new Promise(r=>resolve=r):Promise.resolve(stream)};
 const start={disabled:false,getAttribute:k=>k==='aria-label'?startLabel:null,getClientRects:()=>[{}],click(){endedButton=true;}};
 const end={disabled:false,getAttribute:k=>k==='aria-label'?'End voice chat':null,getClientRects:()=>[{}],click(){endedButton=false;}};
 const document={hidden:false,querySelectorAll:s=>(s==='button'||s==='button,[role=button]')?[...(wantedButton?[start]:[]),...(endedButton?[end]:[])]:s==='audio'?[{pause(){paused++;}}]:[],addEventListener:(n,f)=>listeners[n]=f};
 const c={location:{origin,href:origin+'/c/test'},document,navigator:{mediaDevices:media},getComputedStyle:()=>({visibility:'visible',display:'block'}),DOMException,
 setTimeout:f=>{timers.set(++seq,f);return seq;},clearTimeout:n=>timers.delete(n),Date,
 AndroidRadio:{webVoiceState:(url,token,state)=>states.push({url,token,state})},addEventListener:(n,f)=>listeners[n]=f,
 RTCPeerConnection:class {close(){closes++;}}};c.window=c;vm.runInNewContext(script,c);
 return {c,stream,track,states,timers,listeners,get stats(){return {stops,paused,closes}},get resolve(){return resolve},buttons(startOn,endOn){wantedButton=startOn;endedButton=endOn;},poll(){const f=Array.from(timers.values()).at(-1);f?.();}};
}
(async()=>{
 const foreign=setup({origin:'https://chatgpt.com.evil.test'});assert.equal(foreign.c.__danWebVoice,undefined);
 let a=setup();a.buttons(false,false);assert.equal(a.c.__danWebVoice.start('1'),'unavailable');assert.equal(a.stats.stops,0);
 a=setup();assert.equal(a.c.__danWebVoice.start('2'),'connecting');assert.equal(a.c.__danWebVoice.isActive(),false);
 await a.c.navigator.mediaDevices.getUserMedia({audio:true});assert.equal(a.c.__danWebVoice.isActive(),true);
 new a.c.RTCPeerConnection();a.c.__danWebVoice.stop();assert.equal(a.stats.stops,1);assert.equal(a.stats.closes,1);assert.equal(a.stats.paused,1);assert.equal(a.c.__danWebVoice.isActive(),false);
 assert.ok(a.states.some(s=>s.state==='active'&&s.token==='2'));assert.equal(a.states.at(-1).state,'closed');
 await assert.rejects(a.c.navigator.mediaDevices.getUserMedia({audio:true}),e=>e.name==='NotAllowedError');
 a=setup({deferred:true});a.c.__danWebVoice.start('old');const capture=a.c.navigator.mediaDevices.getUserMedia({audio:true});a.c.__danWebVoice.stop();a.resolve(a.stream);
 await assert.rejects(capture,e=>e.name==='AbortError');assert.equal(a.track.readyState,'ended');assert.ok(!a.states.some(s=>s.state==='active'));
 a=setup();a.c.__danWebVoice.start('3');await a.c.navigator.mediaDevices.getUserMedia({audio:true});a.c.document.hidden=true;a.listeners.visibilitychange();assert.equal(a.stats.stops,1);assert.equal(a.c.__danWebVoice.isActive(),false);
 a=setup();a.c.__danWebVoice.start('4');a.poll();assert.ok(a.states.some(s=>s.state==='opened'));assert.ok(!a.states.some(s=>s.state==='active')); // UI opening is not microphone capture.
 a=setup();a.c.__danWebVoice.start('5');await a.c.navigator.mediaDevices.getUserMedia({audio:true});a.track.readyState='ended';a.listeners.ended();assert.equal(a.c.__danWebVoice.isActive(),false);
 for(const name of ['Avvia la modalità vocale','Apri modalità vocale','Open voice mode','Start voice']) {a=setup({startLabel:name});assert.equal(a.c.__danWebVoice.start('labels'),'connecting');a.c.__danWebVoice.stop();}
 a=setup({startLabel:'Dictate'});assert.equal(a.c.__danWebVoice.start('no-dictation'),'unavailable');
 for(const [failure,state] of [['NotAllowedError','capture-denied'],['NotFoundError','capture-missing'],['NotReadableError','capture-busy']]){a=setup({failure});a.c.__danWebVoice.start('failure');await assert.rejects(a.c.navigator.mediaDevices.getUserMedia({audio:true}));assert(a.states.some(s=>s.state===state));assert.equal(a.c.__danWebVoice.isActive(),false);}
 a=setup();let intents=0,prevented=0;a.c.AndroidRadio.startWebVoiceFromPage=()=>intents++;
 const target={disabled:false,getAttribute:k=>k==='aria-label'?'Avvia la modalità vocale':null,getClientRects:()=>[{}]};
 const click={isTrusted:true,target:{closest:()=>target},preventDefault:()=>prevented++,stopImmediatePropagation:()=>{}};
 a.listeners.click(click);assert.equal(intents,1);assert.equal(prevented,1);
 a.listeners.click({...click,isTrusted:false});assert.equal(intents,1);
 a.c.__danWebVoice.start('native');a.listeners.click(click);assert.equal(intents,1); // No interception or recursive launch once native flow is armed.
 const css=fs.readFileSync('app/src/main/assets/dan-hud.css','utf8');assert.ok(css.includes('--text-primary:#e0f4ff!important'));assert.ok(css.includes('-webkit-text-fill-color:#e0f4ff!important'));
 console.log('Web voice: origin, capture confirmation, X cleanup, peer closure, late permission race, background and track-end checks passed.');
})().catch(e=>{console.error(e);process.exitCode=1;});

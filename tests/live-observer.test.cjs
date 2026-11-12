const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
let now = 0, check, streaming = false, users = 1;
let answers = ['Risposta precedente'];
const spoken = [];
const doc = {
  querySelectorAll(selector) {
    if (selector === '*') return [];
    if (selector === '[data-message-author-role=user]') return Array(users).fill({});
    if (selector.includes('[data-message-author-role=assistant]')) return answers.map(node);
    return [];
  },
  querySelector() { return streaming ? {} : null; },
  defaultView: {getComputedStyle: () => ({display:'block',visibility:'visible'})},
  documentElement: {}
};
function node(text) {
  return {innerText:text,textContent:text,nodeType:1,ownerDocument:doc,
    closest:()=>null,getClientRects:()=>[{}],querySelectorAll:()=>[]};
}
const context = {document:doc,location:{href:'https://chatgpt.com/c/test'},
  Date:{now:()=>now},Set,Array,
  MutationObserver:class {observe(){}},
  setTimeout:()=>1,clearTimeout:()=>{},setInterval:fn=>{check=fn;},
  AndroidRadio:{assistantReadyForPage:(url,text)=>spoken.push(text)}};
context.window = context;
vm.runInNewContext(fs.readFileSync('/tmp/dan146-observer.js','utf8'), context);
function tick(ms=2000) { now += ms; check(); }
tick(); tick();
assert.equal(spoken.length,0,'opening an existing chat must not read its old answer');
context.__danLivePending={users:1,answers:1};
tick(); tick();
assert.equal(spoken.length,0,'a stable old answer cannot complete a newly sent live turn');
users=2;
tick();tick();
assert.equal(spoken.length,0,'the user turn alone does not complete the answer');
answers.push('Nuova risposta');streaming=true;
tick();tick();
assert.equal(spoken.length,0,'streaming content must not be spoken');
streaming=false;tick();
assert.equal(spoken.length,0,'wait until answer text is stable');
tick();assert.deepEqual(spoken,['Nuova risposta']);
tick();tick();assert.equal(spoken.length,1,'a completed answer must only be read once');
context.__danLivePending={users:2,answers:2};users=3;answers.push('Nuova risposta');
tick();tick();assert.equal(spoken.length,2,'identical replies in different turns must both be read');
context.__danLivePending={users:3,answers:3};users=4;answers.push('');
tick();tick();assert.equal(spoken.length,2,'empty responses must not complete a live turn');
console.log('Live observer checks passed');

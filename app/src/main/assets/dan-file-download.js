/* Keep short-lived Blob references so Android's delayed download callback can read
 * an object URL even after the page revokes it. No network traffic until save(). */
(() => {
 'use strict';
 if(window.__danFileDownloads)return;
 const blobs=new Map();
 const TTL=120000,MAX_BYTES=128*1024*1024,MAX_ENTRIES=16;
 const originalCreate=URL.createObjectURL.bind(URL),originalFetch=window.fetch.bind(window);
 let retained=0;
 function remove(url){const item=blobs.get(url);if(item){retained-=item.blob.size;clearTimeout(item.timer);blobs.delete(url);}}
 function remember(url,blob){
  if(!blob||!Number.isFinite(blob.size)||typeof blob.slice!=='function'||blob.size>MAX_BYTES)return;
  remove(url);
  while(blobs.size&&(retained+blob.size>MAX_BYTES||blobs.size>=MAX_ENTRIES))remove(blobs.keys().next().value);
  blobs.set(url,{blob,timer:setTimeout(()=>remove(url),TTL)});retained+=blob.size;
 }
 function take(url){const item=blobs.get(url);if(!item)return null;const blob=item.blob;remove(url);return blob;}
 function owner(){try{if(window.top!==window&&window.top.location.origin===location.origin&&window.top.__danFileDownloads)return window.top.__danFileDownloads;}catch(_){}return window.__danFileDownloads;}
 URL.createObjectURL=function(blob){const url=originalCreate(blob);remember(url,blob);const shared=owner();if(shared&&shared!==api)shared.remember(url,blob);return url;};
 // revokeObjectURL retains its normal behavior. Only the short-lived Blob object
 // is kept; there is no second URL, base64 copy or delayed URL revocation.
 const api={remember,take,async save(url,token,name,mime){
  try{
   let blob=take(url);const shared=owner();if(shared&&shared!==api){const other=shared.take(url);if(!blob)blob=other;}
   if(!blob){const response=await originalFetch(url);if(!response.ok)throw new Error('HTTP '+response.status);blob=await response.blob();}
   for(let offset=0;offset<blob.size;offset+=49152){
    const data=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]||'');reader.onerror=()=>reject(new Error('Lettura file'));reader.readAsDataURL(blob.slice(offset,offset+49152));});
    if(!window.DanFileDownload.append(token,data))throw new Error('Scrittura file interrotta');
   }
   window.DanFileDownload.finish(token,blob.size,name,mime||blob.type||'application/octet-stream');
   return true;
  }catch(error){window.DanFileDownload.fail(token,String(error));return false;}
 }};
 window.__danFileDownloads=api;
 addEventListener('pagehide',()=>{for(const url of blobs.keys())remove(url);});
})();

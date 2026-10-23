'use strict';
// Real Chromium mobile browser smoke tests without npm dependencies or GitHub tokens.
// Run: node tests/browser-smoke.cjs (EXPOSITORY_ATLAS=1 for expanded review captures)
const {spawn,spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const assert=require('node:assert/strict');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const which=name=>{const r=spawnSync('which',[name],{encoding:'utf8'});return r.status===0?r.stdout.trim():null};
const CHROME=process.env.CHROME_BIN||which('google-chrome')||which('google-chrome-stable')||which('chromium')||which('chromium-browser');
if(!CHROME)throw Error('Chromium/Google Chrome is required for this browser smoke test.');
const work=fs.mkdtempSync(path.join(os.tmpdir(),'expository-smoke-'));
const screenshots=path.join(__dirname,'..','review','evidence');
fs.mkdirSync(screenshots,{recursive:true});
console.log('Chromium binary:',CHROME);
const browser=spawn(CHROME,['--headless=new','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--no-first-run',
 '--disable-background-networking','--remote-allow-origins=*','--remote-debugging-port=0','--user-data-dir='+work,'--window-size=393,852','about:blank'],{stdio:['ignore','pipe','pipe']});
let browserOutput='';for(const stream of [browser.stdout,browser.stderr])stream.on('data',chunk=>{browserOutput=(browserOutput+String(chunk)).slice(-5000)});
browser.on('error',error=>{browserOutput+='\nSpawn error: '+error.message});
let socket,sequence=0;
const pending=new Map(),runtimeErrors=[];
function fail(message){throw Error(message)}
async function connect(){
 let port;
 for(let i=0;i<100;i++){
  if(browser.exitCode!==null)fail('Chromium exited before DevTools connection: '+browser.exitCode);
  try{port=Number(fs.readFileSync(path.join(work,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break}catch{}
  await sleep(100);
 }
 if(!port)fail('Chromium did not expose a DevTools port (exit='+browser.exitCode+', signal='+browser.signalCode+'). Browser logs: '+browserOutput);
 let targets;
 for(let i=0;i<30;i++){try{targets=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();if(targets.some(x=>x.type==='page'))break}catch{}await sleep(100)}
 const target=targets?.find(x=>x.type==='page');if(!target)fail('No browser page target.');
 socket=new WebSocket(target.webSocketDebuggerUrl);
 await new Promise((resolve,reject)=>{
  const timeout=setTimeout(()=>reject(Error('WebSocket connection timed out')),5000);
  socket.onopen=()=>{clearTimeout(timeout);resolve()};
  socket.onerror=()=>{clearTimeout(timeout);reject(Error('CDP WebSocket connection failed'))};
 });
 socket.addEventListener('message',evt=>{
  const m=JSON.parse(evt.data);
  if(m.method==='Runtime.exceptionThrown')runtimeErrors.push(m.params.exceptionDetails?.exception?.description||m.params.exceptionDetails?.text||'Runtime error');
  if(m.id&&pending.has(m.id)){const q=pending.get(m.id);pending.delete(m.id);m.error?q.reject(Error(m.error.message)):q.resolve(m.result)}
 });
}
function send(method,params={}){
 return new Promise((resolve,reject)=>{
  const id=++sequence;pending.set(id,{resolve,reject});
  socket.send(JSON.stringify({id,method,params}));
  setTimeout(()=>{if(pending.has(id)){pending.delete(id);reject(Error('CDP timeout: '+method))}},12000).unref();
 });
}
async function evaluate(expression){
 const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
 if(r.exceptionDetails)fail(r.exceptionDetails.exception?.description||r.exceptionDetails.text);
 return r.result?.value;
}
async function until(expression,label){
 for(let i=0;i<70;i++){
  try{if(await evaluate(expression))return}catch{}
  await sleep(100);
 }
 fail('Timed out waiting for: '+label);
}
async function screenshot(name){const x=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(screenshots,name),Buffer.from(x.data,'base64'))}
const fixtures=String.raw`
(()=> {
 const original=window.fetch;
 const dirs=(...items)=>items.map(([name,p])=>({name,path:p,type:'dir',size:0}));
 const files=(...items)=>items.map(([name,p])=>({name,path:p,type:'file',size:250}));
 const svg=(fill,name)=>'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 450"><rect width="800" height="450" fill="#182033"/><circle cx="400" cy="225" r="160" fill="'+fill+'"/><text x="400" y="235" fill="white" text-anchor="middle" font-size="42">'+name+'</text></svg>';
 const dataA=svg('#2acbab','A'),dataB=svg('#c966e9','B');
 const largePaths=Array.from({length:1012},(_,i)=>'gallery'+String(i).padStart(4,'0')+'.png');
 const hugeCode=Array.from({length:1205},(_,i)=>i===1100?'export function distantSymbol() {}':'const row_'+i+' = '+i+';').join('\n');
 window.fetch=async (input,opts={})=>{
  const u=new URL(String(input),location.href);
  if(u.hostname!=='api.github.com')return original(input,opts);
  const p=decodeURIComponent(u.pathname),json=obj=>new Response(JSON.stringify(obj),{status:200,headers:{'content-type':'application/json'}});
  if(p==='/repos/gamesbyian/Glimpse')return json({default_branch:'main',private:false});
  if(p==='/repos/gamesbyian/Glimpse/branches')return json([{name:'main'},{name:'feature/mobile-inspection'}]);
  if(p==='/repos/gamesbyian/PrivateFixture')return json({default_branch:'main',private:true});
  if(p==='/repos/gamesbyian/DeniedPrivate')return new Response(JSON.stringify({message:'Not Found'}),{status:404,headers:{'content-type':'application/json'}});
  if(p==='/repos/gamesbyian/LargeFixture')return json({default_branch:'main',private:false});
  if(p==='/repos/gamesbyian/DeepLarge')return json({default_branch:'main',private:false});
  if(p==='/repos/gamesbyian/DeepLarge/git/trees/main'){
   if(u.searchParams.get('recursive')==='1')return json({truncated:true,tree:[{type:'tree',path:'gallery'}]});
   return json({truncated:false,tree:[{type:'tree',path:'gallery',sha:'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'}]})
  }
  if(p==='/repos/gamesbyian/DeepLarge/git/trees/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb')return json({truncated:false,tree:largePaths.map(path=>({type:'blob',path,size:200}))});
  if(p==='/repos/gamesbyian/DeepLarge/contents/')return json(dirs(['gallery','gallery']));
  if(p==='/repos/gamesbyian/DeepLarge/contents/gallery')return json(files(...largePaths.slice(0,1000).map(name=>[name,'gallery/'+name])));
  if(p==='/repos/gamesbyian/LargeFixture/git/trees/main')return json({truncated:false,tree:largePaths.map(path=>({type:'blob',path,size:200}))});
  if(p==='/repos/gamesbyian/LargeFixture/contents/')return json(files(...largePaths.slice(0,1000).map(path=>[path,path])));
  if(p.endsWith('/git/trees/main'))return json({truncated:false,tree:[
   {path:'design',type:'tree'},{path:'design/proposals',type:'tree'},
   {path:'design/proposals/a.svg',type:'blob',size:250},
   {path:'design/proposals/b.svg',type:'blob',size:250},
   {path:'docs',type:'tree'},{path:'docs/README.md',type:'blob',size:48},{path:'docs/data.json',type:'blob',size:120},{path:'docs/data.csv',type:'blob',size:100},{path:'docs/example.ts',type:'blob',size:140},{path:'docs/huge.ts',type:'blob',size:hugeCode.length}
  ]});
  if(p.endsWith('/contents/'))return json(dirs(['design','design'],['docs','docs']));
  if(p.endsWith('/contents/design'))return json(dirs(['proposals','design/proposals']));
  if(p.endsWith('/contents/docs'))return json(files(['README.md','docs/README.md'],['data.json','docs/data.json'],['data.csv','docs/data.csv'],['example.ts','docs/example.ts'],['huge.ts','docs/huge.ts']));
  if(p.endsWith('/contents/design/proposals'))return json(files(['a.svg','design/proposals/a.svg'],['b.svg','design/proposals/b.svg']));
  if(p.endsWith('/contents/design/proposals/a.svg'))return new Response(dataA,{status:200,headers:{'content-type':'image/svg+xml'}});
  if(p.endsWith('/contents/design/proposals/b.svg'))return new Response(dataB,{status:200,headers:{'content-type':'image/svg+xml'}});
  if(p.endsWith('/contents/docs/README.md'))return new Response('# Hello from fixture\n[Open data](data.csv) [unsafe](javascript:alert(1))',{status:200,headers:{'content-type':'text/plain'}});
  if(p.endsWith('/contents/docs/data.json'))return new Response(JSON.stringify({status:'ready',items:[1,2,3],active:true}),{status:200,headers:{'content-type':'application/json'}});
  if(p.endsWith('/contents/docs/data.csv'))return new Response('name,value\nAlpha,17\nBeta,23',{status:200,headers:{'content-type':'text/csv'}});
  if(p.endsWith('/contents/docs/example.ts'))return new Response('export function hello() {}\nclass MyTool {}',{status:200,headers:{'content-type':'text/plain'}});
  if(p.endsWith('/contents/docs/huge.ts'))return new Response(hugeCode,{status:200,headers:{'content-type':'text/plain'}});
  if(p.endsWith('/commits')){
   const fixtureCommit=n=>({sha:n.toString(16).padStart(40,'0'),commit:{message:'Fixture commit '+n,author:{date:'2026-10-22T12:00:00Z'}}});
   if(u.searchParams.get('path'))return json([{sha:'1111111111111111111111111111111111111111',commit:{message:'First fixture change',author:{date:'2026-10-22T12:00:00Z'}}}]);
   return json(u.searchParams.get('page')==='2'?[fixtureCommit(51),fixtureCommit(52)]:Array.from({length:50},(_,i)=>fixtureCommit(i+1)));
  }
  if(p.endsWith('/pulls'))return json([{number:12,title:'Merged repo work',merged_at:'2026-10-22T15:00:00Z'},{number:13,title:'Closed but never merged',merged_at:null,closed_at:'2026-10-22T17:00:00Z'}]);
  if(p.endsWith('/releases'))return json([{tag_name:'v1.0',name:'First public release',published_at:'2026-10-21T12:00:00Z'}]);
  if(p.endsWith('/tags'))return json([{name:'v1.0'}]);
  return new Response('Not found',{status:404});
 };
})();
`;
async function main(){
 try{
  await connect();await send('Page.enable');await send('Runtime.enable');await send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:2});
  await send('Page.addScriptToEvaluateOnNewDocument',{source:fixtures});
  await send('Emulation.setDeviceMetricsOverride',{width:393,height:852,deviceScaleFactor:1,mobile:true});
  const page=pathToFileURL(path.join(__dirname,'..','index.html')).href;
  await send('Page.navigate',{url:page});
  await until("document.querySelector('#status')?.textContent?.includes('items')",'initial listing');
  await evaluate("document.querySelector('#navToggle').click();document.querySelector('#loadBranches').click()");
  await until("document.querySelector('#branchPicker').hidden===false",'on-demand branch selector appears');
  assert.equal(await evaluate("document.querySelector('#branchPicker').options.length"),3,'two read-only branches listed');
  assert.equal(await evaluate("document.querySelector('#branchPicker').value"),'main','current branch preselected');
  await evaluate("document.querySelector('#scrim').click()");
  await evaluate("document.querySelector('#sizeToggle').click()");
  await until("document.querySelectorAll('#sizeView .size-tile').length===2",'indexed size treemap rendered');
  assert.equal(await evaluate("document.querySelector('#items').hidden"),true,'size view hides file tiles');
  assert.equal(await evaluate("document.querySelectorAll('#sizeView .size-list button').length"),2,'accessible tap list reflects both indexed groups');
  assert.equal(await evaluate("document.querySelector('#sizeView .size-head').textContent.includes('indexed bytes')"),true,'indexed byte coverage explicitly shown');
  assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'size treemap does not overflow phone width');
  assert.equal(await evaluate("document.querySelector('#sizeView .size-list button').getBoundingClientRect().bottom<=innerHeight"),true,'first accessible treemap folder is available on the first mobile screen');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-sizes-393.png');
  await evaluate("document.querySelector('#sizeView .size-list button').click()");
  await until("document.querySelector('#breadcrumbs')?.textContent?.includes('design')",'treemap folder drill-down');
  assert.equal(await evaluate("document.querySelector('#sizeView').hidden"),true,'drill-down returns to ordinary files');
  await evaluate("document.querySelector('#sizeToggle').click()");
  await until("document.querySelector('#sizeView .size-head')?.textContent?.includes('design')",'nested folder size map');
  assert.equal(await evaluate("document.querySelectorAll('#sizeView .size-list button').length"),1,'nested directory aggregates children');
  await evaluate("document.querySelector('#sizeToggle').click()");
  await evaluate("document.querySelector('#back').click()");
  await until("document.querySelector('#breadcrumbs')?.textContent?.includes('Glimpse')",'treemap browsing returns to root');

  assert.equal(await evaluate("document.querySelector('#refineControls').hidden"),true,'phone opens with filters collapsed');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-start-393.png');
  await evaluate("document.querySelector('#refineToggle').click()");
  assert.equal(await evaluate("document.querySelector('#refineControls').hidden"),false,'filters expand on phone');
  await evaluate("document.querySelector('#refineToggle').click()");
  assert.equal(await evaluate("document.querySelector('#refineControls').hidden"),true,'filters collapse again');
  await evaluate("document.querySelector('#refineToggle').click()");

  await evaluate("document.querySelector('#scope').value='images';document.querySelector('#scope').dispatchEvent(new Event('change'))");
  await until("document.querySelectorAll('#items .tile').length===2",'recursive image gallery');
  await evaluate("document.querySelector('#sortFiles').value='size';document.querySelector('#sortFiles').dispatchEvent(new Event('change'))");
  await until("document.querySelectorAll('#items .tile').length===2",'sorted gallery');
  await evaluate("document.querySelector('#density').value='210';document.querySelector('#density').dispatchEvent(new Event('input'))");
  assert.equal(await evaluate("document.querySelector('#items').style.getPropertyValue('--tile-min')"),'210px','resizable gallery');
  await evaluate("document.querySelector('#filter').value='circle';document.querySelector('#contentSearch').click()");
  await until("document.querySelector('#contentSearch').textContent==='Search contents'",'bounded content search completion');
  assert.equal(await evaluate("document.querySelectorAll('#searchResults .search-hit').length>=1"),true,'content search found SVG text');
  await evaluate("document.querySelector('#filter').value='';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await until("document.querySelectorAll('#items .tile').length===2",'restored gallery after search');
  assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'393px overflow');
  await screenshot('expository-gallery-393.png');
  await evaluate("document.querySelector('#selectMode').click();document.querySelectorAll('#items .tile')[0].click();document.querySelectorAll('#items .tile')[1].click()");
  assert.equal(await evaluate("document.querySelector('#contactSheet').textContent"),'Contact sheet (2/12)','selection count');
  await evaluate("document.querySelector('#contactSheet').click()");
  await until("document.querySelectorAll('.sheet-card img').length===2",'contact sheet images loaded');
  assert.equal(await evaluate("document.querySelector('#pageMain').inert"),true,'sheet modal background inert');
  assert.equal(await evaluate("document.querySelector('.sheet-controls button').disabled"),false,'export enabled after decode');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-contact-sheet-393.png');
  await evaluate("document.querySelector('#closePreview').click();document.querySelector('#selectMode').click()");
  assert.equal(await evaluate("document.querySelector('#pageMain').inert"),false,'contact sheet close restores page');

  if(process.env.EXPOSITORY_ATLAS==='1'){
   await send('Emulation.setDeviceMetricsOverride',{width:412,height:892,deviceScaleFactor:1,mobile:true});
   assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'412px gallery overflow');
   await screenshot('expository-gallery-412.png');
   await send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
   await until("document.querySelector('#refineControls').hidden===false",'desktop refinement controls visible');
   assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'1440px gallery overflow');
   await screenshot('expository-gallery-1440.png');
   await send('Emulation.setDeviceMetricsOverride',{width:393,height:852,deviceScaleFactor:1,mobile:true});
  }
  await evaluate("document.querySelector('#items .tile').click()");
  await until("document.querySelector('#compareAction')?.hidden===false",'first image preview');
  assert.equal(await evaluate("Boolean(document.querySelector('.image-viewer .image-stage img'))"),true,'image inspector displayed');
  await evaluate("document.querySelector('.image-actions button:nth-child(3)').click()");
  assert.equal(await evaluate("document.querySelector('.image-stage img').style.width"),'125%','image zoom in');
  await evaluate("document.querySelector('.image-actions button:nth-child(2)').click()");
  assert.equal(await evaluate("document.querySelector('.image-stage img').style.width"),'100%','image zoom reset');
  await evaluate("(()=>{const stage=document.querySelector('.image-stage');const t=(id,x,y)=>new Touch({identifier:id,target:stage,clientX:x,clientY:y});const send=(type,a,b)=>stage.dispatchEvent(new TouchEvent(type,{touches:[t(1,...a),t(2,...b)],bubbles:true,cancelable:true}));send('touchstart',[120,180],[220,180]);send('touchmove',[80,180],[260,180]);})()");
  assert.equal(await evaluate("document.querySelector('.image-stage img').style.width"),'180%','two finger pinch zoom updates image');
  assert.equal(await evaluate("document.querySelector('.image-stage').style.overscrollBehavior"),'contain','zoomed image pans within stage');
  await evaluate("window.__prePanScroll=document.querySelector('.image-stage').scrollLeft");
  await evaluate("(()=>{const stage=document.querySelector('.image-stage');const t=(x,y)=>new Touch({identifier:3,target:stage,clientX:x,clientY:y});stage.dispatchEvent(new TouchEvent('touchend',{touches:[],bubbles:true,cancelable:true}));stage.dispatchEvent(new TouchEvent('touchstart',{touches:[t(180,220)],bubbles:true,cancelable:true}));stage.dispatchEvent(new TouchEvent('touchmove',{touches:[t(110,220)],bubbles:true,cancelable:true}));})()");
  assert.equal(await evaluate("document.querySelector('.image-stage').scrollLeft>window.__prePanScroll"),true,'one finger pans enlarged image');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-pinch-393.png');
  await evaluate("document.querySelector('.image-actions button:nth-child(2)').click()");
  assert.equal(await evaluate("document.querySelector('.image-stage').style.overscrollBehavior"),'auto','fitted image permits natural page scroll');

  await evaluate("document.querySelector('#fileHistory').click()");
  await until("document.querySelector('.history-list a')?.textContent?.includes('First fixture change')",'file history loaded');
  await evaluate("document.querySelector('#fileHistory').click()");
  await until("Boolean(document.querySelector('.image-viewer'))",'return from history');
  assert.equal(await evaluate("document.querySelector('#pageMain').inert"),true,'modal background inert');
  await evaluate("document.querySelector('#compareAction').click();document.querySelector('#nextPreview').click()");
  await until("document.querySelector('#compareAction')?.textContent==='Compare A/B'",'second preview');
  await evaluate("document.querySelector('#compareAction').click()");
  await until("Boolean(document.querySelector('.compare-wipe'))",'touch reveal comparison');
  assert.equal(await evaluate("document.querySelector('.compare-range').getBoundingClientRect().bottom<=innerHeight-8"),true,'mobile reveal control visible without scrolling');
  assert.equal(await evaluate("Math.abs(document.querySelector('#closePreview').getBoundingClientRect().top-document.querySelector('#previewTitle').getBoundingClientRect().top)<12"),true,'modal close and title share first header row');
  await evaluate("const s=document.querySelector('.compare-range');s.value='75';s.dispatchEvent(new Event('input',{bubbles:true}))");
  assert.equal(await evaluate("document.querySelector('.compare-wipe').style.getPropertyValue('--reveal')"),'75%','slider changes wipe');
  await screenshot('expository-reveal-393.png');
  await evaluate("document.querySelector('.compare-controls button').click()");
  await until("Boolean(document.querySelector('.compare-grid'))",'side by side mode');
  await evaluate("document.querySelector('.compare-controls button:nth-child(3)').click()");
  await until("Boolean(document.querySelector('.diff-canvas'))",'canvas pixel diff');
  assert.equal(await evaluate("document.querySelector('.diff-summary')?.textContent?.includes('differing sampled pixels')"),true,'pixel difference metric visible');
  await screenshot('expository-pixel-diff-393.png');
  await evaluate("document.querySelector('.compare-controls button:first-child').click()");
  await until("Boolean(document.querySelector('.compare-grid'))",'restore comparison mode');
  await send('Emulation.setDeviceMetricsOverride',{width:360,height:780,deviceScaleFactor:1,mobile:true});
  assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'360px overflow');
  await screenshot('expository-compare-360.png');
  await evaluate("document.querySelector('#closePreview').click()");
  assert.equal(await evaluate("document.querySelector('#pageMain').inert"),false,'modal background restored');
  await evaluate("document.querySelector('#scope').value='repo';document.querySelector('#scope').dispatchEvent(new Event('change'));document.querySelector('#filter').value='data.json';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await until("document.querySelectorAll('#items .tile').length===1",'indexed JSON search');
  await evaluate("document.querySelector('#items .tile').click()");
  await until("document.querySelector('#structuredToggle')?.hidden===false",'JSON structure available');
  await evaluate("(()=>{const bar=document.querySelector('.code-toolbar');const find=bar.querySelector('.code-find');find.value='status';find.dispatchEvent(new Event('input',{bubbles:true}));bar.querySelectorAll('button')[1].click()})()");
  assert.equal(await evaluate("Boolean(document.querySelector('.code-line.code-find-current'))"),true,'in-file match highlighted');
  await evaluate("(()=>{const bar=document.querySelector('.code-toolbar');const go=bar.querySelector('.code-line-number');go.value='1';bar.querySelectorAll('button')[2].click()})()");
  assert.equal(await evaluate("document.querySelector('.code-search-info').textContent"),'Line 1 of 1','go-to-line works on JSON raw preview');

  await evaluate("document.querySelector('#structuredToggle').click()");
  await until("Boolean(document.querySelector('.json-tree details'))",'JSON tree rendered');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-json-360.png');
  assert.equal(await evaluate("document.querySelector('.json-tree summary')?.textContent"),'Root {3 keys}','safe JSON hierarchy');
  await evaluate("document.querySelector('#closePreview').click()");
  assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'no overflow after inspector');
  
  await evaluate("document.querySelector('#filter').value='README.md';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await until("document.querySelectorAll('#items .tile').length===1",'Markdown fixture found');
  await evaluate("document.querySelector('#items .tile').click()");
  await until("Boolean(document.querySelector('.md-link'))",'relative Markdown navigation available');
  assert.equal(await evaluate("document.querySelectorAll('.md-link').length"),1,'unsafe URL not linkified');
  await evaluate("document.querySelector('.md-link').click()");
  await until("document.querySelector('#structuredToggle')?.hidden===false",'CSV table option shown');
  await evaluate("document.querySelector('#structuredToggle').click()");
  await until("Boolean(document.querySelector('.data-table'))",'CSV table rendered');
  assert.equal(await evaluate("document.querySelectorAll('.data-table tbody tr').length"),2,'CSV rows shown');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-csv-360.png');
  await evaluate("document.querySelector('#closePreview').click()");
  await evaluate("document.querySelector('#filter').value='';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  
  await evaluate("document.querySelector('#storyToggle').click()");
  await until("document.querySelectorAll('#storyView .story-event').length===52",'Story Mode source-backed events');
  assert.equal(await evaluate("document.querySelectorAll('#storyView .story-event[href*=\"/pull/\"]').length"),1,'only merged PR milestone displayed');
  await evaluate("document.querySelector('.story-paging button').click()");
  await until("document.querySelectorAll('#storyView .story-event').length===54",'explicit older commits loaded');
  assert.equal(await evaluate("document.querySelector('.story-notice')?.textContent?.includes('Sample:')"),false,'heading remains separate from evidence receipt');
  assert.equal(await evaluate("document.querySelector('#storyView').hidden"),false,'Story Mode visible');
  assert.equal(await evaluate("document.querySelector('#items').hidden"),true,'files hidden during Story Mode');
  assert.equal(await evaluate("document.querySelectorAll('#storyView .story-tags a').length"),1,'undated tags grouped separately');
  await evaluate("document.querySelector('.story-controls button:nth-child(3)').click()");
  assert.equal(await evaluate("document.querySelectorAll('#storyView .story-event').length"),1,'filter to releases');
  await evaluate("document.querySelector('.story-controls button:nth-child(4)').click()");
  assert.equal(await evaluate("document.querySelectorAll('#storyView .story-event').length"),1,'filter to merged PRs');
  await evaluate("document.querySelector('.story-controls button:nth-child(3)').click()");
  assert.equal(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),true,'Story Mode no horizontal overflow');
  await screenshot('expository-story-360.png');
  await evaluate("document.querySelector('#storyToggle').click()");
  assert.equal(await evaluate("document.querySelector('#items').hidden"),false,'returns to files');
  await evaluate("document.querySelector('#scope').value='repo';document.querySelector('#scope').dispatchEvent(new Event('change'));document.querySelector('#filter').value='example.ts';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await until("document.querySelectorAll('#items .tile').length===1",'source outline fixture listed');
  await evaluate("document.querySelector('#items .tile').click()");
  await until("document.querySelectorAll('.outline-list button').length===2",'two source symbols listed');
  await evaluate("document.querySelectorAll('.outline-list button')[1].click()");
  assert.equal(await evaluate("document.querySelectorAll('.code-find-current').length"),1,'symbol jump highlights one source line');
  await evaluate("document.querySelector('#closePreview').click()");
  await evaluate("document.querySelector('#filter').value='';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await evaluate("document.querySelector('#filter').value='huge.ts';document.querySelector('#filter').dispatchEvent(new Event('input'))");
  await until("document.querySelectorAll('#items .tile').length===1",'long source fixture found');
  await evaluate("document.querySelector('#items .tile').click()");
  await until("Boolean(document.querySelector('.code-pages'))",'windowed source preview');
  assert.equal(await evaluate("document.querySelectorAll('.code-view .code-line').length"),500,'only 500 DOM source rows, not all 1205');
  await evaluate("document.querySelector('.code-pages button:last-child').click()");
  assert.equal(await evaluate("document.querySelector('.code-pages .hint').textContent"),'501–1000 / 1205 lines','next source window');
  await evaluate("(()=>{const bar=document.querySelector('.code-toolbar');bar.querySelector('.code-line-number').value='1205';bar.querySelectorAll('button')[2].click()})()");
  assert.equal(await evaluate("document.querySelector('.code-pages .hint').textContent"),'1001–1205 / 1205 lines','jump beyond former 2600 cutoff');
  await evaluate("(()=>{const bar=document.querySelector('.code-toolbar');const search=bar.querySelector('.code-find');search.value='distantSymbol';search.dispatchEvent(new Event('input',{bubbles:true}));bar.querySelectorAll('button')[1].click()})()");
  assert.equal(await evaluate("document.querySelectorAll('.code-line.code-find-current').length"),1,'search across windows highlights distant line');
  if(process.env.EXPOSITORY_ATLAS==='1')await screenshot('expository-large-source-393.png');
  await evaluate("document.querySelector('#closePreview').click();document.querySelector('#filter').value='';document.querySelector('#filter').dispatchEvent(new Event('input'))");

  await evaluate("document.querySelector('#repo').value='gamesbyian/PrivateFixture';document.querySelector('#branch').value='';document.querySelector('#openRepo').click()");
  await until("document.querySelector('#status')?.textContent?.includes('items')",'private fixture loaded');
  assert.equal(await evaluate("location.hash"),'','private repo does not leak into fragment');
  assert.equal(await evaluate("document.querySelector('#bookmark').disabled"),true,'private repo cannot be bookmarked');
  await evaluate("document.querySelector('#repo').value='gamesbyian/Glimpse';document.querySelector('#branch').value='';document.querySelector('#openRepo').click()");
  await until("document.querySelector('#status')?.textContent?.includes('items')",'public fixture restored');
  assert.equal(await evaluate("location.hash.includes('gamesbyian/Glimpse')"),true,'public deep links still work');
  await evaluate("document.querySelector('#repo').value='gamesbyian/LargeFixture';document.querySelector('#branch').value='';document.querySelector('#openRepo').click()");
  await until("document.querySelector('#status')?.textContent?.includes('1012 items')",'large directory repaired from Git tree');
  assert.equal(await evaluate("document.querySelector('#status').textContent.includes('recovered from complete Git tree index')"),true,'complete tree recovery is explicitly labeled');
  await evaluate("document.querySelector('#repo').value='gamesbyian/DeepLarge';document.querySelector('#branch').value='';document.querySelector('#openRepo').click()");
  await until("document.querySelector('#status')?.textContent?.includes('1 items')",'truncated large repo root loaded');
  await evaluate("document.querySelector('#scope').value='folder';document.querySelector('#scope').dispatchEvent(new Event('change'))");
  await until("Boolean(document.querySelector('#items .tile'))",'large nested folder tile visible');
  await evaluate("document.querySelector('#items .tile').click()");
  await until("document.querySelector('#status')?.textContent?.includes('1012 items')",'capped nested directory repaired from direct trees');
  assert.equal(await evaluate("document.querySelector('#status').textContent.includes('Folder verified without relying on truncated repository index')"),true,'folder-scoped recovery reports complete coverage');
  await evaluate("history.replaceState(null,'','#gamesbyian/DeniedPrivate/main/secret?file=private%2Fnotes.md');document.querySelector('#repo').value='gamesbyian/DeniedPrivate';document.querySelector('#branch').value='';document.querySelector('#openRepo').click()");
  await until("document.querySelector('#status')?.textContent?.includes('Cannot open repository')",'denied private fixture');
  assert.equal(await evaluate("location.hash"),'','denied private repo link scrubbed');
  await evaluate("window.__cspChecks=[];document.addEventListener('securitypolicyviolation',e=>window.__cspChecks.push(e.effectiveDirective));window.fetch('https://expository-test.invalid/egress-check').catch(()=>{})");
  await until("window.__cspChecks.includes('connect-src')",'CSP blocks network egress outside GitHub API');
  assert.equal(runtimeErrors.length,0,'browser runtime exceptions: '+runtimeErrors.join(' | '));
  console.log('PASS: real Chromium mobile gallery, sorting, bounded search, zoom, file history, JSON tree, modal isolation, reveal, pixel difference, side-by-side, 360/393px overflow checks.');
  console.log('Evidence: '+screenshots);
 }finally{
  try{socket?.close()}catch{}
  if(browser.exitCode===null){
   browser.kill('SIGTERM');
   await Promise.race([
    new Promise(resolve=>browser.once('exit',resolve)),
    sleep(1500).then(()=>{if(browser.exitCode===null)browser.kill('SIGKILL')})
   ]);
  }
  for(let attempt=0;attempt<4;attempt++){try{fs.rmSync(work,{recursive:true,force:true});break}catch(e){if(attempt===3)console.warn('Temporary Chrome profile could not be removed:',e.message);else await sleep(150)} }
 }
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});

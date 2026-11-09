'use strict';
// Dependency-free smoke and behavior checks for the standalone Expository browser.
// Run: node --test tests/expository.test.cjs
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8');
const source = html.match(/<script>([\s\S]+?)<\/script>/)?.[1];
function fakeNode(tag='DIV') {
  return {
    tagName:tag.toUpperCase(),dataset:{},children:[],get lastChild(){return this.children.at(-1)||null},value:'',textContent:'',style:{setProperty(name,value){this[name]=value}},classList:{add(){},remove(){},contains(){return false}},
    append(...nodes){this.children.push(...nodes)},replaceChildren(...nodes){this.children=nodes},replaceChild(replacement,old){const i=this.children.indexOf(old);if(i<0)throw Error('Child absent');this.children[i]=replacement;return old},
    remove(){},querySelector(){return null},querySelectorAll(){return []},contains(){return false},getClientRects(){return [1]},setAttribute(name,value){this[name]=value},getAttribute(name){return this[name]||null},
    addEventListener(){},focus(){},click(){},get isConnected(){return true}
  };
}
function boot(fetchImpl=globalThis.fetch,BlobImpl=globalThis.Blob) {
 const elements = new Map(),urls=[];
 const get = id => {
  if (!elements.has(id)) elements.set(id,fakeNode());
  return elements.get(id);
 };
 get('scope').value='folder';
 const document={getElementById:get,createElement:fakeNode,createTextNode:s=>({textContent:s}),addEventListener(){},activeElement:fakeNode()};
 const history={replaceState(_a,_b,u){urls.push(u)}};
 const location={hash:'',pathname:'/index.html',search:'',href:'https://example.test/index.html'};
 const localStorage={getItem(){return null},setItem(){}};
 // Remove only the page-load bootstrap. Actual application functions execute unmodified.
 const pos=source.indexOf('const match=location.hash.slice(1)');
 assert.ok(pos>0, 'page-load bootstrap located');
 const isolated='return '+source.slice(0,pos).trimStart()+
  'return {state,treeAdd,expandAncestors,paintTree,currentMatches,updateHash,withMediaType,resetSession,compareReady,drawComparison,trapPreviewFocus,filterSort,typeOf,sourceViewer,toggleBookmark,cancelContentScan,scanContents,jsonViewer,imageViewer,storyEvent,renderStory,stopStory,calculatePixelDiff,resolveMarkdownPath,parseDelimited,tableViewer,simpleMarkdown,selectImage,clearSelection,githubFailure,api,json,boundedGithubJson,fetchBlob,abortRequests,safeRepoPath,renderCurrentFile,clearPreview,currentShareUrl,openRepo,indexedDirectChildren,recoverLargeFolderFromIndex,loadOlderStory,recoverTargetedFolder,extractSourceSymbols,boundedZoom,loadBranches,sizeGroups,sizeRects,renderSizeMap,toggleSizeMap,hideSizeMap,loadOlderPulls,closeImageFullscreen,rotationMetrics,fileFolder,gallerySort,paint,loadFileDates,sourceSections,boundedSourceViewer,navigate,recordRecentFolder,paintRecentFolders,clearRecentFolders,openSearchHit,openContactSheet,presentSelectedSlideshow,stopSlideshow,documentThumbnail,loadThumb,openContainingFolder};\n})();';
 const app=new Function('document','history','location','localStorage','Option','fetch','Blob',isolated)(
   document,history,location,localStorage,function Option(name,value){return {name,value}},fetchImpl,BlobImpl
 );
 return {app,get,urls,document,location};
}
test('browser source parses and controls have matching elements',()=>{
 assert.ok(source, 'inline script exists');
 assert.doesNotThrow(()=>new vm.Script(source));
 for(const id of ['tree','scope','filter','copyFolder','copyFile','disconnect','preview','prevPreview','nextPreview'])
   assert.ok(html.includes('id="'+id+'"'), id+' exists');
});
test('recent folders stay ordered, bounded, accessible, and in tab memory only',()=>{
 const {app,get}=boot();
 app.state.repo='example/private';app.state.branch='main';app.state.repoIsPrivate=true;
 app.recordRecentFolder('secret/2026/contracts');app.recordRecentFolder('src');
 app.recordRecentFolder('secret/2026/contracts');
 assert.deepEqual(app.state.recentFolders,['secret/2026/contracts','src']);
 const nav=get('recentFolders');
 assert.equal(nav.children.length,2);
 assert.equal(nav.children[0].textContent,'↶ contracts');
 assert.equal(nav.children[0].title,'secret/2026/contracts');
 assert.match(nav.children[0].getAttribute('aria-label'),/Open recent folder/);
 assert.equal(get('clearRecents').hidden,false);
 app.recordRecentFolder('secret/../up');
 assert.equal(app.state.recentFolders.length,2,'reject unsafe paths');
 for(let i=0;i<16;i++)app.recordRecentFolder('folder/'+i);
 assert.equal(app.state.recentFolders.length,8,'bound recent folder paths');
 assert.equal(app.state.recentFolders[0],'folder/15');
 assert.equal(app.state.recentFolders.at(-1),'folder/8');
 app.clearRecentFolders();
 assert.deepEqual(app.state.recentFolders,[]);
 assert.equal(get('clearRecents').hidden,true);
 assert.equal(nav.children.length,1,'empty state without private paths');
 app.recordRecentFolder('secret/very-private');
 app.resetSession();
 assert.deepEqual(app.state.recentFolders,[]);
 assert.equal(get('recentFolders').children.length,1);
 assert.ok(!get('recentFolders').children[0].textContent.includes('very-private'),
  'disconnect must scrub private recent navigation');
 assert.equal(get('clearRecents').hidden,true);
});

test('only successful, current-generation folder reads enter recent history',async()=>{
 const requests=[];
 const fetcher=async url=>{
  requests.push(url);
  if(url.includes('/contents/bad?'))return new Response('not found',{status:404});
  return new Response(JSON.stringify([]),{status:200});
 };
 const {app,get}=boot(fetcher);
 app.state.repo='example/public';app.state.branch='main';app.state.repoIsPrivate=false;
 assert.equal(await app.navigate('docs'),true);
 assert.deepEqual(app.state.recentFolders,['docs']);
 assert.equal(await app.navigate('bad'),false);
 assert.deepEqual(app.state.recentFolders,['docs'],'missing folders must not enter recents');
 assert.equal(await app.navigate('src'),true);
 assert.deepEqual(app.state.recentFolders,['src','docs']);
 assert.equal(get('recentFolders').children[0].textContent,'↶ src');
 assert.equal(requests.length,3);
});

test('delayed superseded navigation cannot reinsert a stale private path',async()=>{
 let release;
 const fetcher=url=>{
  if(url.includes('/contents/old?'))return new Promise(resolve=>{
   release=()=>resolve(new Response(JSON.stringify([]),{status:200}))
  });
  return Promise.resolve(new Response(JSON.stringify([]),{status:200}));
 };
 const {app}=boot(fetcher);
 app.state.repo='example/private';app.state.branch='main';
 const stale=app.navigate('old');
 await new Promise(resolve=>setImmediate(resolve));
 const current=app.navigate('current');
 assert.equal(await current,true);
 release();assert.equal(await stale,false);
 assert.deepEqual(app.state.recentFolders,['current']);
});
test('mobile recent-folder disclosure reuses the same tab-only history and clears it',()=>{
 const {app,get}=boot();
 Object.assign(app.state,{repo:'private/repo',branch:'main',repoIsPrivate:true});
 app.recordRecentFolder('');
 assert.equal(get('recentJump').hidden,true,'current root cannot be its own shortcut');
 app.recordRecentFolder('private/project');
 app.recordRecentFolder('private/project/notes');
 app.state.path='private/project/notes';
 app.paintRecentFolders();
 const jumps=get('recentJumpLinks').children;
 assert.equal(get('recentJump').hidden,false);
 assert.equal(get('recentJumpSummary').textContent,'Recent folders (2)');
 assert.deepEqual(jumps.map(x=>x.textContent),['private/project','Repository root']);
 assert.equal(jumps[0].getAttribute('aria-label'),'Open recent folder: private/project');
 assert.equal(jumps[0].title,'private/project');
 assert.equal(jumps[0].type,'button');
 assert.deepEqual(app.state.recentFolders,
  ['private/project/notes','private/project',''],'inline navigation creates no separate history');
 app.clearRecentFolders();
 assert.equal(get('recentJump').hidden,true);
 assert.equal(get('recentJumpLinks').children.length,0,
  'manual Clear must remove private folder names from the mobile DOM');
 app.recordRecentFolder('private/secret');
 app.resetSession();
 assert.equal(get('recentJumpLinks').children.length,0,
  'disconnect removes private jump labels as well as sidebar history');
 assert.equal(get('recentJump').hidden,true);
});

test('folder index produces ancestry; collapsible tree limits DOM rows',()=>{
 const {app,get}=boot();
 app.treeAdd('design/storyboards/P0');app.treeAdd('design/proposals');
 assert.ok(app.state.tree.has('design/storyboards'));
 app.paintTree();
 assert.equal(get('tree').children.length,1,'collapsed top-level row only');
 app.state.expanded.add('design');app.paintTree();
 assert.equal(get('tree').children.length,3,'expanding shows immediate children');
 app.expandAncestors('design/storyboards/P0');
 assert.ok(app.state.expanded.has('design/storyboards'));
 app.paintTree();
 assert.equal(get('tree').children.length,4,'expanding ancestors exposes leaf');
});
test('file last-change dates are opt-in, source-backed and unknown dates sort last',async()=>{
 const requests=[];
 const apiFetch=async url=>{
  requests.push(url);
  const file=new URL(url).searchParams.get('path');
  const date=file==='a.js'?'2026-10-03T12:00:00Z':file==='b.js'?'2026-11-07T12:00:00Z':null;
  return new Response(JSON.stringify(date?[{commit:{committer:{date}}}]:[]),{status:200});
 };
 const {app,get}=boot(apiFetch);
 Object.assign(app.state,{repo:'public/test',branch:'main',repoRequest:1,path:''});
 app.state.items=['a.js','b.js','c.js'].map(name=>({name,path:name,type:'file',size:10}));
 get('sortFiles').value='modified';get('sortDirection').value='desc';
 app.paint();
 assert.equal(requests.length,0,'rendering cannot silently fetch commit history');
 await app.loadFileDates();
 assert.equal(requests.length,3,'only explicitly requested visible file dates are fetched');
 assert.equal(app.state.fileDates.size,3);
 assert.deepEqual(app.gallerySort(app.state.items).map(x=>x.name),['b.js','a.js','c.js']);
 const tiles=get('items').children.filter(x=>x.className==='tile');
 assert.deepEqual(tiles.map(x=>x.children[1].textContent),['b.js','a.js','c.js']);
 assert.match(tiles[0].children[2].textContent,/changed 2026-11-07/);
 assert.match(tiles[2].children[2].textContent,/change date unknown/);
 assert.match(get('status').textContent,/2 dated, 1 without a usable date/);
 await app.loadFileDates();
 assert.equal(requests.length,3,'already reviewed paths are cached only in the tab');
 get('sortDirection').value='asc';
 assert.deepEqual(app.gallerySort(app.state.items).map(x=>x.name),['a.js','b.js','c.js'],
  'unknown remains last even in ascending order');
});

test('manual date sampling requests at most twelve files at three-way concurrency',async()=>{
 let active=0,maxActive=0,requests=0;
 const apiFetch=async()=>{
  active++;maxActive=Math.max(maxActive,active);requests++;
  await new Promise(resolve=>setImmediate(resolve));
  active--;
  return new Response(JSON.stringify([{commit:{committer:{date:'2026-11-01T00:00:00Z'}}}]),{status:200});
 };
 const {app,get}=boot(apiFetch);
 Object.assign(app.state,{repo:'public/test',branch:'main',repoRequest:1,path:''});
 app.state.items=Array.from({length:30},(_,i)=>({
  name:'file'+String(i).padStart(2,'0')+'.kt',
  path:'file'+String(i).padStart(2,'0')+'.kt',type:'file',size:10
 }));
 get('sortFiles').value='modified';get('sortDirection').value='desc';
 app.paint();await app.loadFileDates();
 assert.equal(requests,12);
 assert.equal(app.state.fileDates.size,12);
 assert.ok(maxActive<=3,'no more than three concurrent GitHub history requests');
 assert.match(get('status').textContent,/of 12 sampled visible files/);
});

test('date responses from disconnected private repository never populate a successor',async()=>{
 let release;
 const apiFetch=()=>new Promise(resolve=>{
  release=()=>resolve(new Response(JSON.stringify([{
   commit:{committer:{date:'2026-11-08T00:00:00Z'}}
  }]),{status:200}));
 });
 const {app,get}=boot(apiFetch);
 Object.assign(app.state,{repo:'private/first',branch:'main',repoRequest:1,path:''});
 app.state.items=[{name:'secret.kt',path:'secret.kt',type:'file',size:10}];
 get('sortFiles').value='modified';app.paint();
 const pending=app.loadFileDates();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof release,'function');
 app.resetSession();
 release();await pending;
 assert.equal(app.state.fileDates.size,0);
 assert.equal(app.state.dateBusy,false);
 assert.equal(app.state.activeRequests.size,0);
});

test('repo-wide names and recursive image views match expected paths',()=>{
 const {app,get}=boot();
 app.state.indexReady=true;
 app.state.indexEntries=[
 {type:'file',name:'a.svg',path:'design/proposals/a.svg'},
 {type:'file',name:'b.png',path:'design/proposals/sub/b.png'},
 {type:'file',name:'app.js',path:'src/app.js'}];
 get('scope').value='repo';get('filter').value='proposals';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','b.png']);
 get('scope').value='images';get('filter').value='';app.state.path='design/proposals';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','b.png']);
 app.state.path='src';assert.equal(app.currentMatches().length,0);
 app.state.indexReady=false;app.state.indexError=false;assert.equal(app.currentMatches(),null);
 app.state.indexError=true;app.state.items=[{type:'file',name:'local.png',path:'src/local.png'}];
 assert.equal(app.currentMatches().length,1,'fallback to current-folder images');
});
test('recursive gallery scopes traverse descendants and entire repository without path-prefix leakage',()=>{
 const {app,get}=boot();app.state.indexReady=true;app.state.path='media';
 app.state.indexEntries=[
  {type:'file',name:'b.png',path:'media/sub/b.png',size:50},
  {type:'file',name:'z.html',path:'media/z.html',size:10},
  {type:'file',name:'a.svg',path:'media/a.svg',size:30},
  {type:'file',name:'other.webp',path:'media-old/other.webp',size:80},
  {type:'file',name:'start.md',path:'docs/start.md',size:20}
 ];
 get('scope').value='descendants';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','b.png','z.html']);
 get('fileKind').value='html';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['z.html']);
 get('fileKind').value='previewable';get('scope').value='allFiles';
 assert.equal(app.currentMatches().length,5);
 get('fileKind').value='all';get('scope').value='allImages';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','b.png','other.webp']);
 get('sortFiles').value='size';get('sortDirection').value='asc';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['other.webp','b.png','a.svg']);
 get('sortDirection').value='desc';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','b.png','other.webp']);
 get('sortFiles').value='name';get('sortDirection').value='asc';get('groupFolders').value='folder';
 assert.deepEqual(app.currentMatches().map(x=>x.path),['media/a.svg','media-old/other.webp','media/sub/b.png']);
 get('groupFolders').value='none';get('extFilter').value='svg, html';get('scope').value='allFiles';
 assert.deepEqual(app.currentMatches().map(x=>x.name),['a.svg','z.html'],'extension facets accept comma-delimited types without a new scan');
 get('extFilter').value='';
 assert.equal(app.fileFolder('media/sub/b.png'),'media/sub');
 assert.equal(app.fileFolder('root.png'),'');
});

test('folder group dividers, paging and jump actions use real containing paths',()=>{
 const {app,get}=boot();app.state.repo='gamesbyian/Expository';app.state.branch='main';app.state.indexReady=true;app.state.mode='list';
 app.state.indexEntries=[
  {type:'file',name:'a.svg',path:'art/a.svg',size:10},
  {type:'file',name:'b.svg',path:'art/b.svg',size:10},
  {type:'file',name:'c.svg',path:'other/c.svg',size:10}
 ];
 get('scope').value='allImages';get('groupFolders').value='folder';get('fileKind').value='image';
 app.paint();
 const host=get('items'),groups=host.children.filter(x=>x.className==='gallery-divider'),cards=host.children.filter(x=>x.className==='gallery-card');
 assert.deepEqual(groups.map(x=>x.children[0].textContent),['art','other']);
 assert.equal(cards.length,3);
 get('filter').value='old query';
 cards[0].children[1].onclick();
 assert.equal(app.state.path,'art');
 assert.equal(get('scope').value,'folder','jump must switch out of recursive mode');
 assert.equal(get('filter').value,'','jump clears stale filter');
});

test('folder grouping stays stable across show-more pagination boundaries',()=>{
 const {app,get}=boot();app.state.indexReady=true;app.state.mode='list';get('scope').value='allImages';get('groupFolders').value='folder';
 app.state.indexEntries=[...Array.from({length:81},(_,i)=>({type:'file',name:'image'+String(i).padStart(3,'0')+'.png',path:'images/image'+String(i).padStart(3,'0')+'.png',size:10})),{type:'file',name:'last.png',path:'screens/last.png',size:10}];
 app.paint();let host=get('items');
 assert.equal(host.children.filter(x=>x.className==='gallery-divider').length,1);
 const more=host.children.find(x=>x.className==='pill more');assert.ok(more);
 more.onclick();
 assert.equal(host.children.filter(x=>x.className==='gallery-divider').length,2,'continued folder is not given a duplicate divider');
 assert.equal(app.state.visibleItems.length,82);
});


// Exercise the real bounded streaming reader, rather than a removed
// response.blob() shortcut. Deferred text decoding still tests the exact
// post-download epoch transition which previously leaked stale previews.
async function waitForThumbnailCondition(predicate,message){
 for(let attempt=0;attempt<60;attempt++){
  if(predicate())return;
  await new Promise(resolve=>setImmediate(resolve));
 }
 assert.ok(predicate(),message);
}

test('stale document thumbnail text cannot repopulate a repainted tile',async()=>{
 let finishDecode;
 class DeferredTextBlob extends Blob {
  text(){return new Promise(resolve=>{finishDecode=resolve})}
 }
 const fetcher=async()=>new Response(
  '<title>Old private name</title><h1>Old text</h1>',
  {status:200,headers:{'content-type':'text/html'}});
 const {app,get}=boot(fetcher,DeferredTextBlob);
 app.state.repo='example/private';app.state.branch='main';
 const thumb=get('delayedThumb');
 app.loadThumb({name:'old.html',path:'docs/old.html',size:45,type:'file'},thumb,null,app.state.renderEpoch);
 await waitForThumbnailCondition(()=>typeof finishDecode==='function',
  'text decoding is pending after the streaming body completed');
 app.state.renderEpoch++;
 finishDecode('<title>Old private name</title><h1>Old text</h1>');
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(thumb.children.length,0,'prior render may not repaint thumbnail after decode');
});

test('current document thumbnail still renders when its epoch remains unchanged',async()=>{
 const fetcher=async()=>new Response(
  '<title>Current title</title><h1>Current contents</h1>',
  {status:200,headers:{'content-type':'text/html'}});
 const {app,get}=boot(fetcher);
 app.state.repo='example/public';app.state.branch='main';
 const thumb=get('currentThumb');
 app.loadThumb({name:'now.html',path:'docs/now.html',size:55,type:'file'},thumb,null,app.state.renderEpoch);
 await waitForThumbnailCondition(()=>thumb.children.length===1,
  'current streamed document thumbnail was not rendered');
 assert.equal(thumb.children[0].children[1].textContent,'Current title');
});

test('HTML thumbnail summaries are inert DOM text without loading or running scripts',()=>{
 const {app}=boot(),thumb=fakeNode();
 const file={name:'dashboard.html',path:'preview/dashboard.html'};
 const malicious='<title>Report</title><style>h1{color:red}</style><script>stealPrivateToken()</script><h1>Revenue</h1><p>Q3 summary</p>';
 app.documentThumbnail(file,thumb,malicious);
 const page=thumb.children[0];
 assert.equal(page.className,'doc-thumbnail');
 assert.equal(page.children[1].textContent,'Report');
 assert.match(page.children[2].textContent,/Revenue/);
 assert.ok(!page.children[2].textContent.includes('stealPrivateToken'),'scripts excluded from visible excerpt');
 assert.equal(page.children.some(x=>x.tagName==='IFRAME'),false);
});

test('share hashes retain repository slash and never contain credentials',()=>{
 const {app,urls}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';app.state.path='design/proposals';app.state.repoIsPrivate=false;app.state.token='SECRET_TEST_TOKEN';
 app.updateHash('design/proposals/sample image.svg');
 assert.equal(urls.at(-1),'#gamesbyian/Glimpse/main/design/proposals?file=design%2Fproposals%2Fsample%20image.svg');
 assert.ok(!urls.at(-1).includes('SECRET_TEST_TOKEN'));
});
test('format-specific preview blob type and explicit session reset',()=>{
 const {app,get}=boot();
 const blob=app.withMediaType(new Blob(['<svg/>']), 'figure.svg');
 assert.equal(blob.type,'image/svg+xml');
 app.state.token='SECRET_TEST_TOKEN';app.state.repo='gamesbyian/Glimpse';
 app.state.cache.set('private','data');app.treeAdd('private/folder');
 app.resetSession();
 assert.equal(app.state.token,'');assert.equal(app.state.repo,'');
 assert.equal(app.state.cache.size,0);assert.equal(app.state.tree.size,0);
 assert.equal(get('breadcrumbs').textContent,'No repository selected');
});
test('security invariants avoid persistent tokens and external scripts',()=>{
 assert.ok(!/localStorage\.setItem\([^)]*(?:token|pat|credential)/i.test(source));
 assert.ok(!/<script\s+[^>]*src=/i.test(html),'no remote script dependency');
 assert.ok(!/\beval\s*\(/.test(source),'no eval of remote file contents');
 assert.ok(html.includes('prefers-reduced-motion'));
});

test('pinned visual comparisons require two different images on the same repo and branch',()=>{
 const {app}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';
 app.state.preview={name:'B.svg',path:'design/B.svg'};app.state.currentBlob=new Blob(['B']);
 app.state.pinned={item:{name:'A.svg',path:'design/A.svg'},blob:new Blob(['A']),repo:'gamesbyian/Glimpse',branch:'main'};
 assert.equal(app.compareReady(),true);
 app.state.preview={name:'A.svg',path:'design/A.svg'};assert.equal(app.compareReady(),false,'cannot compare file to itself');
 app.state.preview={name:'notes.md',path:'design/notes.md'};assert.equal(app.compareReady(),false,'only image compare');
 app.state.preview={name:'B.svg',path:'design/B.svg'};app.state.pinned.branch='other';assert.equal(app.compareReady(),false,'no cross-branch retained blob');
});

test('touch comparison renders wipe and side-by-side controls without network',()=>{
 const {app,get}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';
 app.state.preview={name:'current.svg',path:'design/current.svg'};app.state.currentBlob=new Blob(['B'],{type:'image/svg+xml'});
 app.state.pinned={item:{name:'pinned.svg',path:'design/pinned.svg'},blob:new Blob(['A'],{type:'image/svg+xml'}),repo:app.state.repo,branch:'main'};
 app.drawComparison();
 assert.equal(app.state.compareMode,true);
 assert.equal(get('previewBody').children.length,1);
 const shell=get('previewBody').children[0],toolbar=shell.children[0];
 assert.equal(toolbar.children.length,3,'side-by-side, reveal and pixel-difference modes');
 assert.equal(shell.children[1].className,'compare-wipe');
 const slider=shell.children[2];
 assert.equal(slider.type,'range');
 assert.equal(slider.value,'50');
 slider.value='79';slider.oninput();
 assert.equal(app.state.compareWipe,79);
 assert.equal(shell.children[1].style['--reveal'],'79%');
 toolbar.children[0].onclick();
 assert.equal(app.state.compareDisplay,'side');
 assert.equal(get('previewBody').children[0].children[1].className,'compare-grid');
 app.state.compareMode=false;
});
test('modal focus trap wraps keyboard Tab in both directions',()=>{
 const {app,get,document}=boot();
 app.state.preview={name:'test.svg',path:'test.svg'};
 const first=fakeNode('button'),last=fakeNode('button'), dialog=get('preview');
 let active='';first.focus=()=>active='first';last.focus=()=>active='last';
 dialog.querySelectorAll=()=>[first,last];dialog.contains=e=>e===first||e===last;
 document.activeElement=last;
 let prevented=false;
 app.trapPreviewFocus({key:'Tab',shiftKey:false,preventDefault(){prevented=true}});
 assert.equal(prevented,true);assert.equal(active,'first');
 document.activeElement=first;prevented=false;
 app.trapPreviewFocus({key:'Tab',shiftKey:true,preventDefault(){prevented=true}});
 assert.equal(prevented,true);assert.equal(active,'last');
});

test('category filtering and file sorting are deterministic',()=>{
 const {app,get}=boot();
 const files=[
 {name:'z.svg',path:'z.svg',type:'file',size:50},
 {name:'data.csv',path:'data.csv',type:'file',size:400},
 {name:'folder',path:'folder',type:'dir',size:0},
 {name:'a.js',path:'a.js',type:'file',size:900}];
 get('fileKind').value='all';get('sortFiles').value='size';
 assert.deepEqual(app.filterSort(files).map(x=>x.name),['folder','a.js','data.csv','z.svg']);
 get('fileKind').value='image';
 assert.deepEqual(app.filterSort(files).map(x=>x.name),['z.svg']);
 get('fileKind').value='data';
 assert.deepEqual(app.filterSort(files).map(x=>x.name),['data.csv']);
 get('fileKind').value='all';get('sortFiles').value='extension';
 assert.deepEqual(app.filterSort(files).map(x=>x.name),['folder','data.csv','a.js','z.svg']);
});
test('syntax viewer renders malicious-looking code as inert text nodes',()=>{
 const {app}=boot();
 const view=app.sourceViewer('<img src=x onerror=alert(1)>\nconst x = 42','danger.js');
 assert.ok(view.children.some(x=>x.className==='code-view'));
 const code=view.children.find(x=>x.className==='code-view');
 assert.equal(code.children.length,2);
 assert.equal(code.children[0].tagName,'DIV');
});
test('only explicitly confirmed public repo can be starred',()=>{
 const {app,get}=boot();
 app.state.repo='gamesbyian/private';app.state.repoIsPrivate=true;
 app.toggleBookmark();
 assert.equal(app.state.bookmarks.size,0);
 app.state.repo='gamesbyian/open';app.state.repoIsPrivate=false;
 app.toggleBookmark();
 assert.equal(app.state.bookmarks.has('gamesbyian/open'),true);
 app.toggleBookmark();
 assert.equal(app.state.bookmarks.size,0);
});
test('content search cancellation is idempotent and clears transient results',()=>{
 const {app,get}=boot();
 app.state.scanning=true;app.state.searchSeq=5;
 app.cancelContentScan();assert.equal(app.state.searchSeq,6);
 assert.equal(app.state.scanning,false);
 assert.equal(get('contentSearch').textContent,'Search contents');
});

test('JSON structural viewer uses inert DOM nodes and depth bounds',()=>{
 const {app}=boot();
 const data={title:'<script>alert(1)</script>',nested:{array:[1,true,null]},empty:{}};
 const tree=app.jsonViewer(data);
 assert.equal(tree.className,'json-tree');
 assert.equal(tree.children[0].tagName,'DETAILS');
 assert.equal(tree.children[0].children[0].tagName,'SUMMARY');
 assert.equal(tree.children[0].children[0].textContent,'Root {3 keys}');
});

test('Story Mode builds dated, source-linked events without inventing undated chronology',()=>{
 const {app,get}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';app.state.storyOpen=true;
 const sha='a'.repeat(40);
 app.state.storyData={filter:'all',errors:[],commits:[{sha,commit:{message:'Fix reel alignment\nDetailed body',author:{date:'2026-10-21T12:00:00Z'}}}],releases:[{tag_name:'v1.0',name:'First release',published_at:'2026-10-20T10:00:00Z'}],tags:[{name:'v1.0'}]};
 app.renderStory();
 const view=get('storyView');
 assert.ok(view.children.some(x=>x.className==='story-stream'));
 const stream=view.children.find(x=>x.className==='story-stream');
 assert.equal(stream.children.filter(x=>x.className==='story-event').length,2);
 assert.equal(stream.children[1].href,'https://github.com/gamesbyian/Glimpse/commit/'+sha);
 app.state.storyData.filter='release';app.renderStory();
 assert.equal(get('storyView').children.find(x=>x.className==='story-stream').children.filter(x=>x.className==='story-event').length,1);
 app.stopStory();assert.equal(app.state.storyOpen,false);
});

test('pixel difference marks modified pixels and preserves unchanged luminance',()=>{
 const {app}=boot();
 const a=new Uint8ClampedArray([20,20,20,255,0,100,0,255,255,255,255,255]);
 const b=new Uint8ClampedArray([20,20,20,255,100,0,0,255,255,255,255,255]);
 const result=app.calculatePixelDiff(a,b);
 assert.equal(result.total,3);
 assert.equal(result.changed,1);
 assert.equal(result.pixels[4],255,'changed pixel marked bright magenta');
 assert.equal(result.pixels[5],74);
 assert.equal(result.pixels[0],13,'unchanged rendered in gray');
 assert.throws(()=>app.calculatePixelDiff(a,b.subarray(0,8)),/equal dimensions/);
});

test('relative Markdown links stay within the repository and block unsafe schemes',()=>{
 const {app}=boot();
 assert.deepEqual(app.resolveMarkdownPath('../assets/pic.svg','docs/guides/intro.md'),{path:'docs/assets/pic.svg',dir:false});
 assert.deepEqual(app.resolveMarkdownPath('/README.md','docs/guides/intro.md'),{path:'README.md',dir:false});
 assert.equal(app.resolveMarkdownPath('../../../escape.md','docs/guides/intro.md'),null);
 assert.equal(app.resolveMarkdownPath('javascript:alert(1)','docs/intro.md'),null);
 assert.equal(app.resolveMarkdownPath('//evil.example/a','docs/intro.md'),null);
 assert.ok(app.resolveMarkdownPath('https://example.com/docs','docs/intro.md').external);
});
test('CSV parser preserves quoted commas, CRLF, escaped quotes and inert formulas',()=>{
 const {app}=boot();
 const rows=app.parseDelimited('name,value,formula\r\n"One, Two","a""b",=SUM(A1)\r\n');
 assert.deepEqual(rows,[['name','value','formula'],['One, Two','a"b','=SUM(A1)']]);
 const table=app.tableViewer('name,value\nA,1',',');
 assert.ok(table.children.some(x=>x.className==='data-table-wrap'));
});
test('Markdown renders links as safe DOM buttons rather than injected HTML',()=>{
 const {app}=boot();
 const doc=app.simpleMarkdown('[Readme](../README.md) <script>alert(1)</script> [danger](javascript:alert(1))',{path:'docs/start.md'});
 const paragraph=doc.children[0];
 assert.ok(paragraph.children.some(x=>x.className==='md-link'));
 assert.ok(!paragraph.children.some(x=>x.tagName==='SCRIPT'));
});

test('image contact sheet selections are bounded, reversible and cleared',()=>{
 const {app,get}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';
 assert.equal(app.selectImage({name:'notes.md',path:'docs/notes.md',type:'file'}),false);
 for(let i=0;i<12;i++)assert.equal(app.selectImage({name:i+'.svg',path:'design/'+i+'.svg',type:'file'}),true);
 assert.equal(app.state.selected.size,12);
 assert.equal(get('contactSheet').disabled,false);
 assert.equal(app.selectImage({name:'overflow.svg',path:'design/overflow.svg',type:'file'}),false);
 assert.equal(app.state.selected.size,12);
 assert.equal(app.selectImage({name:'0.svg',path:'design/0.svg',type:'file'}),true);
 assert.equal(app.state.selected.size,11);
 app.state.selectionMode=true;app.clearSelection();
 assert.equal(app.state.selected.size,0);
 assert.equal(app.state.selectionMode,false);
 assert.equal(get('contactSheet').disabled,true);
});

test('contact-sheet slideshow uses already loaded images with no new fetch and clean teardown',async()=>{
 let calls=0;
 const fetcher=async()=>{calls++;return new Response(new Uint8Array([1,2,3]),{status:200})};
 const {app,get}=boot(fetcher);
 app.state.repo='example/private';app.state.branch='main';app.state.repoIsPrivate=true;
 const items=[{name:'one.png',path:'private/one.png',type:'file',size:3},
  {name:'two.png',path:'private/two.png',type:'file',size:3}];
 app.state.selected=new Map(items.map(x=>[x.path,x]));
 await app.openContactSheet();
 assert.equal(calls,2,'the sheet loads each source once');
 const sheet=get('previewBody').children[0],actions=sheet.children[0];
 const slide=actions.children[1];
 assert.equal(slide.textContent,'Slideshow');
 assert.equal(slide.disabled,false);
 assert.equal(slide.onclick(),true);
 assert.equal(calls,2,'slideshow must reuse downloaded images without new calls');
 const view=get('previewBody').children[0];
 assert.equal(view.className,'slide-view');
 const buttons=view.children[0].children,stage=view.children[1],caption=view.children[2];
 assert.equal(buttons[5].textContent,'1 of 2');
 assert.equal(stage.children[0].alt,'private/one.png');
 buttons[3].onclick();
 assert.equal(stage.children[0].alt,'private/two.png');
 assert.equal(buttons[5].textContent,'2 of 2');
 assert.equal(caption.textContent,'private/two.png');
 buttons[1].onclick();
 assert.equal(stage.children[0].alt,'private/one.png');
 buttons[2].onclick();
 assert.ok(app.state.slideshowTimer!==null,'Play starts the timer by explicit user action');
 assert.equal(buttons[2].textContent,'Pause');
 buttons[4].value='3000';buttons[4].onchange();
 assert.ok(app.state.slideshowTimer!==null,'interval change remains actively scheduled');
 buttons[2].onclick();
 assert.equal(app.state.slideshowTimer,null);
 assert.equal(buttons[2].textContent,'Play');
 buttons[0].onclick();
 assert.equal(app.state.slideshowActive,false);
 assert.equal(get('previewBody').children[0],sheet,'Back restores original contact sheet');
 assert.equal(get('previewTitle').textContent,'Contact sheet · 2 images');
 assert.equal(calls,2);
 app.clearPreview();
 assert.equal(app.state.slideshowTimer,null);
 assert.equal(app.state.slideStep,null);
 assert.equal(get('previewBody').children.length,0);
});

test('stale slideshows cannot start, and session teardown stops active playback',()=>{
 const {app,get}=boot();
 const loaded=[{item:{name:'secret.png',path:'secret/secret.png'},image:{src:'blob:synthetic'}}];
 const body=fakeNode('div');
 app.state.sheetOpen=true;app.state.sheetSeq=4;app.state.preview={name:'Contact sheet'};
 assert.equal(app.presentSelectedSlideshow(loaded,body,3),false,'stale sheet generation rejected');
 assert.equal(get('previewBody').children.length,0);
 assert.equal(app.presentSelectedSlideshow(loaded,body,4),true);
 const controls=get('previewBody').children[0].children[0].children;
 controls[2].onclick();
 assert.ok(app.state.slideshowTimer!==null);
 app.resetSession();
 assert.equal(app.state.slideshowActive,false);
 assert.equal(app.state.slideshowTimer,null);
 assert.equal(app.state.slideStep,null,'private selected paths cannot remain in timer callbacks');
});
test('private repository browsing never leaves identifiers in Expository URL fragments',()=>{
 const {app,urls}=boot();
 app.state.repo='gamesbyian/secret-project';app.state.branch='main';app.state.path='assets/private';app.state.repoIsPrivate=true;
 app.updateHash('assets/private/photo.png');
 assert.equal(urls.at(-1),'/index.html','private location scrubbed');
 assert.ok(!urls.at(-1).includes('secret-project'));
 assert.equal(app.currentShareUrl(),'https://github.com/gamesbyian/secret-project/tree/main/assets/private','explicit private share becomes a GitHub URL');
 app.state.preview={name:'photo.png',path:'assets/private/photo.png'};
 assert.equal(app.currentShareUrl(),'https://github.com/gamesbyian/secret-project/blob/main/assets/private/photo.png');
});
test('deep linked paths reject traversal, invalid separators, and control chars',()=>{
 const {app}=boot();
 for(const unsafe of ['../secret','dir/../secret','a//b','a/./b','a\\b','a\0b','a\nb'])assert.equal(app.safeRepoPath(unsafe),false,unsafe);
 for(const good of ['','README.md','docs/proposals/First story.svg','design/色見本.png'])assert.equal(app.safeRepoPath(good),true,good);
});
test('primary and secondary GitHub rate limits map to actionable safe cooldowns',()=>{
 const {app}=boot();
 const response=(status,headers={})=>({status,headers:{get:k=>headers[k]||null}});
 const now=1771000000000;
 const primary=app.githubFailure(response(403,{'x-ratelimit-remaining':'0','x-ratelimit-reset':String(Math.floor(now/1000)+90)}),now);
 assert.equal(primary.limited,true);assert.equal(primary.until,now+90000);assert.match(primary.message,/rate limit/);
 const secondary=app.githubFailure(response(403,{}),now,true);
 assert.equal(secondary.limited,true);assert.ok(secondary.until>=now+60000);
 const retry=app.githubFailure(response(429,{'retry-after':'120'}),now);
 assert.equal(retry.until,now+120000);
 assert.match(app.githubFailure(response(401),now).message,/token/);
 assert.match(app.githubFailure(response(404),now).message,/not found/);
});
test('API cooldown prevents repeated requests and never repeats GitHub error body or token',async()=>{
 let calls=0;
 const apiFetch=async()=>{calls++;return new Response(JSON.stringify({message:'secret token ghp_dontshow'}),{status:403,headers:{'x-ratelimit-remaining':'0','x-ratelimit-reset':String(Math.floor(Date.now()/1000)+180)}})};
 const {app}=boot(apiFetch);app.state.token='ghp_localtest';
 await assert.rejects(app.api('/repos/example/private'),e=>/rate limit/.test(e.message)&&!e.message.includes('ghp_'));
 assert.equal(calls,1);
 await assert.rejects(app.api('/repos/example/private'),/temporarily rate-limited/);
 assert.equal(calls,1,'cooldown blocked subsequent network call');
 app.resetSession();
 assert.equal(app.state.rateLimitedUntil,0,'disconnect resets cooldown for new credentials');
});
test('repo switches abort outstanding authenticated API requests',async()=>{
 let aborted=false;
 const apiFetch=(_u,opts)=>new Promise((_resolve,reject)=>{
  opts.signal.addEventListener('abort',()=>{aborted=true;reject(Object.assign(new Error('Aborted'),{name:'AbortError'}))});
 });
 const {app}=boot(apiFetch);
 const pending=app.api('/repos/example/private');
 app.abortRequests();
 await assert.rejects(pending,/Aborted/);
 assert.equal(aborted,true);
 assert.equal(app.state.activeRequests.size,0);
});


test('repo switch cancels an authenticated JSON stream after response headers',async()=>{
 let finishRead,signal,reads=0;
 const apiFetch=async(_url,opts)=>{
  signal=opts.signal;
  return {ok:true,headers:{get:()=>null},body:{getReader:()=>({
   read:()=>reads++?Promise.resolve({done:true}):new Promise(resolve=>{
    finishRead=()=>resolve({done:false,value:new TextEncoder().encode('{"private":true}')})
   }),releaseLock(){}
  })}};
 };
 const {app}=boot(apiFetch);app.state.token='ghp_private_test_only';
 const pending=app.json('/repos/old/private');
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof finishRead,'function','stream consumption must remain active after headers');
 assert.equal(app.state.activeRequests.size,1,'JSON stream owns the abort controller');
 app.abortRequests();
 assert.equal(signal.aborted,true);
 finishRead();
 await assert.rejects(pending,/cancelled/,'late JSON must not survive disconnect');
 assert.equal(app.state.activeRequests.size,0);
});

test('repo switch aborts the current binary preview stream, not just fetch headers',async()=>{
 let finishRead,signal,readCount=0;
 const apiFetch=async(_u,opts)=>{
  signal=opts.signal;
  return {ok:true,headers:{get:()=>null},body:{getReader:()=>({
   read:()=>readCount++?Promise.resolve({done:true}):new Promise(resolve=>{
    finishRead=()=>resolve({done:false,value:new Uint8Array([1,2,3])})
   }),
   releaseLock(){}
  })}};
 };
 const {app}=boot(apiFetch);app.state.repo='example/old';app.state.branch='main';
 const pending=app.fetchBlob({name:'secret.txt',path:'private/secret.txt'});
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof finishRead,'function');
 assert.equal(app.state.activeRequests.size,1);
 app.abortRequests();
 assert.equal(signal.aborted,true);
 finishRead();
 await assert.rejects(pending,/cancelled/);
 assert.equal(app.state.activeRequests.size,0);
});

test('GitHub blob read rejects actual streamed bytes beyond limit without Content-Length',async()=>{
 let cancelled=false;
 const apiFetch=async()=>new Response(new ReadableStream({
  start(controller){
   controller.enqueue(new Uint8Array([1,2,3]));
   controller.enqueue(new Uint8Array([4,5]));
  },
  cancel(){cancelled=true}
 }),{status:200});
 const {app}=boot(apiFetch);app.state.repo='example/public';app.state.branch='main';
 await assert.rejects(
  app.fetchBlob({name:'large.bin',path:'large.bin'},4),
  /4-byte browser read limit/
 );
 assert.equal(cancelled,true,'oversize response is cancelled early');
 assert.equal(app.state.activeRequests.size,0);
});

test('GitHub blob read rejects oversized advertised length before streaming',async()=>{
 const apiFetch=async()=>new Response('small',{status:200,headers:{'Content-Length':'999'}});
 const {app}=boot(apiFetch);app.state.repo='example/public';app.state.branch='main';
 await assert.rejects(app.fetchBlob({name:'large.bin',path:'large.bin'},4),/4-byte browser read limit/);
 assert.equal(app.state.activeRequests.size,0);
});

test('GitHub blob read accepts exact byte limit and preserves media type',async()=>{
 const apiFetch=async()=>new Response(new Uint8Array([60,115,118,103]),{status:200});
 const {app}=boot(apiFetch);app.state.repo='example/public';app.state.branch='main';
 const result=await app.fetchBlob({name:'icon.svg',path:'icon.svg'},4);
 assert.equal(result.size,4);
 assert.equal(result.type,'image/svg+xml');
 assert.equal(await result.text(),'<svg');
 assert.equal(app.state.activeRequests.size,0);
});

test('GitHub blob read refuses browsers without enforceable streaming',async()=>{
 const apiFetch=async()=>({ok:true,headers:{get:()=>null},body:null,blob:async()=>new Blob(['private'])});
 const {app}=boot(apiFetch);app.state.repo='example/private';app.state.branch='main';
 await assert.rejects(app.fetchBlob({name:'file.txt',path:'file.txt'},1024),/cannot enforce a streaming/);
 assert.equal(app.state.activeRequests.size,0);
});

test('a late denied old-session streamed error cannot throttle the next session',async()=>{
 let finishError,signal,reads=0;
 const apiFetch=async(_url,opts)=>{
  signal=opts.signal;
  return {ok:false,status:403,
   headers:{get:key=>key==='x-ratelimit-remaining'?'35':null},
   body:{getReader:()=>({
    read:()=>reads++?Promise.resolve({done:true}):new Promise(resolve=>{
     finishError=()=>resolve({done:false,value:new TextEncoder().encode(
      '{"message":"secondary rate limit exceeded for prior token"}')})
    }),releaseLock(){}
   })}
  };
 };
 const {app}=boot(apiFetch);
 const pending=app.json('/repos/old/private');
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof finishError,'function');
 app.abortRequests();
 assert.equal(signal.aborted,true);
 finishError();
 await assert.rejects(pending,/cancelled/);
 assert.equal(app.state.rateLimitedUntil,0,'stale error cannot throttle successor');
 assert.equal(app.state.activeRequests.size,0);
});

test('GitHub metadata reader rejects streamed JSON exceeding its actual byte cap',async()=>{
 let cancelled=false;
 const {app}=boot();
 const response=new Response(new ReadableStream({
  start(controller){controller.enqueue(new TextEncoder().encode('{"secret":true}'))},
  cancel(){cancelled=true}
 }),{status:200});
 await assert.rejects(app.boundedGithubJson(response,9),/9-byte browser read limit/);
 assert.equal(cancelled,true,'oversized response stream must be cancelled');
});
test('GitHub JSON rejects invalid UTF-8 instead of silently changing metadata',async()=>{
 const {app}=boot();
 await assert.rejects(
  app.boundedGithubJson(new Response(new Uint8Array([0xc0,0x80]),{status:200}),100),
  /invalid UTF-8 JSON/
 );
 await assert.rejects(
  app.boundedGithubJson(new Response('{"incomplete":',{status:200}),100),
  /malformed JSON/
 );
});
test('recursive GitHub trees have a larger explicit cap than ordinary API responses',async()=>{
 const fetcher=async()=>new Response('{"tree":[]}',{
  status:200,headers:{'Content-Length':'6000000'}
 });
 const {app}=boot(fetcher);
 await assert.rejects(app.json('/repos/public/test'),/GitHub JSON exceeds the 4194304-byte browser read limit/);
 const tree=await app.json('/repos/public/test/git/trees/abc?recursive=1');
 assert.deepEqual(tree.tree,[]);
 assert.equal(app.state.activeRequests.size,0);
});
test('GitHub metadata refuses unbounded parsing on unsupported response streams',async()=>{
 const fetcher=async()=>({ok:true,headers:{get:()=>null},body:null,json:async()=>({private:true})});
 const {app}=boot(fetcher);
 await assert.rejects(app.json('/repos/old/private'),/cannot enforce a streaming/);
 assert.equal(app.state.activeRequests.size,0);
});

test('a closed ZIP preview cannot republish a pending parsed archive',async()=>{
 let finishArchive;
 const item={name:'private.zip',path:'private/private.zip',type:'file',size:22};
 const blob={size:22,arrayBuffer:()=>new Promise(resolve=>{finishArchive=resolve})};
 const {app,get}=boot();
 app.state.preview=item;app.state.currentBlob=blob;
 const pending=app.renderCurrentFile();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof finishArchive,'function','archive byte read is pending');
 app.clearPreview();
 const bytes=new Uint8Array(22);
 new DataView(bytes.buffer).setUint32(0,0x06054b50,true);
 finishArchive(bytes.buffer);
 await pending;
 assert.equal(app.state.preview,null);
 assert.equal(get('previewBody').children.length,0,'old archive must not reappear in a closed dialog');
});

test('reopening the same file discards stale text from the previous Blob',async()=>{
 let finishText;
 const item={name:'readme.md',path:'docs/readme.md',type:'file',size:20};
 const oldBlob={type:'text/plain',text:()=>new Promise(resolve=>{finishText=resolve})};
 const {app,get}=boot();
 app.state.preview=item;app.state.currentBlob=oldBlob;
 const pending=app.renderCurrentFile();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof finishText,'function','old content decode is pending');
 app.state.currentBlob={type:'text/plain',text:async()=> 'new content'};
 finishText('private contents from previous read');
 await pending;
 assert.equal(app.state.previewText,null,'old response cannot become new file text');
 assert.equal(get('previewBody').children.length,0);
});

test('cancelled content search cannot publish hits after asynchronous text decoding',async()=>{
 const fetcher=async()=>new Response('needle in old private data',{status:200});
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo:'example/private',branch:'main',indexReady:true});
 app.state.indexEntries=[{name:'old.txt',path:'secret/old.txt',type:'file',size:26}];
 get('filter').value='needle';
 const original=Blob.prototype.text;
 let finishDecode;
 Blob.prototype.text=function(){return new Promise(resolve=>{finishDecode=resolve})};
 try{
  const pending=app.scanContents();
  for(let i=0;i<12&&!finishDecode;i++)await new Promise(resolve=>setImmediate(resolve));
  assert.equal(typeof finishDecode,'function','download completed and text decoding is suspended');
  app.cancelContentScan();
  assert.equal(get('searchResults').children.length,0,'cancellation clears existing hits');
  finishDecode('needle from previous repository');
  await pending;
  assert.equal(get('searchResults').children.length,0,'late decode cannot resurrect private search results');
  assert.equal(app.state.scanning,false);
 }finally{Blob.prototype.text=original}
});

test('current content search still returns bounded text matches',async()=>{
 const fetcher=async()=>new Response('first line\nneedle in second line',{status:200});
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo:'example/public',branch:'main',indexReady:true});
 app.state.indexEntries=[{name:'notes.txt',path:'notes.txt',type:'file',size:32}];
 get('filter').value='needle';
 await app.scanContents();
 const hits=get('searchResults').children.filter(x=>x.className==='search-hit');
 assert.equal(hits.length,1);
 assert.equal(hits[0].children[0].textContent,'notes.txt:2');
 assert.equal(app.state.scanning,false);
});

test('large source sections align to line endings, retain global line numbering, and reuse in-memory bytes',async()=>{
 const text=Array.from({length:8000},(_,i)=>'line'+i+' '+('x'.repeat(80))).join('\n');
 assert.ok(text.length>500000);
 const {app,get}=boot();
 const sections=app.sourceSections(text);
 assert.equal(sections.length,2);
 assert.equal(sections[0].start,0);
 assert.ok(sections[0].end<=500000,'section must stay within 500k chars');
 assert.equal(text[sections[0].end-1],'\n','prefer complete lines when available');
 assert.equal(sections[1].start,sections[0].end,'source sections must cover every character');
 assert.equal(sections[1].partialStart,false);
 assert.equal(sections[0].partialEnd,false);
 assert.ok(sections[1].firstLine>1);
 const file={name:'large.ts',path:'src/large.ts',type:'file',size:text.length};
 app.state.repo='example/public';app.state.branch='main';
 app.state.preview=file;app.state.currentBlob=new Blob([text]);app.state.previewText=text;
 await app.renderCurrentFile();
 let shell=get('previewBody').children[0];
 assert.equal(shell.className,'source-segments');
 assert.equal(shell.children[0].children[0].disabled,true);
 assert.equal(shell.children[0].children[1].disabled,false);
 assert.match(shell.children[0].children[2].textContent,/Section 1 of 2/);
 assert.ok(app.state.sourceRanges?.sections===sections ||
           app.state.sourceRanges?.sections.length===sections.length);
 await shell.children[0].children[1].onclick();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(app.state.sourceSegment,1);
 shell=get('previewBody').children[0];
 assert.match(shell.children[0].children[2].textContent,/Section 2 of 2/);
 assert.equal(shell.children[0].children[1].disabled,true);
 const viewer=shell.children[1],pager=viewer.children.find(node=>node.className==='code-pages');
 assert.equal(pager.children[1].textContent.startsWith(sections[1].firstLine+'–'),true);
 assert.equal(viewer.jumpToLine(sections[1].firstLine),true);
 assert.equal(viewer.children.at(-1).children[0].className,'code-line code-find-current');
 assert.equal(shell.jumpToLine(1),false,'unloaded sections do not invent DOM rows');
 await shell.children[0].children[0].onclick();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(app.state.sourceSegment,0,'can return to first section');
 app.resetSession();
 assert.equal(app.state.sourceRanges,null,'disconnect discards private text reference');
 assert.equal(app.state.sourceSegment,0);
});

test('long individual line gets explicit continuation and never splits a surrogate pair',()=>{
 const {app}=boot();
 const text='a'.repeat(499999)+'😀'+'z'.repeat(22);
 const sections=app.sourceSections(text);
 assert.equal(sections.length,2);
 assert.equal(sections[0].end,499999,'do not split the UTF-16 surrogate pair');
 assert.equal(text.slice(sections[1].start,sections[1].start+2),'😀');
 assert.equal(sections[0].partialEnd,true);
 assert.equal(sections[1].partialStart,true);
 assert.equal(sections[1].firstLine,1,'continued physical line keeps its original number');
 const view=app.boundedSourceViewer(text,'single-line.js');
 assert.match(view.children[0].children[2].textContent,/ends mid-line/);
 app.state.sourceSegment=1;
 const continued=app.boundedSourceViewer(text,'single-line.js');
 assert.match(continued.children[0].children[2].textContent,/starts mid-line/);
});

test('windowed source viewer uses absolute line offsets for controls, symbols and jump',()=>{
 const {app}=boot();
 const view=app.sourceViewer('function alpha() {}\nfunction beta() {}','code.js',false,702);
 const bar=view.children[0],pager=view.children.find(x=>x.className==='code-pages');
 assert.equal(bar.children[4].min,'702');
 assert.equal(bar.children[4].max,'703');
 assert.equal(pager.children[1].textContent,'702–703 / 703 lines');
 assert.equal(view.children.at(-1).children[0].children[0].textContent,'702');
 assert.equal(view.jumpToLine(703),true);
 assert.equal(view.jumpToLine(701),false);
 const symbols=view.children.find(x=>x.className==='outline-panel');
 assert.match(symbols.children[1].children[0].textContent,/L702/);
 assert.match(symbols.children[1].children[1].textContent,/L703/);
});

test('source viewer jumps to global lines beyond its current 500-line window',()=>{
 const {app}=boot();
 const view=app.sourceViewer(Array.from({length:1307},(_,i)=>'source '+(i+1)).join('\n'),'long.ts');
 const pager=view.children.find(node=>node.className==='code-pages');
 const pre=view.children.at(-1);
 assert.equal(pager.children[1].textContent,'1–500 / 1307 lines');
 assert.equal(view.jumpToLine(1207),true);
 assert.equal(pager.children[1].textContent,'1001–1307 / 1307 lines');
 assert.equal(pre.children[206].className,'code-line code-find-current');
 assert.equal(view.children[0].children[4].value,'1207');
 assert.equal(view.children[0].children[6].textContent,'Line 1207 of 1307');
 assert.equal(view.jumpToLine(1308),false);
 assert.equal(pager.children[1].textContent,'1001–1307 / 1307 lines','out-of-range jump cannot change window');
});

test('content search opens a deep Markdown match in raw windowed source and allows rendered toggle',async()=>{
 const markdown=Array.from({length:705},(_,i)=>i===650?'needle in line 651':'content '+i).join('\n');
 const fetcher=async()=>new Response(markdown,{status:200});
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo:'example/public',branch:'main',repoRequest:1,indexReady:true});
 const file={name:'notes.md',path:'docs/notes.md',size:markdown.length,type:'file'};
 app.state.indexEntries=[file];app.state.visibleItems=[file];
 get('filter').value='needle';
 await app.scanContents();
 const hit=get('searchResults').children.find(node=>node.className==='search-hit');
 assert.ok(hit,'bounded scan produced the deep source-line result');
 assert.match(hit.children[0].textContent,/notes.md:651/);
 assert.equal(await hit.onclick(),true,'search hit must jump to its actual loaded line');
 const viewer=get('previewBody').children[0],pager=viewer.children.find(node=>node.className==='code-pages');
 assert.equal(pager.children[1].textContent,'501–705 / 705 lines');
 assert.equal(viewer.children.at(-1).children[150].className,'code-line code-find-current');
 assert.equal(get('structuredToggle').hidden,false);
 assert.equal(get('structuredToggle').textContent,'Rendered Markdown');
 await get('structuredToggle').onclick();
 assert.equal(get('structuredToggle').textContent,'Raw source');
 assert.notEqual(get('previewBody').children[0].className,'code-view','Markdown is rendered again');
});

test('content-search jump refuses a file from a disconnected repository',async()=>{
 const {app,get}=boot(async()=>new Response('needle',{status:200}));
 Object.assign(app.state,{repo:'new/repository',branch:'main',repoRequest:2});
 const file={name:'stale.js',path:'secret/stale.js',size:6,type:'file'};
 assert.equal(await app.openSearchHit(file,0,'old/repository','main'),false);
 assert.equal(get('previewBody').children.length,0,'no stale source preview mounted');
});
test('code inspector searches displayed lines without treating source as markup',()=>{
 const {app}=boot();
 const view=app.sourceViewer('const target = 1;\nconst other = 2;\n// target again','example.js');
 const bar=view.children[0],search=bar.children[1],prev=bar.children[2],next=bar.children[3],info=bar.children[6],pre=view.children.at(-1);
 assert.equal(pre.children.length,3);
 search.value='target';search.oninput();
 assert.equal(pre.children[0].className,'code-line code-find-hit');
 assert.equal(pre.children[1].className,'code-line');
 assert.equal(pre.children[2].className,'code-line code-find-hit');
 next.onclick();
 assert.equal(pre.children[0].className,'code-line code-find-hit code-find-current');
 next.onclick();
 assert.equal(pre.children[2].className,'code-line code-find-hit code-find-current');
 prev.onclick();
 assert.equal(pre.children[0].className,'code-line code-find-hit code-find-current');
 search.value='<script>';search.oninput();
 assert.equal(info.textContent,'No matching lines');
});
test('go-to-line is bounded and search describes truncated files honestly',()=>{
 const {app}=boot();
 const view=app.sourceViewer('one\ntwo\nthree','example.ts');
 const bar=view.children[0],go=bar.children[4],goButton=bar.children[5],info=bar.children[6],pre=view.children.at(-1);
 go.value='3';goButton.onclick();
 assert.equal(info.textContent,'Line 3 of 3');
 assert.equal(pre.children[2].className,'code-line code-find-current');
 go.value='400';goButton.onclick();
 assert.equal(info.textContent,'Enter a line from 1 to 3');
 const big=app.sourceViewer(Array.from({length:2605},(_,i)=>'line '+i).join('\n'),'large.py');
 const rows=big.children.at(-1),pager=big.children.find(x=>x.className==='code-pages');
 assert.equal(rows.children.length,500,'bounded DOM window renders only 500 rows');
 assert.equal(pager.children[1].textContent,'1–500 / 2605 lines');
 pager.children[2].onclick();
 assert.equal(rows.children.length,500);
 assert.equal(pager.children[1].textContent,'501–1000 / 2605 lines');
 const bar2=big.children[0];bar2.children[4].value='2605';bar2.children[5].onclick();
 assert.equal(rows.children.length,105,'last window has only 105 rows');
 assert.equal(pager.children[1].textContent,'2501–2605 / 2605 lines');
 assert.equal(rows.children.at(-1).className,'code-line code-find-current');
});

test('public static app CSP pins GitHub API and prevents arbitrary network/media origins',()=>{
 const csp=html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)"/)?.[1];
 assert.ok(csp,'a static meta CSP is present');
 assert.match(csp,/default-src 'none'/);
 assert.match(csp,/connect-src https:\/\/api\.github\.com;/);
 assert.match(csp,/img-src 'self' blob: data:;/);
 assert.match(csp,/worker-src 'none'/);
 assert.match(csp,/object-src 'none'/);
 assert.match(csp,/base-uri 'none'/);
 assert.match(csp,/form-action 'none'/);
 assert.ok(!/connect-src[^;]*\*/.test(csp),'no wildcard outgoing connections');
 assert.ok(!/script-src[^;]*https?:/.test(csp),'no remotely hosted scripts');
});

test('large folder uses completed Git tree to recover entries beyond Contents API cap',()=>{
 const {app}=boot();
 app.state.path='gallery';app.state.indexReady=true;app.state.indexTruncated=false;
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:'image'+i+'.png',path:'gallery/image'+i+'.png',size:10}));
 app.state.indexEntries=[
  {type:'file',name:'image1000.png',path:'gallery/image1000.png',size:12},
  {type:'file',name:'image1001.png',path:'gallery/image1001.png',size:13},
  {type:'file',name:'nested.png',path:'gallery/child/nested.png',size:15},
  {type:'file',name:'offsite.png',path:'other/offsite.png',size:12}
 ];
 app.treeAdd('gallery/child');
 assert.deepEqual(app.indexedDirectChildren('gallery').map(x=>x.path),['gallery/image1000.png','gallery/image1001.png','gallery/child']);
 assert.equal(app.recoverLargeFolderFromIndex(),3);
 assert.equal(app.state.items.length,1003);
 assert.equal(app.recoverLargeFolderFromIndex(),0,'no duplicate recovery');
});
test('truncated tree index cannot falsely certify or backfill huge directories',()=>{
 const {app}=boot();
 app.state.path='media';app.state.items=Array.from({length:1000},(_,i)=>({type:'file',path:'media/'+i+'.svg',name:i+'.svg'}));
 app.state.indexReady=true;app.state.indexTruncated=true;app.state.indexEntries=[{type:'file',path:'media/unlisted.svg',name:'unlisted.svg'}];
 assert.equal(app.recoverLargeFolderFromIndex(),0);
 assert.equal(app.state.items.length,1000);
});

test('failed private deep links are scrubbed before GitHub auth/visibility is known',async()=>{
 const refused=async()=>new Response(JSON.stringify({message:'Not Found'}),{status:404,headers:{'content-type':'application/json'}});
 const {app,get,urls,location}=boot(refused);
 get('repo').value='owner/PrivateFixture';
 location.hash='#owner/PrivateFixture/main/hidden/docs?file=hidden%2Fsecret.md';
 const ok=await app.openRepo('hidden/docs');
 assert.equal(ok,false);
 assert.equal(urls[0],'/index.html','incoming fragment cleared before any API result');
 assert.equal(urls.some(x=>x.includes('hidden')),false,'private paths never written to browser history');
});

test('Story Mode shows real merged PR milestones, not merely closed PRs',()=>{
 const {app,get}=boot();app.state.repo='gamesbyian/Expository';app.state.storyOpen=true;
 app.state.storyData={commits:[],releases:[],tags:[],pulls:[
  {number:72,title:'Merged feature',merged_at:'2026-10-20T13:00:00Z'},
  {number:73,title:'Unmerged proposal',merged_at:null,closed_at:'2026-10-21T14:00:00Z'},
  {number:74,title:'<img src=x onerror=alert(1)>',merged_at:'2026-10-22T09:00:00Z'}
 ],filter:'pr',pages:1,hasMore:false,loadingOlder:false,olderError:'',errors:[]};
 app.renderStory();const host=get('storyView'),stream=host.children.find(x=>x.className==='story-stream');
 const entries=stream.children.filter(x=>x.className==='story-event');
 assert.equal(entries.length,2,'only merged PRs receive timeline entries');
 assert.equal(entries[0].href,'https://github.com/gamesbyian/Expository/pull/74');
 assert.equal(entries[0].children[0].textContent,'#74 · <img src=x onerror=alert(1)>','untrusted title inert text');
});
test('older Story Mode commits paginate only after user intent and stop at nonfull page',async()=>{
 const sha='f'.repeat(40);const paths=[];
 const apiFetch=async (url)=>{paths.push(url);return new Response(JSON.stringify([{sha,commit:{message:'Older',author:{date:'2026-10-16T08:00:00Z'}}}]),{status:200,headers:{'content-type':'application/json'}})};
 const {app}=boot(apiFetch);app.state.repo='gamesbyian/Expository';app.state.branch='main';app.state.storyOpen=true;app.state.storySeq=5;
 const data={commits:[{sha:'a'.repeat(40),commit:{message:'Recent',author:{date:'2026-10-20T10:00:00Z'}}}],releases:[],pulls:[],tags:[],filter:'all',pages:1,hasMore:true,loadingOlder:false,olderError:'',errors:[]};
 app.state.storyData=data;await app.loadOlderStory();
 assert.equal(paths.length,1);assert.match(paths[0],/page=2$/);
 assert.equal(data.commits.length,2);assert.equal(data.pages,2);assert.equal(data.hasMore,false);
 await app.loadOlderStory();assert.equal(paths.length,1,'no request after final partial page');
});
test('Story Mode does not accept older commits after view was closed',async()=>{
 let release;const apiFetch=()=>new Promise(resolve=>{release=()=>resolve(new Response(JSON.stringify([{sha:'a'.repeat(40)}]),{status:200,headers:{'content-type':'application/json'}}))});
 const {app}=boot(apiFetch);app.state.repo='o/r';app.state.branch='main';app.state.storyOpen=true;app.state.storySeq=2;
 const data={commits:[],releases:[],pulls:[],tags:[],filter:'all',pages:1,hasMore:true,loadingOlder:false,olderError:'',errors:[]};
 app.state.storyData=data;const work=app.loadOlderStory();app.stopStory();release();await work;
 assert.equal(data.commits.length,0,'stale async response discarded');
});

test('truncated recursive index recovers a capped child folder through two nonrecursive Git trees',async()=>{
 const paths=[],sha='b'.repeat(40);
 const fetcher=async url=>{
  paths.push(url);
  const root=url.endsWith('/git/trees/main');
  const data=root?{truncated:false,tree:[{type:'tree',path:'gallery',sha}]}:
   {truncated:false,tree:Array.from({length:1002},(_,i)=>({type:'blob',path:'img'+i+'.svg',size:50}))};
  return new Response(JSON.stringify(data),{status:200,headers:{'content-type':'application/json'}})
 };
 const {app,get}=boot(fetcher);
 app.state.repo='test/large';app.state.branch='main';app.state.path='gallery';app.state.repoRequest=2;app.state.seq=3;
 app.state.indexTruncated=true;app.state.indexReady=true;app.state.mode='list';
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:'img'+i+'.svg',path:'gallery/img'+i+'.svg',size:50}));
 const added=await app.recoverTargetedFolder();
 assert.equal(added,2);assert.equal(app.state.items.length,1002);
 assert.equal(paths.length,2,'one root and one folder tree, no recursive refetch');
 assert.equal(get('status').textContent.includes('Folder verified'),true);
});
test('deep capped folder recovery uses a cached parent SHA without walking nine ancestors',async()=>{
 const repo='test/large',branch='main',sha='c'.repeat(40);
 const folder=Array.from({length:12},(_,i)=>'level'+i).join('/');
 const parent=folder.split('/').slice(0,-1).join('/');
 const paths=[];
 const fetcher=async url=>{
  paths.push(url);
  return new Response(JSON.stringify({truncated:false,tree:[
   ...Array.from({length:1000},(_,i)=>({type:'blob',path:'img'+i+'.svg',size:50})),
   {type:'blob',path:'extra.svg',size:50}
  ]}),{status:200});
 };
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo,branch,path:folder,repoRequest:3,seq:4,indexTruncated:true,indexReady:true,mode:'list'});
 app.state.cache.set(repo+'@'+branch+'/'+parent,[{type:'dir',path:folder,name:'level11',sha}]);
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',path:folder+'/img'+i+'.svg',name:'img'+i+'.svg',size:50}));
 const added=await app.recoverTargetedFolder();
 assert.equal(added,1);
 assert.deepEqual(paths,['https://api.github.com/repos/test/large/git/trees/'+sha]);
 assert.equal(app.state.items.length,1001);
 assert.match(get('status').textContent,/Folder verified/);
});
test('deep recovery fetches an uncached parent once and rejects unknown child SHA',async()=>{
 const repo='test/large',branch='main',sha='d'.repeat(40);
 const folder=Array.from({length:10},(_,i)=>'nested'+i).join('/');
 const parent=folder.split('/').slice(0,-1).join('/');
 const paths=[];
 let includeChild=true;
 const fetcher=async url=>{
  paths.push(url);
  const data=url.includes('/contents/')?
   (includeChild?[{type:'dir',path:folder,sha}]:[{type:'dir',path:'other',sha}]):
   {truncated:false,tree:[{type:'blob',path:'extra.svg',size:10}]};
  return new Response(JSON.stringify(data),{status:200});
 };
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo,branch,path:folder,repoRequest:1,seq:1,indexTruncated:true,indexReady:true,mode:'list'});
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:i+'.svg',path:folder+'/'+i+'.svg',size:10}));
 assert.equal(await app.recoverTargetedFolder(),1);
 assert.deepEqual(paths,['https://api.github.com/repos/test/large/contents/'+parent+'?ref=main','https://api.github.com/repos/test/large/git/trees/'+sha]);
 // A separate folder generation must refuse ambiguous or capped parent Contents
 // responses instead of claiming recovery from an unrelated tree.
 app.state.seq++;app.state.folderRecoveryKey='';app.state.items.pop();includeChild=false;
 paths.length=0;
 assert.equal(await app.recoverTargetedFolder(),0);
 assert.deepEqual(paths,['https://api.github.com/repos/test/large/contents/'+parent+'?ref=main']);
 assert.match(get('status').textContent,/Listing may be incomplete/);
});
test('deep capped parent can recover exact child from a nearby cached ancestor',async()=>{
 const repo='test/large',branch='main',parentSha='a'.repeat(40),folderSha='b'.repeat(40);
 const folder=Array.from({length:11},(_,i)=>'level'+i).join('/');
 const parent=folder.split('/').slice(0,-1).join('/');
 const grandparent=parent.split('/').slice(0,-1).join('/');
 const paths=[];
 const fetcher=async url=>{
  paths.push(url);
  const payload=url.includes('/contents/')?Array.from({length:1000},(_,i)=>({
   type:'file',path:parent+'/capped'+i+'.txt',sha:'c'.repeat(40)
  })):url.endsWith('/git/trees/'+parentSha)?{
   truncated:false,tree:[{type:'tree',path:'level10',sha:folderSha}]
  }:{
   truncated:false,tree:[
    ...Array.from({length:1000},(_,i)=>({type:'blob',path:'file'+i+'.svg',size:10})),
    {type:'blob',path:'extra.svg',size:10}
   ]
  };
  return new Response(JSON.stringify(payload),{status:200});
 };
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo,branch,path:folder,repoRequest:1,seq:1,indexTruncated:true,indexReady:true,mode:'list'});
 app.state.items=Array.from({length:1000},(_,i)=>({
  type:'file',name:'file'+i+'.svg',path:folder+'/file'+i+'.svg',size:10
 }));
 app.state.cache.set(repo+'@'+branch+'/'+grandparent,[{type:'dir',path:parent,sha:parentSha}]);
 assert.equal(await app.recoverTargetedFolder(),1);
 assert.deepEqual(paths,[
  'https://api.github.com/repos/test/large/contents/'+parent+'?ref=main',
  'https://api.github.com/repos/test/large/git/trees/'+parentSha,
  'https://api.github.com/repos/test/large/git/trees/'+folderSha
 ]);
 assert.equal(app.state.items.length,1001);
 assert.match(get('status').textContent,/Folder verified/);
});

test('deep capped parent will not start an unbounded uncached ancestor walk',async()=>{
 const repo='test/large',branch='main',folder=Array.from({length:15},(_,i)=>'level'+i).join('/');
 const parent=folder.split('/').slice(0,-1).join('/');
 const tooDistant=folder.split('/').slice(0,5).join('/');
 const paths=[];
 const fetcher=async url=>{
  paths.push(url);
  return new Response(JSON.stringify([]),{status:200});
 };
 const {app,get}=boot(fetcher);
 Object.assign(app.state,{repo,branch,path:folder,repoRequest:1,seq:1,indexTruncated:true,indexReady:true});
 app.state.items=Array.from({length:1000},(_,i)=>({
  type:'file',name:'file'+i,path:folder+'/file'+i
 }));
 app.state.cache.set(repo+'@'+branch+'/'+tooDistant,[{
  type:'dir',path:folder.split('/').slice(0,6).join('/'),sha:'d'.repeat(40)
 }]);
 assert.equal(await app.recoverTargetedFolder(),0);
 assert.deepEqual(paths,['https://api.github.com/repos/test/large/contents/'+parent+'?ref=main']);
 assert.match(get('status').textContent,/Listing may be incomplete/);
});

test('deep folder recovery drops delayed parent responses after navigation',async()=>{
 let release;
 const fetcher=()=>new Promise(resolve=>{release=()=>resolve(new Response(JSON.stringify([{type:'dir',path:'old',sha:'e'.repeat(40)}]),{status:200}))});
 const {app}=boot(fetcher);
 const folder=Array.from({length:10},(_,i)=>'level'+i).join('/');
 Object.assign(app.state,{repo:'test/large',branch:'main',path:folder,indexTruncated:true,indexReady:true,seq:7,repoRequest:1});
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:i+'.svg',path:folder+'/'+i+'.svg'}));
 const pending=app.recoverTargetedFolder();
 app.state.seq++;
 release();
 assert.equal(await pending,0);
 assert.equal(app.state.items.length,1000);
});

test('direct Git-tree recovery caps the entire visible union at 5,000 entries',async()=>{
 // Git trees and Contents listings may reflect different branch snapshots;
 // do not append 5,000 NEW entries on top of the first 1,000.
 const fetcher=async()=>new Response(JSON.stringify({
  truncated:false,
  tree:Array.from({length:4500},(_,i)=>({
   type:'blob',path:'new'+i+'.svg',size:10
  }))
 }),{status:200});
 const {app,get}=boot(fetcher);
 app.state.repo='test/large';app.state.branch='main';app.state.path='';
 app.state.indexTruncated=true;app.state.mode='list';
 app.state.items=Array.from({length:1000},(_,i)=>({
  type:'file',name:'old'+i+'.svg',path:'old'+i+'.svg',size:10
 }));
 const added=await app.recoverTargetedFolder();
 assert.equal(added,4000,'visible union is capped, not each response independently');
 assert.equal(app.state.items.length,5000);
 assert.equal(new Set(app.state.items.map(item=>item.path)).size,5000);
 assert.match(get('status').textContent,/results still incomplete/);
});

test('changed Git branch snapshot never claims a verified folder',async()=>{
 const fetcher=async()=>new Response(JSON.stringify({
  truncated:false,
  tree:Array.from({length:1001},(_,i)=>({
   type:'blob',path:'new'+i+'.svg',size:10
  }))
 }),{status:200});
 const {app,get}=boot(fetcher);
 app.state.repo='test/large';app.state.branch='main';app.state.path='';
 app.state.indexTruncated=true;app.state.mode='list';
 app.state.items=Array.from({length:1000},(_,i)=>({
  type:'file',name:'old'+i+'.svg',path:'old'+i+'.svg'
 }));
 const added=await app.recoverTargetedFolder();
 assert.equal(added,1001);
 assert.equal(app.state.items.length,2001,'below the union cap');
 assert.match(get('status').textContent,/Directory changed between listings/);
 assert.match(get('status').textContent,/results still incomplete/);
 assert.doesNotMatch(get('status').textContent,/Folder verified/);
});

test('changed file-versus-folder kind cannot be called a verified directory',async()=>{
 const fetcher=async()=>new Response(JSON.stringify({
  truncated:false,
  tree:Array.from({length:1000},(_,i)=>({
   type:'blob',path:'entry'+i,size:10
  }))
 }),{status:200});
 const {app,get}=boot(fetcher);
 app.state.repo='test/large';app.state.branch='main';app.state.path='';
 app.state.indexTruncated=true;app.state.mode='list';
 app.state.items=Array.from({length:1000},(_,i)=>({
  type:i===0?'dir':'file',name:'entry'+i,path:'entry'+i,size:10
 }));
 assert.equal(await app.recoverTargetedFolder(),0);
 assert.equal(app.state.items.length,1000);
 assert.match(get('status').textContent,/Directory changed between listings/);
 assert.doesNotMatch(get('status').textContent,/Folder verified/);
});

test('truncated direct folder tree never claims complete coverage',async()=>{
 const fetcher=async()=>new Response(JSON.stringify({truncated:true,tree:[{type:'blob',path:'later.svg',size:50}]}),{status:200});
 const {app,get}=boot(fetcher);app.state.repo='test/large';app.state.branch='main';app.state.path='';
 app.state.indexTruncated=true;app.state.mode='list';app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:i+'.svg',path:i+'.svg',size:10}));
 const added=await app.recoverTargetedFolder();
 assert.equal(added,1);assert.match(get('status').textContent,/results still incomplete/);
});
test('direct folder recovery never paints responses after navigation changes',async()=>{
 let release;const fetcher=()=>new Promise(resolve=>{release=()=>resolve(new Response(JSON.stringify({truncated:false,tree:[{type:'blob',path:'late.svg',size:10}]}),{status:200}))});
 const {app}=boot(fetcher);app.state.repo='test/large';app.state.branch='main';app.state.path='';app.state.indexTruncated=true;
 app.state.items=Array.from({length:1000},(_,i)=>({type:'file',name:i+'.svg',path:i+'.svg',size:10}));
 const work=app.recoverTargetedFolder();app.state.seq++;release();await work;
 assert.equal(app.state.items.length,1000,'old directory unchanged');
});

test('bounded heuristic outlines identify source symbols without inventing a full parser',()=>{
 const {app}=boot();
 const ts=app.extractSourceSymbols(['// function decoy(){}','export async function openRepo(){}','const render = async (x) => x','class Panel {}'], 'app.ts');
 assert.deepEqual(ts,[{kind:'function',name:'openRepo',line:2},{kind:'function',name:'render',line:3},{kind:'class',name:'Panel',line:4}]);
 assert.deepEqual(app.extractSourceSymbols(['class Visitor:','    async def visit(self):','    # def fake():'], 'visitor.py'),[{kind:'class',name:'Visitor',line:1},{kind:'function',name:'visit',line:2}]);
 assert.deepEqual(app.extractSourceSymbols(['pub struct Person {}','pub async fn read() {}'], 'lib.rs'),[{kind:'type',name:'Person',line:1},{kind:'function',name:'read',line:2}]);
 assert.deepEqual(app.extractSourceSymbols(['func (p *Item) Read() {}','type Item struct {}'], 'item.go'),[{kind:'function',name:'Read',line:1},{kind:'type',name:'Item',line:2}]);
 assert.deepEqual(app.extractSourceSymbols(['def test():'], 'notes.txt'),[],'unknown extension not guessed');
});
test('source outline buttons select the actual code line using inert DOM',()=>{
 const {app}=boot();
 const viewer=app.sourceViewer('export function alpha() {}\nclass Beta {}\n// unsafe <script>', 'code.js');
 const details=viewer.children.find(node=>node.className==='outline-panel');
 assert.equal(details.children[0].textContent,'Symbols (2) · heuristic, first 2,600 lines');
 const buttons=details.children[1].children;
 assert.equal(buttons.length,2);buttons[1].onclick();
 const code=viewer.children.at(-1);
 assert.equal(code.children[1].className,'code-line code-find-current');
 assert.equal(details.open,false);
});

test('touch zoom remains bounded, with deterministic two-digit precision',()=>{
 const {app}=boot();
 assert.equal(app.boundedZoom(-3),1);
 assert.equal(app.boundedZoom(1.376),1.38);
 assert.equal(app.boundedZoom(2.0001),2);
 assert.equal(app.boundedZoom(10),4);
});

test('branch picker fetches bounded names only on request and never persists them',async()=>{
 const paths=[],fetched=async url=>{paths.push(url);return new Response(JSON.stringify([{name:'main'},{name:'feature/private-review'},{name:'fix/<script>'}]),{status:200})};
 const {app,get}=boot(fetched);
 app.state.repo='gamesbyian/secret';app.state.branch='main';app.state.repoRequest=4;app.state.repoIsPrivate=true;
 assert.equal(paths.length,0,'no eager enumeration');
 await app.loadBranches();
 assert.equal(paths.length,1);assert.match(paths[0],/\/branches\?per_page=100$/);
 assert.equal(get('branchPicker').hidden,false);assert.equal(get('branchPicker').children.length,4);
 assert.equal(get('branchPicker').children[2].value,'feature/private-review');
 assert.equal(get('branchPicker').children[3].name,'fix/<script>','branch displayed using inert Option data');
 app.resetSession();assert.equal(get('branchPicker').hidden,true);
 assert.equal(get('branchPicker').children.length,1);
});
test('a stale branch list cannot display after a new repository is selected',async()=>{
 let release;const fetcher=()=>new Promise(resolve=>{release=()=>resolve(new Response(JSON.stringify([{name:'secret-branch'}]),{status:200}))});
 const {app,get}=boot(fetcher);app.state.repo='o/private';app.state.branch='main';app.state.repoRequest=1;
 const pending=app.loadBranches();app.state.repoRequest++;app.state.repo='o/public';release();await pending;
 assert.notEqual(get('branchPicker').hidden,false);
});

test('file-size treemap aggregates direct children without fetching other files',()=>{
 const {app}=boot();const entries=[
  {type:'file',path:'docs/README.md',size:100},
  {type:'file',path:'docs/tutorial/intro.md',size:200},
  {type:'file',path:'src/main.ts',size:500},
  {type:'file',path:'src/utils.ts',size:400},
  {type:'file',path:'LICENSE',size:50},
  {type:'tree',path:'src',size:100000},
 ];
 const root=app.sizeGroups(entries,'');
 assert.equal(root.totalFiles,5);
 assert.equal(root.totalBytes,1250);
 assert.deepEqual(root.groups.map(x=>[x.name,x.bytes,x.files]),[['src',900,2],['docs',300,2],['LICENSE',50,1]]);
 assert.deepEqual(app.sizeGroups(entries,'docs').groups.map(x=>[x.name,x.bytes]),[['tutorial',200],['README.md',100]]);
});
test('treemap layout tiles do not overlap or exceed normalized area',()=>{
 const {app}=boot();const groups=[{weight:70,name:'A'},{weight:20,name:'B'},{weight:10,name:'C'}];
 const rects=app.sizeRects(groups,100,60);
 assert.equal(rects.length,3);
 const total=rects.reduce((sum,x)=>sum+x.w*x.h,0);
 assert.ok(Math.abs(total-6000)<0.0001);
 for(const r of rects)assert.ok(r.w>=0&&r.h>=0&&r.x>=0&&r.y>=0&&r.x+r.w<=100.000001&&r.y+r.h<=60.000001);
 for(let a=0;a<rects.length;a++)for(let b=a+1;b<rects.length;b++){
  const x=Math.max(0,Math.min(rects[a].x+rects[a].w,rects[b].x+rects[b].w)-Math.max(rects[a].x,rects[b].x));
  const y=Math.max(0,Math.min(rects[a].y+rects[a].h,rects[b].y+rects[b].h)-Math.max(rects[a].y,rects[b].y));
  assert.ok(x*y<0.000001,'non-overlapping treemap rectangles');
 }
});
test('many size groups are bounded and aggregated, with honest zero-byte sizes',()=>{
 const {app}=boot();
 const entries=Array.from({length:100},(_,i)=>({type:'file',path:'f'+i+'.svg',size:i===0?0:i}));
 const stats=app.sizeGroups(entries,'',12);
 assert.equal(stats.groups.length,12);assert.equal(stats.groups.at(-1).type,'other');
 assert.equal(stats.totalFiles,100);assert.equal(stats.zeroSize,1);
 assert.equal(stats.totalBytes,4950);
 assert.ok(stats.omittedGroups>0);
 const unsafe=app.sizeGroups([{type:'file',path:'bad/<script>.txt',size:40}],''),result=unsafe.groups[0];
 assert.equal(result.name,'bad');assert.equal(result.type,'dir');
});
test('size view needs existing index and remains in-memory across toggles',()=>{
 const {app,get}=boot();app.state.repo='gamesbyian/Expository';app.state.branch='main';app.state.path='';app.state.indexReady=true;
 app.state.indexEntries=[{type:'file',name:'index.html',path:'index.html',size:900},{type:'file',name:'config.json',path:'docs/config.json',size:100}];
 app.toggleSizeMap();
 assert.equal(app.state.sizeOpen,true);
 assert.equal(get('items').hidden,true);
 assert.equal(get('sizeView').hidden,false);
 assert.ok(get('sizeView').children.some(x=>x.className==='size-map'));
 assert.ok(get('sizeView').children.some(x=>x.className==='size-list'));
 app.toggleSizeMap();assert.equal(app.state.sizeOpen,false);assert.equal(get('items').hidden,false);
 app.state.indexReady=false;app.state.indexError=true;app.toggleSizeMap();
 assert.ok(get('sizeView').children.length===1,'error view contains only explicit coverage message');
});
test('truncated index labels treemap sampled coverage rather than complete totals',()=>{
 const {app,get}=boot();app.state.repo='gamesbyian/Private';app.state.branch='main';app.state.indexReady=true;app.state.indexTruncated=true;
 app.state.indexEntries=[{type:'file',name:'file.txt',path:'file.txt',size:300}];
 app.renderSizeMap();
 assert.match(get('sizeView').children[0].children[1].textContent,/truncated tree/i);
});

test('code search spans multiple displayed windows without rendering all lines',()=>{
 const {app}=boot();
 const input=Array.from({length:1801},(_,i)=>i===1750?'needle = 17':'const x = '+i).join('\n');
 const view=app.sourceViewer(input,'huge.js'),bar=view.children[0],pager=view.children.find(x=>x.className==='code-pages'),pre=view.children.at(-1);
 assert.equal(pre.children.length,500);
 bar.children[1].value='needle';bar.children[1].oninput();bar.children[3].onclick();
 assert.equal(pager.children[1].textContent,'1501–1801 / 1801 lines');
 assert.equal(pre.children[250].className,'code-line code-find-hit code-find-current');
 assert.equal(pre.children.length,301,'only last window rendered, no full-file DOM');
});
test('source code preview explains 500,000-character safety limit',()=>{
 const {app}=boot();
 const view=app.sourceViewer('foo\nbar','huge.ts',true);
 assert.ok(view.children.some(x=>x.textContent.includes('500,000 characters')),'download warning shown');
 assert.ok(view.children[0].children[0].textContent.includes('500k-character cap'));
});
test('find caps active matches while honestly reporting all matching loaded lines',()=>{
 const {app}=boot();
 const view=app.sourceViewer(Array.from({length:1003},()=> 'a').join('\n'),'repeats.py');
 const search=view.children[0].children[1],info=view.children[0].children[6];
 search.value='a';search.oninput();
 assert.match(info.textContent,/First 500 of 1003 matching lines/);
 view.children[0].children[3].onclick();
 assert.match(info.textContent,/of first 500 \(of 1003\)/);
});

test('older PR samples request only on intent, deduplicate, and display only merged items',async()=>{
 const requests=[];
 const apiFetch=async url=>{
  requests.push(url);
  return new Response(JSON.stringify([{number:2,title:'New merged',merged_at:'2026-10-20T08:00:00Z'},{number:1,title:'Duplicated prior merged',merged_at:'2026-10-19T08:00:00Z'},{number:3,title:'Only closed',merged_at:null}]),{status:200,headers:{'content-type':'application/json'}})
 };
 const {app,get}=boot(apiFetch);
 app.state.repo='owner/repo';app.state.branch='main';app.state.storyOpen=true;app.state.storySeq=9;
 const data={commits:[],releases:[],tags:[],pulls:[{number:1,title:'Old merged',merged_at:'2026-10-19T08:00:00Z'}],filter:'pr',pages:1,hasMore:false,loadingOlder:false,olderError:'',pullPages:1,hasMorePulls:true,loadingOlderPulls:false,olderPullError:'',errors:[]};
 app.state.storyData=data;
 assert.equal(requests.length,0,'no eager PR pagination');
 await app.loadOlderPulls();
 assert.equal(requests.length,1);assert.match(requests[0],/per_page=20&page=2$/);
 assert.deepEqual(data.pulls.map(x=>x.number),[1,2,3],'duplicate PR is not appended');
 assert.equal(data.pullPages,2);assert.equal(data.hasMorePulls,false,'short page ends discovery');
 const stream=get('storyView').children.find(x=>x.className==='story-stream');
 assert.equal(stream.children.filter(x=>x.className==='story-event').length,2,'closed-only entry not shown as a merge');
 await app.loadOlderPulls();assert.equal(requests.length,1,'short page prevents unnecessary extra API reads');
});
test('older PR pagination caps four pages and ignores stale results when view closes',async()=>{
 let release;const fetcher=()=>new Promise(resolve=>{release=()=>resolve(new Response(JSON.stringify([{number:2,merged_at:'2026-10-20T08:00:00Z'}]),{status:200}))});
 const {app}=boot(fetcher);app.state.repo='o/r';app.state.branch='main';app.state.storyOpen=true;app.state.storySeq=3;
 const data={commits:[],releases:[],tags:[],pulls:[{number:1}],filter:'pr',pages:1,hasMore:false,loadingOlder:false,olderError:'',pullPages:2,hasMorePulls:true,loadingOlderPulls:false,olderPullError:'',errors:[]};
 app.state.storyData=data;const work=app.loadOlderPulls();app.stopStory();release();await work;
 assert.equal(data.pulls.length,1,'stale PR page never used after closing Story Mode');
 app.state.storyOpen=true;data.pullPages=4;await app.loadOlderPulls();
 assert.equal(data.pulls.length,1,'four-page limit stops additional requests');
});

test('image fullscreen affordance is accessible and degrades safely without browser API',()=>{
 const {app}=boot();
 const view=app.imageViewer(new Blob(['fixture'],{type:'image/png'}),{name:'example.png',path:'example.png'});
 const actions=view.children[0],stage=view.children[1],full=actions.children[4];
 assert.equal(full.textContent,'Fullscreen');
 assert.equal(full['aria-label'],'Enter image fullscreen');
 assert.equal(full.disabled,true,'unsupported fullscreen does not masquerade as active');
 assert.match(full.title,/not supported/);
 assert.equal(stage['aria-label'],'Image preview, rotated 0 degrees, pinch to zoom and drag to pan');
 assert.equal(app.state.zoom,1);
 app.closeImageFullscreen();
 assert.equal(app.state.imageFullscreenCleanup,null,'fullscreen listener cleaned when preview closes');
});

test('quarter-turn metrics fit rotated intrinsic geometry within the allotted image stage',()=>{
 const {app}=boot();
 const zero=app.rotationMetrics(800,450,360,620,0,1),quarter=app.rotationMetrics(800,450,360,620,90,1),half=app.rotationMetrics(800,450,360,620,180,1),third=app.rotationMetrics(800,450,360,620,270,1);
 assert.equal(zero.angle,0);assert.equal(zero.frameWidth,360);
 assert.ok(quarter.frameWidth<=360.00001&&quarter.frameHeight<=620.00001);
 assert.ok(Math.abs(quarter.frameWidth-348.75)<0.001);
 assert.ok(Math.abs(quarter.imageWidth-quarter.frameHeight)<0.001,'rotated intrinsic width matches frame height');
 assert.ok(Math.abs(quarter.imageHeight-quarter.frameWidth)<0.001,'rotated intrinsic height matches frame width');
 assert.deepEqual(third, {...quarter,angle:270});
 assert.equal(half.frameWidth,zero.frameWidth);
 const zoomed=app.rotationMetrics(800,450,360,620,90,2);
 assert.equal(zoomed.frameHeight,quarter.frameHeight*2);
 assert.equal(app.rotationMetrics(0,450,360,620,90,1),null,'invalid unloaded dimensions explicit');
});
test('image rotates four quarter turns without changing image bytes or leaving stale wrappers',()=>{
 const {app}=boot();
 const blob=new Blob(['<svg><text>original</text></svg>'],{type:'image/svg+xml'});
 const view=app.imageViewer(blob,{name:'story.svg',path:'story.svg'});
 const actions=view.children[0],stage=view.children[1],rot=actions.children[3],full=actions.children[4],image=stage.children[0];
 assert.equal(full.textContent,'Fullscreen','rotation leaves fullscreen affordance available');
 for(let n=1;n<=3;n++){
  rot.onclick();assert.equal(stage.dataset.rotated,'true');
  assert.equal(stage.children[0].className,'image-rotation-frame');
  assert.equal(stage.children[0].children[0],image);
  assert.match(image.style.transform,new RegExp('rotate\\('+(n*90)+'deg\\)'));
  assert.equal(app.state.zoom,1,'quarter turn resets zoom for fit');
 }
 rot.onclick();assert.equal(stage.dataset.rotated,'false');
 assert.equal(stage.children[0],image,'fourth turn removes wrapper');
 assert.equal(image.style.width,'100%');
 assert.equal(image.style.transform,'');
 assert.equal(blob.size,32,'source bytes remain unchanged');
});

// Byte-verify the independently reviewed Gradle wrapper and locked distribution.
require('./gradle-wrapper-integrity.test.cjs');

// Exercise standalone ZIP archive security and extraction fixtures in the regular CI entrypoint.
require('./zip-browser.test.cjs');

// Keep the opt-in writer's security and ref-creation contract in ordinary PR CI.
require('./zip-publisher.test.cjs');

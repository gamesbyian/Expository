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
    tagName:tag.toUpperCase(),dataset:{},children:[],value:'',textContent:'',style:{setProperty(name,value){this[name]=value}},classList:{add(){},remove(){},contains(){return false}},
    append(...nodes){this.children.push(...nodes)},replaceChildren(...nodes){this.children=nodes},
    remove(){},querySelector(){return null},querySelectorAll(){return []},contains(){return false},getClientRects(){return [1]},setAttribute(name,value){this[name]=value},getAttribute(name){return this[name]||null},
    addEventListener(){},focus(){},click(){},get isConnected(){return true}
  };
}
function boot(fetchImpl=globalThis.fetch) {
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
  'return {state,treeAdd,expandAncestors,paintTree,currentMatches,updateHash,withMediaType,resetSession,compareReady,drawComparison,trapPreviewFocus,filterSort,typeOf,sourceViewer,toggleBookmark,cancelContentScan,jsonViewer,imageViewer,storyEvent,renderStory,stopStory,calculatePixelDiff,resolveMarkdownPath,parseDelimited,tableViewer,simpleMarkdown,selectImage,clearSelection,githubFailure,api,abortRequests,safeRepoPath,currentShareUrl,openRepo,indexedDirectChildren,recoverLargeFolderFromIndex,loadOlderStory,recoverTargetedFolder,extractSourceSymbols,boundedZoom,loadBranches,sizeGroups,sizeRects,renderSizeMap,toggleSizeMap,hideSizeMap,loadOlderPulls,closeImageFullscreen,rotationMetrics,fileFolder,gallerySort,paint,documentThumbnail,openContainingFolder};\n})();';
 const app=new Function('document','history','location','localStorage','Option','fetch',isolated)(
   document,history,location,localStorage,function Option(name,value){return {name,value}},fetchImpl
 );
 return {app,get,urls,document,location};
}
test('browser source parses and controls have matching elements',()=>{
 assert.ok(source, 'inline script exists');
 assert.doesNotThrow(()=>new vm.Script(source));
 for(const id of ['tree','scope','filter','copyFolder','copyFile','disconnect','preview','prevPreview','nextPreview'])
   assert.ok(html.includes('id="'+id+'"'), id+' exists');
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

// Exercise standalone ZIP archive security and extraction fixtures in the regular CI entrypoint.
require('./zip-browser.test.cjs');

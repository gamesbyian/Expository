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
    tagName:tag.toUpperCase(),children:[],value:'',textContent:'',style:{setProperty(name,value){this[name]=value}},classList:{add(){},remove(){},contains(){return false}},
    append(...nodes){this.children.push(...nodes)},replaceChildren(...nodes){this.children=nodes},
    remove(){},querySelector(){return null},querySelectorAll(){return []},contains(){return false},getClientRects(){return [1]},setAttribute(name,value){this[name]=value},getAttribute(name){return this[name]||null},
    addEventListener(){},focus(){},click(){},get isConnected(){return true}
  };
}
function boot() {
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
  'return {state,treeAdd,expandAncestors,paintTree,currentMatches,updateHash,withMediaType,resetSession,compareReady,drawComparison,trapPreviewFocus,filterSort,typeOf,sourceViewer,toggleBookmark,cancelContentScan,jsonViewer,imageViewer,storyEvent,renderStory,stopStory,calculatePixelDiff,resolveMarkdownPath,parseDelimited,tableViewer,simpleMarkdown,selectImage,clearSelection};\n})();';
 const app=new Function('document','history','location','localStorage','Option',isolated)(
   document,history,location,localStorage,function Option(name,value){return {name,value}}
 );
 return {app,get,urls,document};
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
test('share hashes retain repository slash and never contain credentials',()=>{
 const {app,urls}=boot();
 app.state.repo='gamesbyian/Glimpse';app.state.branch='main';app.state.path='design/proposals';app.state.token='SECRET_TEST_TOKEN';
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

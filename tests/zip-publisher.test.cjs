'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../zip-publisher.html'),'utf8');
const source=html.match(/<script>([\s\S]+?)<\/script>/)?.[1];
assert.ok(source,'Publisher inline source present');
const start=source.indexOf('const MAX_FILES='),end=source.indexOf('function renderIncoming(){',start);
assert.ok(start>=0&&end>start,'Publisher pure logic boundary present');
const SHA={head:'a'.repeat(40),tree:'b'.repeat(40),blob:'c'.repeat(40),newTree:'d'.repeat(40),commit:'e'.repeat(40)};
function setup(respond){
 const calls=[];
 const context=vm.createContext({Blob,URL,FileReader:class{
  readAsDataURL(blob){blob.arrayBuffer().then(bytes=>{this.result='data:application/octet-stream;base64,'+Buffer.from(bytes).toString('base64');this.onload()}).catch(e=>this.onerror?.(e))}
 },fetch:async(url,options)=>{
  calls.push({url,options,body:options.body?JSON.parse(options.body):undefined});
  const answer=respond?respond(url,options,calls):undefined;
  return {ok:true,json:async()=>answer??mock(url,options)};
 }});
 vm.runInContext(source.slice(start,end)+'\nglobalThis.published={cleanPath,repoPath,branchPath,resolveEntries,findCollisions,inspectDestination,writeNewBranch};',context);
 return {api:context.published,calls};
}
function mock(url,options){
 if(url.includes('/git/ref/heads/'))return {object:{sha:SHA.head}};
 if(url.includes('/git/commits/'+SHA.head))return {tree:{sha:SHA.tree}};
 if(url.includes('/git/trees/'+SHA.tree+'?recursive=1'))return {truncated:false,tree:[{path:'already.txt',type:'blob'}]};
 if(url.endsWith('/git/blobs'))return {sha:SHA.blob};
 if(url.endsWith('/git/trees'))return {sha:SHA.newTree};
 if(url.endsWith('/git/commits'))return {sha:SHA.commit};
 if(url.endsWith('/git/refs'))return {object:{sha:SHA.commit}};
 throw Error('Unexpected GitHub API endpoint: '+url)
}
const file=(path,text='one')=>({path,blob:new Blob([text])});
test('publisher script parses without external library dependencies',()=>{
 assert.doesNotThrow(()=>new vm.Script(source));
 assert.match(html,/Content-Security-Policy/);
 assert.doesNotMatch(html,/localStorage|indexedDB|sessionStorage/i)
});
test('writer rejects path traversal, unsafe Windows targets and ambiguous duplicates',()=>{
 const {api}=setup();
 for(const p of ['../escape.txt','/absolute','dir//file.txt','CON.txt','folder/bad?.txt'])assert.equal(api.cleanPath(p),false,p);
 assert.equal(api.cleanPath('folder/ok.png'),true);
 assert.equal(api.cleanPath('.git/config'),false,'Git administration metadata cannot be published');
 assert.throws(()=>api.resolveEntries([file('foo.png'),file('FOO.PNG')],''),/duplicate/);
 assert.throws(()=>api.resolveEntries([file('é.png'),file('e\u0301.PNG')],''),/duplicate/);
 assert.throws(()=>api.resolveEntries([file('assets'),file('assets/logo.png')],''),/path conflict/);
 assert.throws(()=>api.resolveEntries([file('ASSETS'),file('assets/logo.png')],''),/path conflict/);
 assert.throws(()=>api.resolveEntries([file('é'),file('e\u0301/child.png')],''),/path conflict/);
 assert.throws(()=>api.resolveEntries([file('nested/file.txt')],'a'.repeat(4090)),/too long/);
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'../bad'),/unsafe/);
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'test/'),/unsafe/);
 assert.equal(api.branchPath('feature/zip-import'),true);
 assert.equal(api.branchPath('bad..branch'),false);
 assert.equal(api.branchPath('topic@{head}'),false,'Git ref syntax must reject @{');
});
test('tree collision check catches files, directories and case folding',()=>{
 const {api}=setup();
 const files=api.resolveEntries([file('NEW.txt'),file('A/file.txt')],'');
 const collisions=api.findCollisions(files,{truncated:false,tree:[{path:'new.TXT',type:'blob'},{path:'A',type:'blob'}]});
 assert.deepEqual([...collisions],['NEW.txt','A/file.txt']);
 assert.throws(()=>api.findCollisions(files,{truncated:true,tree:[]}),/incomplete/);
 const unicode=api.resolveEntries([file('e\u0301/new.txt')],'');
 assert.deepEqual([...api.findCollisions(unicode,{truncated:false,
  tree:[{path:'é',type:'blob'}]})],['e\u0301/new.txt'],
  'Existing canonically equivalent blob blocks a directory beneath it');
 const duplicated=api.resolveEntries([file('e\u0301.txt')],'');
 assert.deepEqual([...api.findCollisions(duplicated,{truncated:false,
  tree:[{path:'É.TXT',type:'blob'}]})],['e\u0301.txt'],
  'Existing NFC/casefold equivalent file blocks a new blob');
 assert.throws(()=>api.findCollisions(files,{truncated:false,
  tree:[{path:'É.txt',type:'blob'},{path:'e\u0301.TXT',type:'tree'}]}),/ambiguous/,
  'Malformed existing tree must not make an authority-colliding file disappear');
 assert.throws(()=>api.findCollisions(files,{truncated:false,
  tree:[{path:'mystery',type:'unknown'}]}),/unrecognized/);
});
test('destination inspection performs only reads before explicit confirmation',async()=>{
 const {api,calls}=setup();
 const plan=await api.inspectDestination('sensitive-token','owner/repo','main','folder',[file('new.txt')]);
 assert.equal(plan.entries[0].path,'folder/new.txt');
 assert.equal(plan.head,SHA.head);
 assert.deepEqual(calls.map(x=>x.options.method),['GET','GET','GET']);
 for(const call of calls)assert.equal(call.url.includes('sensitive-token'),false);
});
test('explicit commit builds blobs, tree and commit before creating only a new branch ref',async()=>{
 const {api,calls}=setup();
 const plan=await api.inspectDestination('secret','owner/repo','main','assets',[file('one.txt'),file('sub/two.txt')]);
 const result=await api.writeNewBranch(plan,'secret','expository/zip-demo',()=>{});
 assert.equal(result.commitSha,SHA.commit);
 const writes=calls.filter(x=>x.options.method==='POST');
 assert.deepEqual(writes.map(x=>x.url.split('/').at(-2)+'/'+x.url.split('/').at(-1)),['git/blobs','git/blobs','git/trees','git/commits','git/refs']);
 assert.equal(writes[3].body.parents[0],SHA.head);
 assert.equal(writes[4].body.ref,'refs/heads/expository/zip-demo');
 assert.ok(writes.every(x=>!x.url.includes('/git/refs/heads/main')),'no update to base branch');
});
test('new branch writer refuses a moved base branch before mutating Git objects',async()=>{
 const {api,calls}=setup((url,options,requests)=>{
  if(url.includes('/git/ref/heads/')&&requests.length>3)return {object:{sha:'f'.repeat(40)}};
 });
 const plan=await api.inspectDestination('secret','owner/repo','main','',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'secret','expository/zip-demo',()=>{}),/changed/);
 assert.equal(calls.filter(x=>x.options.method==='POST').length,0);
});

test('read-only explorer passes checked ZIP payload to a nonce-only companion URL',async()=>{
 const index=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
 const first=index.indexOf('const ZIP_PUBLISH_MAX='),last=index.indexOf('const ZIP_SHARE_MAX=',first);
 assert.ok(first>=0&&last>first,'ZIP handoff is present in normal viewer');
 let opened='',channel;
 class FakeChannel {
  constructor(name){this.name=name;this.packets=[];channel=this}
  postMessage(packet){this.packets.push(packet)}
  close(){}
 }
 const state={repo:'owner/private',branch:'dev/active',repoRequest:7};
 const ctx=vm.createContext({
  Blob,URL,BroadcastChannel:FakeChannel,crypto:require('node:crypto').webcrypto,state,
  zipChoice:()=>new Set(),unzipMember:async()=>new Blob(['verified']),
  location:{href:'https://gamesbyian.github.io/Expository/'},
  window:{open:(url)=>{opened=url}},status:()=>{},setTimeout:()=>{}
 });
 vm.runInContext(index.slice(first,last)+'\nglobalThis.publishZipForTest=publishZip;',ctx);
 const manifest={entries:[{name:'assets/screen.png',size:8}]};
 ctx.publishZipForTest(manifest,{name:'pictures.zip'});
 const url=new URL(opened);
 assert.equal(url.search,'');
 assert.equal(url.pathname,'/Expository/zip-publisher.html');
 assert.match(url.hash,/^#[0-9a-f]{32}$/);
 channel.onmessage({data:{type:'ready',nonce:url.hash.slice(1)}});
 await new Promise(resolve=>setImmediate(resolve));
 const bundle=channel.packets.find(p=>p.type==='bundle');
 assert.ok(bundle);
 assert.equal(bundle.entries[0].path,'assets/screen.png');
 assert.equal(await bundle.entries[0].blob.text(),'verified');
 assert.equal(bundle.repo,'owner/private');
 assert.equal(bundle.base,'dev/active');
 assert.doesNotMatch(opened,/owner|private|screen|credential|token/i,'private paths are not encoded into the URL')
});

test('publisher refuses a base branch that moves during blob upload, before new ref creation',async()=>{
 const {api,calls}=setup((url,options,requests)=>{
  if(url.includes('/git/ref/heads/main')&&requests.filter(x=>x.url.includes('/git/ref/heads/main')).length>=3)return {object:{sha:'f'.repeat(40)}};
 });
 const plan=await api.inspectDestination('secret','owner/repo','main','import',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'secret','expository/zip-late-race',()=>{}),/changed during upload/);
 assert.equal(calls.some(x=>x.url.endsWith('/git/blobs')&&x.options.method==='POST'),true,'the upload began');
 assert.equal(calls.some(x=>x.url.endsWith('/git/refs')&&x.options.method==='POST'),false,'branch reference was never created');
});
test('publishing token entry is frame-guarded',()=>{
 assert.match(html,/window\.self!==window\.top/);
 assert.match(html,/window\.open|window\.top/);
});

'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../zip-publisher.html'),'utf8');
const source=html.match(/<script>([\s\S]+?)<\/script>/)?.[1];
assert.ok(source,'Publisher inline source present');
const start=source.indexOf('const MAX_FILES='),end=source.indexOf('function renderIncoming(){',start);
assert.ok(start>=0&&end>start,'Publisher pure logic boundary present');
const SHA={head:'a'.repeat(40),tree:'b'.repeat(40),blob:'c'.repeat(40),newTree:'d'.repeat(40),commit:'e'.repeat(40)};
function setup(respond,cryptoImpl=require('node:crypto').webcrypto){
 const calls=[];
 const context=vm.createContext({Blob,URL,TextEncoder,TextDecoder,Uint8Array,crypto:cryptoImpl,FileReader:class{
  readAsDataURL(blob){blob.arrayBuffer().then(bytes=>{this.result='data:application/octet-stream;base64,'+Buffer.from(bytes).toString('base64');this.onload()}).catch(e=>this.onerror?.(e))}
 },fetch:async(url,options)=>{
  calls.push({url,options,body:options.body?JSON.parse(options.body):undefined});
  const answer=respond?respond(url,options,calls):undefined;
  // Synthetic response codes and real streamed JSON bodies, never real API access.
  if(answer?.__httpStatus)return new Response('{}',{status:answer.__httpStatus});
  if(answer?.__response)return answer.__response;
  return new Response(JSON.stringify(answer??mock(url,options,calls)),
   {status:200,headers:{'content-type':'application/json'}});
 }});
 vm.runInContext(source.slice(start,end)+'\nglobalThis.published={wellFormedUtf16,cleanPath,repoPath,branchPath,resolveEntries,findCollisions,githubCompareUrl,inspectDestination,writeNewBranch,verifyPublishedBranch,verifyPublishedTreeDelta,gitBlobIdentity,boundedGithubJson};',context);
 return {api:context.published,calls};
}
function mock(url,options,calls){
 if(url.includes('/git/ref/heads/expository/')){
  const branch=url.split('/git/ref/heads/')[1];
  return {ref:'refs/heads/'+branch,object:{sha:SHA.commit}};
 }
 if(url.includes('/git/ref/heads/'))return {ref:'refs/heads/main',object:{sha:SHA.head}};
 if(url.includes('/git/commits/'+SHA.head))return {sha:SHA.head,tree:{sha:SHA.tree}};
 if(url.includes('/git/commits/'+SHA.commit))return {
  sha:SHA.commit,tree:{sha:SHA.newTree},parents:[{sha:SHA.head}]
 };
 if(url.includes('/git/trees/'+SHA.tree+'?recursive=1'))
  return {sha:SHA.tree,truncated:false,tree:[
   {path:'already.txt',type:'blob',mode:'100644',sha:'f'.repeat(40)}
  ]};
 if(url.includes('/git/trees/'+SHA.newTree+'?recursive=1')){
  const treeWrite=calls.find(x=>x.options.method==='POST'&&x.url.endsWith('/git/trees'));
  const additions=treeWrite?.body.tree||[];
  const parents=new Set();
  for(const file of additions){
   let slash=file.path.indexOf('/');
   while(slash!==-1){
    parents.add(file.path.slice(0,slash));
    slash=file.path.indexOf('/',slash+1)
   }
  }
  return {sha:SHA.newTree,truncated:false,tree:[
   {path:'already.txt',type:'blob',mode:'100644',sha:'f'.repeat(40)},
   ...[...parents].map(path=>({path,type:'tree',mode:'040000',sha:'9'.repeat(40)})),
   ...additions
  ]};
 }
 if(url.endsWith('/git/blobs')){
  const source=Buffer.from(JSON.parse(options.body).content,'base64');
  const actual=require('node:crypto').createHash('sha1')
   .update(Buffer.from('blob '+source.length+'\0')).update(source).digest('hex');
  return {sha:actual};
 }
 if(url.endsWith('/git/trees'))return {sha:SHA.newTree};
 if(url.endsWith('/git/commits'))return {sha:SHA.commit};
 if(url.endsWith('/git/refs'))return {ref:options.body?JSON.parse(options.body).ref:'',object:{sha:SHA.commit}};
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
 assert.equal(api.cleanPath('é'.repeat(127)),true,'254 UTF-8 bytes is safe');
 assert.equal(api.cleanPath('é'.repeat(128)),false,'256 UTF-8 bytes exceeds one Git segment');
 const longUtf8Path=Array(16).fill('é'.repeat(120)).join('/');
 assert.equal(api.cleanPath(longUtf8Path),true,'under 4096 UTF-8 bytes can fit');
 assert.equal(api.cleanPath(longUtf8Path+'/'+longUtf8Path),false,'combined multibyte path exceeds total bound');
 assert.throws(()=>api.resolveEntries([file('foo.png'),file('FOO.PNG')],''),/duplicate/);
 assert.throws(()=>api.resolveEntries([file('é.png'),file('e\u0301.PNG')],''),/duplicate/);
 assert.throws(()=>api.resolveEntries([file('assets'),file('assets/logo.png')],''),/path conflict/);
 assert.throws(()=>api.resolveEntries([file('ASSETS'),file('assets/logo.png')],''),/path conflict/);
 assert.throws(()=>api.resolveEntries([file('é'),file('e\u0301/child.png')],''),/path conflict/);
 const validLongPrefix=Array(16).fill('a'.repeat(255)).join('/');
 assert.equal(api.cleanPath(validLongPrefix),true,'Every 255-byte component is valid');
 assert.throws(()=>api.resolveEntries([file('nested/file.txt')],validLongPrefix),/too long/,
  'Only the combined prefix and member crosses the full 4096-byte limit');
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'../bad'),/unsafe/);
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'test/'),/unsafe/);
 assert.equal(api.branchPath('feature/zip-import'),true);
 assert.equal(api.branchPath('bad..branch'),false);
 assert.equal(api.branchPath('topic@{head}'),false,'Git ref syntax must reject @{');
});
test('publisher rejects malformed UTF-16 paths before Git serialization and collision review',()=>{
 const {api}=setup();
 const high=String.fromCharCode(0xD800),low=String.fromCharCode(0xDC00);
 for(const value of [high,low,'file'+high+'name.txt','file'+low+'name.txt',
   'file'+high+'x'+low+'name.txt']){
  assert.equal(api.wellFormedUtf16(value),false);
  assert.equal(api.cleanPath(value),false);
  assert.equal(api.cleanPath('safe/'+value),false);
  assert.equal(api.branchPath('feature/'+value),false);
  assert.throws(()=>api.resolveEntries([file(value)],''),/Unsafe member path/);
  assert.throws(()=>api.resolveEntries([file('okay.txt')],value),/unsafe path/);
  assert.throws(()=>api.findCollisions([file('okay.txt')],
   {truncated:false,tree:[{path:value,type:'blob'}]}),/malformed/);
 }
 const pair=String.fromCharCode(0xD83D,0xDCC1);
 assert.equal(api.wellFormedUtf16(pair),true);
 assert.equal(api.cleanPath('nested/'+pair+'.png'),true);
 assert.equal(api.branchPath('feature/'+pair),true);
 const selected=api.resolveEntries([file(pair+'.png')],'nested');
 assert.equal(selected.length,1);
 assert.deepEqual([...api.findCollisions(selected,{truncated:false,
  tree:[{path:'already.txt',type:'blob'}]})],[]);
});

test('verified new-branch result offers a safe, separate GitHub PR comparison URL',()=>{
 const {api}=setup();
 assert.equal(api.githubCompareUrl('gamesbyian/Expository','main',
  'expository/zip-20261109-abc'),
  'https://github.com/gamesbyian/Expository/compare/main...expository/zip-20261109-abc?expand=1');
 assert.equal(api.githubCompareUrl('owner/repo','release/v2',
  'feature/photos'),
  'https://github.com/owner/repo/compare/release/v2...feature/photos?expand=1');
 assert.equal(api.githubCompareUrl('owner/repo','main','feature/#token'),
  'https://github.com/owner/repo/compare/main...feature/%23token?expand=1',
  'valid special characters in a Git ref are URL-encoded, not rejected');
 for(const [repo,base,branch] of [
  ['wrong/../repo','main','feature/new'],
  ['owner/repo','main','main'],
  ['owner/repo','main','topic?write=true'],
  ['owner/repo','../../main','expository/new'],
  ['owner/repo','main','feature/@{token}']
 ])assert.throws(()=>api.githubCompareUrl(repo,base,branch),/Invalid GitHub branch comparison/);
 assert.ok(source.indexOf('compare.href=githubCompareUrl') >
  source.indexOf('const result=await writeNewBranch'),
  'PR review link is rendered only after confirmed publication');
 assert.match(source,/compare.target='_blank';compare.rel='noopener noreferrer'/);
});

test('tree collision check catches files, directories and case folding',()=>{
 const {api}=setup();
 const files=api.resolveEntries([file('NEW.txt'),file('A/file.txt')],'');
 const collisions=api.findCollisions(files,{truncated:false,tree:[{path:'new.TXT',type:'blob'},{path:'A',type:'blob'}]});
 assert.deepEqual([...collisions],['NEW.txt','A/file.txt']);
 assert.throws(()=>api.findCollisions(files,{truncated:true,tree:[]}),/incomplete/);
 const descendant=api.resolveEntries([file('assets')],'');
 assert.deepEqual([...api.findCollisions(descendant,{truncated:false,
  tree:[{path:'assets/images/logo.png',type:'blob'}]})],['assets'],
  'An incoming blob cannot replace a parent folder of existing files');
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
test('publisher streaming JSON caps actual bytes even when GitHub omits Content-Length',async()=>{
 const {api}=setup();
 let cancelled=false;
 const response=new Response(new ReadableStream({
  start(controller){controller.enqueue(new TextEncoder().encode('{"tree":[1,2,3]}'))},
  cancel(){cancelled=true}
 }),{status:200});
 await assert.rejects(api.boundedGithubJson(response,7),/7-byte publisher limit/);
 assert.equal(cancelled,true,'oversized body reader must be cancelled');
});

test('publisher JSON accepts exact caps and refuses malformed or oversized responses',async()=>{
 const {api}=setup();
 const precise=new Response('{"x":1}',{status:200});
 const value=await api.boundedGithubJson(precise,7);
 assert.equal(value.x,1);
 const early=new Response('{}',{status:200,
  headers:{'Content-Length':'999'}});
 await assert.rejects(api.boundedGithubJson(early,2),/2-byte publisher limit/);
 const malformed=new Response('{"x":',{status:200});
 await assert.rejects(api.boundedGithubJson(malformed,10),/malformed JSON/);
 const invalidUtf8=new Response(Uint8Array.from([0xc0,0x80]),{status:200});
 await assert.rejects(api.boundedGithubJson(invalidUtf8,10));
 const noStream={headers:{get:()=>null},body:null,json:async()=>({x:1})};
 await assert.rejects(api.boundedGithubJson(noStream,10),
  /cannot enforce the GitHub response-size limit/);
});

test('oversized GitHub preflight tree refuses publishing before any POST',async()=>{
 const {api,calls}=setup((url)=>{
  if(url.includes('/git/trees/')&&url.includes('?recursive=1'))
   return {__response:new Response('{}',{status:200,
    headers:{'Content-Length':String(25*1024*1024)}})};
 });
 await assert.rejects(api.inspectDestination(
  'synthetic-sensitive-token','owner/repo','main','',[file('new.txt')]),
  /publisher limit/);
 assert.ok(calls.length>=3&&calls.every(x=>x.options.method==='GET'));
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
 assert.equal(result.verifiedEntries,2,'both uploaded Git object IDs were read back');
 const postRefReads=calls.filter(x=>x.options.method==='GET'&&
  (x.url.includes('/git/ref/heads/expository/')||
   x.url.includes('/git/commits/'+SHA.commit)||
   x.url.includes('/git/trees/'+SHA.newTree)));
 assert.equal(postRefReads.length,3,'read branch, commit and exact tree independently');
 const writes=calls.filter(x=>x.options.method==='POST');
 assert.deepEqual(writes.map(x=>x.url.split('/').at(-2)+'/'+x.url.split('/').at(-1)),['git/blobs','git/blobs','git/trees','git/commits','git/refs']);
 assert.equal(writes[3].body.parents[0],SHA.head);
 assert.equal(writes[4].body.ref,'refs/heads/expository/zip-demo');
 assert.ok(writes.every(x=>!x.url.includes('/git/refs/heads/main')),'no update to base branch');
});
test('published-tree comparison accepts only reviewed new paths and parent tree updates',()=>{
 const {api}=setup();
 const hex=n=>n.repeat(40);
 const original=[
  {path:'unchanged.txt',type:'blob',mode:'100755',sha:hex('1')},
  {path:'docs',type:'tree',mode:'040000',sha:hex('2')},
  {path:'docs/keep.txt',type:'blob',mode:'100644',sha:hex('3')},
  {path:'secrets',type:'tree',mode:'040000',sha:hex('4')},
  {path:'secrets/key.link',type:'blob',mode:'120000',sha:hex('5')},
  {path:'vendor',type:'commit',mode:'160000',sha:hex('6')}
 ];
 const added=[
  {path:'docs/new.txt',type:'blob',mode:'100644',sha:hex('7')},
  {path:'art/2026/img.png',type:'blob',mode:'100644',sha:hex('8')}
 ];
 const folders=[
  {path:'art',type:'tree',mode:'040000',sha:hex('9')},
  {path:'art/2026',type:'tree',mode:'040000',sha:hex('a')}
 ];
 const revised=original.map(entry=>entry.path==='docs'
  ?{...entry,sha:hex('b')}:entry);
 const start={truncated:false,tree:original};
 const next={truncated:false,tree:[...revised,...folders,...added]};
 assert.equal(api.verifyPublishedTreeDelta(start,next,added),2,
  'existing ancestor SHA may change to incorporate an authorized descendant');
 const reject=(name,modify,pattern)=>{
  assert.throws(()=>api.verifyPublishedTreeDelta(start,
   {truncated:false,tree:modify([...next.tree])},added),pattern,name)
 };
 reject('existing executable blob content changed',rows=>rows.map(entry=>
  entry.path==='unchanged.txt'?{...entry,sha:hex('0')}:entry),/pre-existing/);
 reject('original symlink replaced',rows=>rows.map(entry=>
  entry.path==='secrets/key.link'?{...entry,sha:hex('0')}:entry),/pre-existing/);
 reject('submodule reference changed',rows=>rows.map(entry=>
  entry.path==='vendor'?{...entry,sha:hex('0')}:entry),/pre-existing/);
 reject('unrelated existing directory changed',rows=>rows.map(entry=>
  entry.path==='secrets'?{...entry,sha:hex('0')}:entry),/pre-existing/);
 reject('original file disappeared',rows=>rows.filter(entry=>
  entry.path!=='docs/keep.txt'),/pre-existing/);
 reject('unreviewed extra file',rows=>[...rows,
  {path:'secret-copy.txt',type:'blob',mode:'100644',sha:hex('c')}],/unreviewed/);
 reject('unreviewed extra directory',rows=>[...rows,
  {path:'surprise',type:'tree',mode:'040000',sha:hex('c')}],/unreviewed/);
 reject('missing required added folder',rows=>rows.filter(entry=>
  entry.path!=='art/2026'),/required uploaded parent/);
 reject('uploaded file omitted',rows=>rows.filter(entry=>
  entry.path!=='art/2026/img.png'),/uploaded file/);
 reject('uploaded file modified',rows=>rows.map(entry=>
  entry.path==='docs/new.txt'?{...entry,sha:hex('c')}:entry),/uploaded file/);
 reject('invalid published object mode',rows=>rows.map(entry=>
  entry.path==='docs/keep.txt'?{...entry,mode:'040000'}:entry),/unverified object identities/);
 assert.throws(()=>api.verifyPublishedTreeDelta(
  {truncated:false,tree:[{...original[0],mode:'bad'}]},next,added),
  /unverified object identities/);
 assert.throws(()=>api.verifyPublishedTreeDelta(
  start,{truncated:true,tree:next.tree},added),/incomplete/);
});

test('an unexpected changed original sibling after branch creation stays uncertain',async()=>{
 const {api,calls}=setup((url,options,requests)=>{
  if(options.method==='GET'&&url.includes('/git/trees/'+SHA.newTree+'?recursive=1')){
   const contents=mock(url,options,requests);
   contents.tree[0]={...contents.tree[0],sha:'0'.repeat(40)};
   return contents
  }
 });
 const plan=await api.inspectDestination('private-token','owner/repo',
  'main','assets',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'private-token',
  'expository/zip-demo',()=>{}),
  error=>error.publishedButUnverified===true);
 assert.deepEqual(calls.filter(call=>call.options.method==='POST').map(call=>
  call.url.split('/').at(-1)),['blobs','trees','commits','refs'],
  'a conflicting published tree must never trigger new mutations');
});

test('a mismatched branch, parent, tree or file readback remains unverified, without replay',async()=>{
 const scenarios=[
  ['wrong branch identity',url=>url.includes('/git/ref/heads/expository/'),
   ()=>({ref:'refs/heads/expository/other',object:{sha:SHA.commit}})],
  ['wrong parent',url=>url.includes('/git/commits/'+SHA.commit),
   ()=>({sha:SHA.commit,tree:{sha:SHA.newTree},parents:[{sha:'f'.repeat(40)}]})],
  ['wrong tree identity',url=>url.includes('/git/trees/'+SHA.newTree+'?recursive=1'),
   ()=>({sha:'f'.repeat(40),truncated:false,tree:[]})],
  ['truncated tree',url=>url.includes('/git/trees/'+SHA.newTree+'?recursive=1'),
   ()=>({sha:SHA.newTree,truncated:true,tree:[]})],
  ['wrong uploaded object',url=>url.includes('/git/trees/'+SHA.newTree+'?recursive=1'),
   ()=>({sha:SHA.newTree,truncated:false,tree:[
    {path:'assets/one.txt',type:'blob',mode:'100644',sha:'f'.repeat(40)}
   ]})]
 ];
 for(const [name,match,answer] of scenarios){
  const {api,calls}=setup((url,options)=>options.method==='GET'&&match(url)?answer():undefined);
  const plan=await api.inspectDestination('private-token','owner/repo',
   'main','assets',[file('one.txt')]);
  await assert.rejects(
   api.writeNewBranch(plan,'private-token','expository/zip-demo',()=>{}),
   error=>error.publishedButUnverified===true&&
    /could not be independently confirmed/.test(error.message)&&
    !error.message.includes('private-token'),name);
  assert.equal(calls.filter(x=>x.options.method==='POST').length,4,
   name+' may not generate a second write after branch creation');
 }
});

test('uncertain GitHub branch-creation replies always require manual inspection',async()=>{
 const branch='expository/zip-demo';
 const scenarios=[
  ['HTTP 409',()=>({__httpStatus:409})],
  ['HTTP 422',()=>({__httpStatus:422})],
  ['HTTP 503',()=>({__httpStatus:503})],
  ['transport disconnect',()=>{throw new TypeError('synthetic connection lost') }],
  ['empty acknowledgement',()=>({})],
  ['wrong ref name',()=>({ref:'refs/heads/expository/other',object:{sha:SHA.commit}})],
  ['wrong commit SHA',()=>({ref:'refs/heads/'+branch,object:{sha:'f'.repeat(40)}})]
 ];
 for(const [scenario,answer] of scenarios){
  const {api,calls}=setup((url,options)=>
   options.method==='POST'&&url.endsWith('/git/refs')?answer():undefined);
  const plan=await api.inspectDestination('private-sensitive-token',
   'owner/repo','main','assets',[file('photo.png')]);
  await assert.rejects(
   api.writeNewBranch(plan,'private-sensitive-token',branch,()=>{}),
   error=>error.publishedButUnverified===true&&
    /branch may already exist/i.test(error.message)&&
    !error.message.includes('private-sensitive-token'),
   scenario+' must never claim that branch creation failed or was undone');
  const posts=calls.filter(call=>call.options.method==='POST');
  assert.deepEqual(posts.map(call=>call.url.split('/').at(-1)),
   ['blobs','trees','commits','refs'],scenario+' must not retry/ref-write again');
  assert.equal(calls.filter(call=>call.url.includes('/git/ref/heads/'+branch)).length,0,
   scenario+' must not silently reconcile or replay an uncertain ref');
 }
});

test('cancellation immediately before ref mutation remains a known no-ref outcome',async()=>{
 const controller=new AbortController();
 const {api,calls}=setup((url,options,requests)=>{
  if(options.method==='GET'&&url.endsWith('/git/ref/heads/main')&&
   requests.filter(call=>call.url.endsWith('/git/ref/heads/main')).length===3)
   controller.abort();
 });
 const plan=await api.inspectDestination('private-token','owner/repo',
  'main','',[file('fine.txt')]);
 await assert.rejects(
  api.writeNewBranch(plan,'private-token','expository/zip-cancel',()=>{},controller.signal),
  error=>error.publishedButUnverified!==true&&/stopped before branch creation/.test(error.message));
 assert.ok(calls.some(call=>call.url.endsWith('/git/commits')&&call.options.method==='POST'),
  'blob/tree/commit objects can be created without a branch ref');
 assert.equal(calls.some(call=>call.url.endsWith('/git/refs')&&call.options.method==='POST'),false,
  'no ref mutation request was sent when cancellation preceded it');
});

test('stop control explains a branch may remain after cancellation',()=>{
 assert.match(html,/id="cancel" disabled>Stop \(branch may remain\)<\/button>/);
 assert.match(source,/A GitHub branch may already exist; inspect it before another publication/);
 assert.match(source,/if\(e\.publishedButUnverified\)/);
 assert.match(source,/Inspect the possibly created branch/);
});

test('an unavailable post-creation GitHub read remains uncertain, never auto-retried',async()=>{
 const {api,calls}=setup((url,options)=>{
  if(options.method==='GET'&&url.includes('/git/ref/heads/expository/'))
   return {__httpStatus:403};
 });
 const plan=await api.inspectDestination('synthetic-token',
  'owner/repo','main','',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'synthetic-token',
  'expository/zip-demo',()=>{}),
  error=>error.publishedButUnverified===true);
 assert.equal(calls.filter(x=>x.options.method==='POST').length,4);
 assert.equal(calls.filter(x=>x.url.includes('/git/ref/heads/expository/')).length,1,
  'the post-ref read must never be automatically replayed');
});

test('local Git blob identities match independent git hash-object vectors',async()=>{
 const {api}=setup();
 assert.equal(await api.gitBlobIdentity(new Blob(['hello world\n'])),
  '3b18e512dba79e4c8300dd08aeb37f8e728b8dad');
 assert.equal(await api.gitBlobIdentity(new Blob([])),
  'e69de29bb2d1d6434b8b29ae775ad8c2e48c5391');
 assert.equal(await api.gitBlobIdentity(new Blob(['abc'])),
  'f2ba8f84ab5c1bce84a7b441cb1959cfc7093b7f');
});

test('GitHub blob SHA disagreement stops before creating a tree, commit or branch',async()=>{
 const {api,calls}=setup((url,options)=>{
  if(options.method==='POST'&&url.endsWith('/git/blobs'))
   return {sha:'f'.repeat(40)};
 });
 const plan=await api.inspectDestination('synthetic-token','owner/repo',
  'main','assets',[file('hello.txt','hello world\n')]);
 await assert.rejects(
  api.writeNewBranch(plan,'synthetic-token','expository/zip-demo',()=>{}),
  /blob identity differs from the selected file bytes/);
 assert.deepEqual(calls.filter(x=>x.options.method==='POST').map(x=>x.url.split('/').at(-1)),
  ['blobs'],'no tree, commit or ref write after inconsistent blob');
});

test('a browser without SHA-1 digest support cannot start publication',async()=>{
 const {api,calls}=setup(undefined,{});
 const plan=await api.inspectDestination('synthetic-token','owner/repo',
  'main','',[file('safe.txt')]);
 await assert.rejects(
  api.writeNewBranch(plan,'synthetic-token','expository/zip-demo',()=>{}),
  /cannot verify Git blob identities/);
 assert.ok(calls.every(x=>x.options.method==='GET'),
  'missing local verification support must never initiate a write');
});

test('new branch writer refuses a moved base branch before mutating Git objects',async()=>{
 const {api,calls}=setup((url,options,requests)=>{
  if(url.includes('/git/ref/heads/')&&requests.length>3)return {object:{sha:'f'.repeat(40)}};
 });
 const plan=await api.inspectDestination('secret','owner/repo','main','',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'secret','expository/zip-demo',()=>{}),/changed/);
 assert.equal(calls.filter(x=>x.options.method==='POST').length,0);
});

test('401/403/429 preflight failures never issue any GitHub writes or echo credentials',async()=>{
 for(const statusCode of [401,403,429]){
  const {api,calls}=setup((url,options)=>{
   if(options.method==='GET'&&url.includes('/git/ref/heads/'))
    return {__httpStatus:statusCode};
  });
  await assert.rejects(api.inspectDestination(
   'synthetic-sensitive-token','owner/repo','main','',[file('safe.txt')]),
   error=>error.message.includes('HTTP '+statusCode)&&
    !error.message.includes('synthetic-sensitive-token'));
  assert.ok(calls.length>0&&calls.every(x=>x.options.method==='GET'));
 }
});

test('upload rate limit after review never creates a Git branch ref',async()=>{
 const {api,calls}=setup((url,options)=>{
  if(options.method==='POST'&&url.endsWith('/git/blobs'))
   return {__httpStatus:429};
 });
 const plan=await api.inspectDestination('synthetic-token','owner/repo',
  'main','nested',[file('new.txt')]);
 await assert.rejects(api.writeNewBranch(plan,'synthetic-token',
  'expository/zip-rate-limit',()=>{}),/HTTP 429/);
 assert.equal(calls.some(x=>x.url.endsWith('/git/refs')&&
  x.options.method==='POST'),false);
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

function setupReviewUi(responder){
 const elements=new Map(),calls=[];
 const node=()=>({
  textContent:'',value:'',disabled:false,children:[],
  append(...items){this.children.push(...items)},
  replaceChildren(...items){this.children=items}
 });
 const get=id=>{if(!elements.has(id))elements.set(id,node());return elements.get(id)};
 const ctx=vm.createContext({
  Blob,URL,TextEncoder,TextDecoder,Uint8Array,AbortController,
  crypto:require('node:crypto').webcrypto,
  document:{getElementById:get,createElement:()=>node()},
  fetch:async(url,options)=>{
   calls.push({url,options});
   const answer=await responder(url,options);
   return new Response(JSON.stringify(answer??mock(url,options,calls)),
    {status:200,headers:{'content-type':'application/json'}})
  }
 });
 const endUi=source.indexOf('const nonce=location.hash.slice(1);');
 assert.ok(endUi>end,'Publisher UI boundary exists');
 vm.runInContext(source.slice(start,endUi)+
  '\nglobalThis.reviewUi={startReview,getReview:()=>review,setIncoming:x=>{incoming=x},getBusy:()=>busy};',ctx);
 get('repo').value='owner/repo';
 get('base').value='main';
 get('prefix').value='assets';
 get('token').value='synthetic-only-token';
 ctx.reviewUi.setIncoming({entries:[file('safe.txt')]});
 return {ui:ctx.reviewUi,get,calls}
}
test('in-flight destination review cannot revive after token edit, even if GitHub ignores abort',async()=>{
 let release,signal;
 const {ui,get,calls}=setupReviewUi(async(url,options)=>{
  if(url.includes('/git/ref/heads/main')){
   signal=options.signal;
   await new Promise(resolve=>{release=resolve});
   return {object:{sha:SHA.head}}
  }
 });
 const pending=ui.startReview();
 for(let n=0;n<5&&!release;n++)await new Promise(resolve=>setImmediate(resolve));
 assert.equal(typeof release,'function','read-only GitHub branch request is suspended');
 assert.equal(get('review').disabled,true);
 get('token').value='different-token';
 get('token').oninput();
 assert.equal(signal.aborted,true,'editing the token aborts the old review request');
 get('token').value='synthetic-only-token'; // Reverting text cannot resurrect old authority.
 release();await pending;
 assert.equal(ui.getReview(),null,'stale response must not reauthorize publication');
 assert.equal(get('publish').disabled,true);
 assert.equal(get('destinations').children.length,0);
 assert.equal(ui.getBusy(),false);
 assert.ok(calls.every(x=>x.options.method==='GET'),'review never performs writes');
});
test('unchanged destination review enables confirmation, later edits revoke it',async()=>{
 const {ui,get,calls}=setupReviewUi(async()=>undefined);
 await ui.startReview();
 assert.equal(get('publish').disabled,false);
 assert.ok(ui.getReview());
 assert.equal(get('destinations').children.length,2,'summary and reviewed file shown');
 get('prefix').value='other-folder';
 get('prefix').oninput();
 assert.equal(get('publish').disabled,true);
 assert.equal(ui.getReview(),null);
 assert.equal(get('destinations').children.length,0);
 assert.ok(calls.length===3&&calls.every(x=>x.options.method==='GET'));
});

test('publishing token entry is frame-guarded',()=>{
 assert.match(html,/window\.self!==window\.top/);
 assert.match(html,/window\.open|window\.top/);
});

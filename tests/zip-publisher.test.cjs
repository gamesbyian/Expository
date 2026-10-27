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
 assert.throws(()=>api.resolveEntries([file('foo.png'),file('FOO.PNG')],''),/duplicate/);
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'../bad'),/unsafe/);
 assert.throws(()=>api.resolveEntries([file('safe.txt')],'test/'),/unsafe/);
 assert.equal(api.branchPath('feature/zip-import'),true);
 assert.equal(api.branchPath('bad..branch'),false);
});
test('tree collision check catches files, directories and case folding',()=>{
 const {api}=setup();
 const files=api.resolveEntries([file('NEW.txt'),file('A/file.txt')],'');
 const collisions=api.findCollisions(files,{truncated:false,tree:[{path:'new.TXT',type:'blob'},{path:'A',type:'blob'}]});
 assert.deepEqual([...collisions],['NEW.txt','A/file.txt']);
 assert.throws(()=>api.findCollisions(files,{truncated:true,tree:[]}),/incomplete/);
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

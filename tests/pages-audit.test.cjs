'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {pagesBase,auditPages,summary,PUBLISHED_PATHS}=require('../scripts/pages-audit.cjs');
const page=(s,status=200)=>new Response(s,{status});
test('Pages audit uses canonical bytes and reports only explicit public path statuses',async()=>{
 const seen=[],local='<html><title>Expository</title></html>';
 const fetchImpl=async(url,opts)=>{
  seen.push({url,method:opts.method,redirect:opts.redirect});
  if(opts.method==='GET')return page(local);
  return page('',url.endsWith('/AGENTS.md')?200:404)
 };
 const result=await auditPages({localHtml:local,fetchImpl});
 assert.equal(result.parity,true);
 assert.equal(result.bytes.source,local.length);
 assert.equal(result.sourceSha256,result.deployedSha256);
 assert.equal(seen.length,1+PUBLISHED_PATHS.length);
 assert.equal(seen[0].method,'GET');assert.equal(seen[0].redirect,'error');
 assert.ok(seen[0].url.includes('expository_parity='));
 assert.ok(seen.slice(1).every(x=>x.method==='HEAD'&&x.redirect==='error'));
 assert.equal(result.publicPaths[0].publiclyServed,true);
 assert.equal(result.publicPaths.at(-1).publiclyServed,false);
 assert.match(summary(result),/PASS/);
});
test('stale or modified live Pages is never falsely marked as matching source',async()=>{
 const fetchImpl=async(_url,opts)=>opts.method==='GET'?page('<html>old</html>'):page('',404);
 const r=await auditPages({localHtml:'<html>new</html>',fetchImpl,paths:['README.md']});
 assert.equal(r.parity,false);
 assert.notEqual(r.sourceSha256,r.deployedSha256);
 assert.match(summary(r),/FAIL/);
});
test('Pages audit rejects credential-bearing, HTTP and traversal URLs',async()=>{
 for(const value of ['http://example.com/repo/','https://user:token@example.com/repo/','https://example.com/repo?secret=1','https://example.com/repo#fragment','https://example.com/repo'])assert.throws(()=>pagesBase(value));
 await assert.rejects(auditPages({localHtml:'ok',paths:['../private'],fetchImpl:async()=>page('ok')}),/relative public paths/);
 await assert.rejects(auditPages({localHtml:'ok',fetchImpl:async()=>page('missing',404)}),/unreachable/);
});

test('canonical ZIP publisher needs its own independent exact-byte Pages GET',async()=>{
 const index='<html>explorer</html>',publisher='<html>isolated publisher</html>';
 const seen=[];
 const fetchImpl=async(url,opts)=>{
  seen.push({url,method:opts.method,redirect:opts.redirect});
  if(opts.method!=='GET')throw Error('Unexpected path check');
  return page(new URL(url).pathname.endsWith('/zip-publisher.html')?publisher:index);
 };
 const report=await auditPages({localHtml:index,localPublisherHtml:publisher,fetchImpl,paths:[]});
 assert.equal(report.parity,true);
 assert.equal(report.publisher.parity,true);
 assert.equal(report.publisher.path,'zip-publisher.html');
 assert.equal(report.publisher.bytes.source,publisher.length);
 assert.equal(seen.length,2);
 assert.equal(seen[1].method,'GET');assert.equal(seen[1].redirect,'error');
 assert.ok(seen[1].url.includes('/Expository/zip-publisher.html?expository_parity='));
 assert.match(summary(report),/ZIP publisher: \*\*PASS:/);
});
test('stale or missing live ZIP publisher is independently refused',async()=>{
 const index='<html>current explorer</html>',publisher='<html>current writer</html>';
 const fake=async(url,opts)=>{
  if(opts.method!=='GET')throw Error('Unexpected HEAD');
  return page(new URL(url).pathname.endsWith('/zip-publisher.html')?'<html>old writer</html>':index);
 };
 const mismatch=await auditPages({localHtml:index,localPublisherHtml:publisher,fetchImpl:fake,paths:[]});
 assert.equal(mismatch.parity,true);
 assert.equal(mismatch.publisher.parity,false);
 assert.match(summary(mismatch),/ZIP publisher: \*\*FAIL:/);
 await assert.rejects(auditPages({localHtml:index,localPublisherHtml:publisher,paths:[],
  fetchImpl:async(url)=>page(new URL(url).pathname.endsWith('/zip-publisher.html')?'missing':index,
    new URL(url).pathname.endsWith('/zip-publisher.html')?404:200)}),/ZIP publisher unreachable/);
 await assert.rejects(auditPages({localHtml:index,localPublisherHtml:42,
  fetchImpl:async()=>page(index),paths:[]}),/Canonical ZIP publisher bytes required/);
});

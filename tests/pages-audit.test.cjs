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

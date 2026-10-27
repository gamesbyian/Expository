'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const start=source.indexOf('const ZIP_MAX_BYTES='),end=source.indexOf('const $=id=>',start);
assert.ok(start>=0&&end>start,'ZIP reader is embedded in the browser application');
const context=vm.createContext({Blob,Uint8Array,DataView,TextDecoder,Response,DecompressionStream,withMediaType:b=>b});
vm.runInContext(source.slice(start,end)+'\nglobalThis.exposed={zipManifest,unzipMember,zipCRC};',context);
const {zipManifest,unzipMember,zipCRC}=context.exposed;
function archive(name='pictures/hello.txt',body='Hello from ZIP',crcOverride){
 const data=Buffer.from(body),filename=Buffer.from(name),crc=crcOverride??zipCRC(data);
 const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50,0);local.writeUInt32LE(crc,14);local.writeUInt32LE(data.length,18);local.writeUInt32LE(data.length,22);local.writeUInt16LE(filename.length,26);
 const center=Buffer.alloc(46);center.writeUInt32LE(0x02014b50,0);center.writeUInt32LE(crc,16);center.writeUInt32LE(data.length,20);center.writeUInt32LE(data.length,24);center.writeUInt16LE(filename.length,28);center.writeUInt32LE(0,42);
 const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50,0);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(center.length+filename.length,12);end.writeUInt32LE(local.length+filename.length+data.length,16);
 return new Blob([local,filename,data,center,filename,end]);
}
test('ZIP manifest includes navigable member paths and extracts verified bytes',async()=>{
 const manifest=await zipManifest(archive());
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,'pictures/hello.txt');
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'Hello from ZIP');
});
test('ZIP reader discards path traversal names',async()=>{
 const manifest=await zipManifest(archive('../escape.txt'));
 assert.equal(manifest.entries.length,0);
 assert.equal(manifest.skipped,1);
});
test('ZIP reader detects bad CRC instead of exporting corrupted data',async()=>{
 const manifest=await zipManifest(archive('safe.txt','hi',0));
 await assert.rejects(unzipMember(manifest,manifest.entries[0]),/checksum/);
});
test('ZIP reader rejects files above configured archive cap',async()=>{
 assert.throws(()=>zipManifest(new Blob([new Uint8Array(24*1024*1024+1)])),/24 MB/);
});

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
vm.runInContext(source.slice(start,end)+'\nglobalThis.exposed={zipManifest,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected};',context);
const {zipManifest,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected}=context.exposed;
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

async function mutate(blob,edit){
 const data=Buffer.from(await blob.arrayBuffer()),central=data.indexOf(Buffer.from([0x50,0x4b,0x01,0x02]));
 assert.ok(central>0,'test fixture contains a central directory');
 edit(data,central);
 return new Blob([data]);
}
test('ZIP reader excludes symlinks rather than treating their payload as a regular file',async()=>{
 const blob=await mutate(archive('link','target'),(data,c)=>{
  data.writeUInt16LE(3<<8,c+4);
  data.writeUInt32LE((0o120777<<16)>>>0,c+38);
 });
 const manifest=await zipManifest(blob);
 assert.equal(manifest.entries.length,0);
 assert.equal(manifest.skipped,1);
});
test('ZIP reader refuses mismatched local and central file names',async()=>{
 const blob=await mutate(archive('item.txt','x'),data=>{data.write('evil.txt',30,'utf8')});
 const manifest=await zipManifest(blob);
 assert.equal(manifest.entries.length,0);
});
test('ZIP reader excludes encrypted entries at index time',async()=>{
 const blob=await mutate(archive('secret.txt','x'),data=>{data.writeUInt16LE(1,6)});
 const manifest=await zipManifest(blob);
 assert.equal(manifest.entries.length,0);
});
test('portable folder-export path guard excludes reserved and hazardous names',()=>{
 for(const name of ['CON.txt','folder/aux','nested/picture?.png','a/trailing.','foo/bar:bad']){
  assert.equal(zipSafeForDevice(name),false,name);
 }
 assert.equal(zipSafeForDevice('nested/print-01.png'),true);
});
test('ZIP parser rejects a mismatched central directory size',async()=>{
 const blob=await mutate(archive(),data=>{
  const pos=data.length-22;data.writeUInt32LE(data.readUInt32LE(pos+12)+1,pos+12);
 });
 await assert.rejects(zipManifest(blob),/central directory/);
});

test('ZIP share action calls navigator.share within the original click, with prepared files',()=>{
 let shared=false;
 context.navigator={canShare({files}){return files[0]==='already-verified'},share({files}){shared=files[0]==='already-verified';return Promise.resolve()}};
 context.status=()=>{};
 zipShareSelected(['already-verified']);
 assert.equal(shared,true,'no asynchronous work precedes invoking the native share sheet');
});
test('ZIP share action refuses browser-declined file batches',()=>{
 let shared=false,message='';
 context.navigator={canShare(){return false},share(){shared=true;return Promise.resolve()}};
 context.status=s=>{message=s};
 zipShareSelected(['unsupported']);
 assert.equal(shared,false);
 assert.match(message,/cannot share/);
});
test('ZIP safety guard rejects case-insensitive special device paths',()=>{
 for(const name of ['a/PRN.jpg','LPT9','weird<name>.txt'])assert.equal(zipSafeForDevice(name),false);
});

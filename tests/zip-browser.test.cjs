'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const start=source.indexOf('const ZIP_MAX_BYTES='),end=source.indexOf('const $=id=>',start);
assert.ok(start>=0&&end>start,'ZIP reader is embedded in the browser application');
const context=vm.createContext({Blob,Uint8Array,DataView,TextDecoder,TextEncoder,Response,DecompressionStream,withMediaType:b=>b});
vm.runInContext(source.slice(start,end)+'\nglobalThis.exposed={zipManifest,zipDecodeName,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected};',context);
const {zipManifest,zipDecodeName,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected}=context.exposed;
function archive(name='pictures/hello.txt',body='Hello from ZIP',crcOverride,flags=0){
 const data=Buffer.from(body),filename=Buffer.isBuffer(name)?name:Buffer.from(name),crc=crcOverride??zipCRC(data);
 const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50,0);local.writeUInt16LE(flags,6);local.writeUInt32LE(crc,14);local.writeUInt32LE(data.length,18);local.writeUInt32LE(data.length,22);local.writeUInt16LE(filename.length,26);
 const center=Buffer.alloc(46);center.writeUInt32LE(0x02014b50,0);center.writeUInt16LE(flags,8);center.writeUInt32LE(crc,16);center.writeUInt32LE(data.length,20);center.writeUInt32LE(data.length,24);center.writeUInt16LE(filename.length,28);center.writeUInt32LE(0,42);
 const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50,0);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(center.length+filename.length,12);end.writeUInt32LE(local.length+filename.length+data.length,16);
 return new Blob([local,filename,data,center,filename,end]);
}
function archiveMany(members){
 const locals=[],centers=[];let localOffset=0,centralSize=0;
 for(const member of members){
  const filename=Buffer.isBuffer(member.name)?member.name:Buffer.from(member.name),
   data=Buffer.from(member.body||'payload'),crc=zipCRC(data),flags=member.flags||0;
  const local=Buffer.alloc(30),center=Buffer.alloc(46);
  local.writeUInt32LE(0x04034b50,0);local.writeUInt16LE(flags,6);
  local.writeUInt32LE(crc,14);local.writeUInt32LE(data.length,18);
  local.writeUInt32LE(data.length,22);local.writeUInt16LE(filename.length,26);
  center.writeUInt32LE(0x02014b50,0);center.writeUInt16LE(flags,8);
  center.writeUInt32LE(crc,16);center.writeUInt32LE(data.length,20);
  center.writeUInt32LE(data.length,24);center.writeUInt16LE(filename.length,28);
  center.writeUInt32LE(localOffset,42);
  locals.push(local,filename,data);centers.push(center,filename);
  localOffset+=local.length+filename.length+data.length;
  centralSize+=center.length+filename.length;
 }
 const end=Buffer.alloc(22);
 end.writeUInt32LE(0x06054b50,0);
 end.writeUInt16LE(members.length,8);end.writeUInt16LE(members.length,10);
 end.writeUInt32LE(centralSize,12);end.writeUInt32LE(localOffset,16);
 return new Blob([...locals,...centers,end]);
}
test('ZIP manifest does not expose NFC-equivalent paths as separate extractable files',async()=>{
 const legacy=Buffer.from([0x64,0x69,0x72,0x2f,0x82,0x2e,0x74,0x78,0x74]);
 const manifest=await zipManifest(archiveMany([
  {name:legacy,flags:0,body:'legacy payload'},
  {name:'DIR/e\\u0301.TXT',flags:0x0800,body:'collision payload'},
  {name:'other/independent.txt',flags:0x0800,body:'independent payload'}
 ]));
 assert.deepEqual(manifest.entries.map(x=>x.name),['dir/é.txt','other/independent.txt']);
 assert.equal(manifest.skipped,1,'canonical alias is excluded at source review');
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'legacy payload');
 assert.equal(await (await unzipMember(manifest,manifest.entries[1])).text(),'independent payload');
});
test('ZIP manifest includes navigable member paths and extracts verified bytes',async()=>{
 const manifest=await zipManifest(archive());
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,'pictures/hello.txt');
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'Hello from ZIP');
});
test('legacy ZIP CP437 names preserve accented letters and extracted bytes',async()=>{
 // Legacy tools set no UTF-8 flag; 0x82 is CP437 é, invalid standalone UTF-8.
 const cp437=Buffer.from([0x63,0x61,0x66,0x82,0x2e,0x74,0x78,0x74]);
 const manifest=await zipManifest(archive(cp437,'correct payload'));
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,'café.txt');
 assert.equal(manifest.skipped,0);
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'correct payload');
 const last=zipDecodeName(new Uint8Array([0xff]),0);
 assert.equal(last.length,1);
 assert.equal(last.charCodeAt(0),0xa0,'CP437 0xFF occupies one code point');
});
test('explicit UTF-8 ZIP flag preserves Unicode filenames',async()=>{
 const name='ユニレーサーズ/écho.txt';
 const manifest=await zipManifest(archive(name,'content',undefined,0x0800));
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,name);
 assert.equal(manifest.skipped,0);
});
test('declared UTF-8 ZIP with malformed bytes is skipped rather than renamed',async()=>{
 const malformed=Buffer.from([0x6e,0x61,0x6d,0x65,0xc3,0x28,0x2e,0x74,0x78,0x74]);
 const manifest=await zipManifest(archive(malformed,'secret',undefined,0x0800));
 assert.equal(manifest.entries.length,0,'invalid UTF-8 must not produce replacement-character filenames');
 assert.equal(manifest.skipped,1);
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
test('ZIP comment containing a second end-marker signature keeps the real archive',async()=>{
 // ZIP comments may contain arbitrary bytes, including a complete-looking
 // PK 05 06 sequence 22 bytes before EOF. The signature is not authority.
 const data=Buffer.from(await archive('old/photos.txt','kept bytes').arrayBuffer());
 const fakeEnd=Buffer.alloc(22);
 fakeEnd.writeUInt32LE(0x06054b50,0);
 data.writeUInt16LE(fakeEnd.length,data.length-2);
 const manifest=await zipManifest(new Blob([data,fakeEnd]));
 assert.equal(manifest.entries.length,1,'valid central entry must survive a fake marker in the comment');
 assert.equal(manifest.entries[0].name,'old/photos.txt');
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'kept bytes');
});
test('ZIP parser rejects multi-disk claims even with an otherwise valid directory',async()=>{
 const blob=await mutate(archive('safe.txt'),data=>{
  const eocd=data.length-22;
  data.writeUInt16LE(1,eocd+4);
 });
 await assert.rejects(zipManifest(blob),/multi-disk/);
});
test('ZIP parser rejects an EOCD whose comment length disagrees with the bytes',async()=>{
 const blob=await mutate(archive('safe.txt'),data=>{
  data.writeUInt16LE(2,data.length-2);
 });
 await assert.rejects(zipManifest(blob),/ZIP comment|central directory/);
});
test('local ZIP extraction refuses version-control internals and path escape tricks',()=>{
 for(const name of [
  '.git/hooks/post-checkout','repo/.GIT/config','projects/.svn/wc.db',
  '.hg/hgrc','sub\\\\path.txt','a/b\\\\c.txt','folder/CON.txt'
 ]){
  assert.equal(zipSafeForDevice(name),false,name+' must not be written through a picked directory');
 }
 assert.equal(zipSafeForDevice('archive/.gitkeep'),true,
  'an ordinary filename with a VCS-like prefix is not itself control metadata');
 assert.equal(zipSafeForDevice('package/.github/workflows/check.yml'),true,
  'ordinary GitHub workflow files are regular archived content');
});
test('local ZIP extraction verifies UTF-8 byte limits on every path component',()=>{
 assert.equal(zipSafeForDevice('é'.repeat(127)+'.txt'),false,
  '258 UTF-8 bytes exceed a portable 255-byte component');
 assert.equal(zipSafeForDevice('é'.repeat(125)+'.txt'),true,
  'a portable 254-byte component remains accepted');
 const oversized=Array(17).fill('a'.repeat(245)).join('/');
 assert.equal(zipSafeForDevice(oversized),false,
  'overall output path exceeding 4096 bytes is refused');
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

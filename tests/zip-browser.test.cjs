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
vm.runInContext(source.slice(start,end)+'\nglobalThis.exposed={zipManifest,zipDecodeName,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected,zipExtractFolder};',context);
const {zipManifest,zipDecodeName,unzipMember,zipCRC,zipSafeForDevice,zipShareSelected,zipExtractFolder}=context.exposed;
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
  {name:'DIR/e'+String.fromCharCode(0x0301)+'.TXT',flags:0x0800,body:'collision payload'},
  {name:'other/independent.txt',flags:0x0800,body:'independent payload'}
 ]));
 assert.deepEqual([...manifest.entries].map(x=>x.name),['dir/é.txt','other/independent.txt']);
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
function extractionFixture(){
 const operations=[],written=new Map(),progress={textContent:''},button={disabled:false,after(){}};
 const folder=(prefix='')=>({
  async getDirectoryHandle(name,opts){
   operations.push({type:'directory',path:prefix+name,create:opts?.create===true});
   return folder(prefix+name+'/')
  },
  async getFileHandle(name,opts){
   operations.push({type:'file',path:prefix+name,create:opts?.create===true});
   if(!opts?.create)throw Object.assign(new Error('not found'),{name:'NotFoundError'});
   return {async createWritable(){
    operations.push({type:'writable',path:prefix+name});
    return {
     async write(blob){written.set(prefix+name,await blob.text())},
     async close(){operations.push({type:'close',path:prefix+name})},
     async abort(){operations.push({type:'abort',path:prefix+name})}
    }
   }}
  }
 });
 let message='';
 context.window={showDirectoryPicker:async()=>folder()};
 context.elem=()=>({disabled:false,remove(){}});
 context.status=value=>{message=value};
 return {operations,written,progress,button,get message(){return message}}
}
test('CRC failure never creates empty destination folders during ZIP extraction',async()=>{
 const manifest=await zipManifest(archive('nested/album/broken.txt','corrupt contents',0));
 const fixture=extractionFixture();
 await zipExtractFolder(manifest,manifest.entries,fixture.button,fixture.progress);
 assert.deepEqual(fixture.operations,[],'corrupt member must not invoke any directory/file handle');
 assert.equal(fixture.written.size,0);
 assert.match(fixture.message,/0\/1 files saved.*1 failed\/unsupported/);
 assert.equal(fixture.button.disabled,false,'extraction UI recovers after a failed member');
});
test('verified nested ZIP member still creates the intended destination and writes exact bytes',async()=>{
 const manifest=await zipManifest(archive('nested/album/photo.txt','verified contents'));
 const fixture=extractionFixture();
 await zipExtractFolder(manifest,manifest.entries,fixture.button,fixture.progress);
 assert.equal(fixture.written.get('nested/album/photo.txt'),'verified contents');
 assert.deepEqual(fixture.operations.map(x=>x.type),
  ['directory','directory','file','file','writable','close']);
 assert.match(fixture.message,/1\/1 files saved/);
 assert.equal(fixture.button.disabled,false);
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
test('invalid local entries cannot consume the aggregate budget of valid ZIP members',async()=>{
 const members=Array.from({length:9},(_,i)=>({name:'spoof-'+i+'.txt',body:'broken'}));
 members.push({name:'good.txt',body:'preserved'});
 const data=Buffer.from(await archiveMany(members).arrayBuffer());
 const eocd=data.length-22,central=data.readUInt32LE(eocd+16);
 let cursor=central;
 for(let i=0;i<9;i++){
  // Central-directory record advertises a valid individual 16 MiB deflate
  // entry, but the local header is STORED and fails the mandatory check.
  data.writeUInt16LE(8,cursor+10);
  data.writeUInt32LE(16*1024*1024,cursor+24);
  cursor+=46+data.readUInt16LE(cursor+28)+data.readUInt16LE(cursor+30)+data.readUInt16LE(cursor+32);
 }
 const manifest=await zipManifest(new Blob([data]));
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,'good.txt');
 assert.equal(manifest.skipped,9);
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'preserved');
});
test('ZIP member bytes cannot overlap the central-directory records',async()=>{
 const source=await archive('file.txt','x').arrayBuffer();
 const data=Buffer.from(source);
 const central=data.readUInt32LE(data.length-22+16);
 // The stored file is one byte long. Advertise two bytes, crossing into
 // the central-directory header's first P byte. The entry must be rejected
 // at manifest time, before any user extraction or checksum attempt.
 data.writeUInt32LE(2,central+20);
 data.writeUInt32LE(2,central+24);
 const manifest=await zipManifest(new Blob([data]));
 assert.equal(manifest.entries.length,0);
 assert.equal(manifest.skipped,1);
});
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
test('ZIP local and central filenames must match in original bytes, not only after slash normalization',async()=>{
 const blob=await mutate(archive('folder/item.txt','original'),data=>{
  data.write('folder\\item.txt',30,'utf8');
 });
 const manifest=await zipManifest(blob);
 assert.equal(manifest.entries.length,0);
 assert.equal(manifest.skipped,1,'changed raw local filename is untrusted even when display path matches');
});
test('ZIP local CRC and sizes agree with central records when no data descriptor is declared',async()=>{
 for(const field of [14,18,22]){
  const blob=await mutate(archive('stable.txt','verified bytes'),data=>{
   data.writeUInt32LE((data.readUInt32LE(field)^1)>>>0,field);
  });
  const manifest=await zipManifest(blob);
  assert.equal(manifest.entries.length,0,'local header disagreement at offset '+field+' must be refused');
  assert.equal(manifest.skipped,1);
 }
});
test('valid bit-three ZIP descriptor retains payload when local CRC and sizes are placeholders',async()=>{
 const seed=Buffer.from(await archive('streamed.txt','descriptor-backed',undefined,0x0008).arrayBuffer());
 const oldEocd=seed.length-22,oldCentral=seed.readUInt32LE(oldEocd+16);
 const descriptor=Buffer.alloc(16);
 descriptor.writeUInt32LE(0x08074b50,0);
 descriptor.writeUInt32LE(seed.readUInt32LE(14),4);
 descriptor.writeUInt32LE(seed.readUInt32LE(18),8);
 descriptor.writeUInt32LE(seed.readUInt32LE(22),12);
 const data=Buffer.concat([seed.subarray(0,oldCentral),descriptor,seed.subarray(oldCentral)]);
 for(const field of [14,18,22])data.writeUInt32LE(0,field);
 data.writeUInt32LE(oldCentral+descriptor.length,data.length-22+16);
 const manifest=await zipManifest(new Blob([data]));
 assert.equal(manifest.entries.length,1);
 assert.equal(manifest.entries[0].name,'streamed.txt');
 assert.equal(await (await unzipMember(manifest,manifest.entries[0])).text(),'descriptor-backed');
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

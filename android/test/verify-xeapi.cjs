const fs=require('fs'),crypto=require('crypto'),assert=require('assert');
const vectors=JSON.parse(fs.readFileSync(require('path').join(__dirname,'vectors.json')));
const text=fs.readFileSync(process.argv[2],'utf8');
const request=JSON.parse(text.match(/INSTRUMENTATION_RESULT: xeapi=(.*)/)[1]);
const privateKey=crypto.createPrivateKey({key:Buffer.from(vectors.privateKey,'base64'),type:'pkcs8',format:'der'});
const S=Buffer.from(request.S,'base64'),pub=S.subarray(0,32),iv=S.subarray(32,44);
const peer=crypto.createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b656e032100','hex'),pub]),type:'spki',format:'der'});
const shared=crypto.diffieHellman({privateKey,publicKey:peer});
const prk=crypto.createHmac('sha256',Buffer.alloc(32)).update(shared).digest();
const aeskey=crypto.createHmac('sha256',prk).update(Buffer.concat([pub,Buffer.from([1])])).digest().subarray(0,16);
const dec=crypto.createDecipheriv('aes-128-gcm',aeskey,iv);dec.setAuthTag(S.subarray(-16));
const session=Buffer.concat([dec.update(S.subarray(44,-16)),dec.final()]).toString().split('|');
assert.equal(session[1],'android');assert.equal(session[2],'test-sk');
function ecb(key,value){const c=crypto.createDecipheriv('aes-'+key.length*8+'-ecb',key,null);return Buffer.concat([c.update(value),c.final()]);}
const staticKey=Buffer.from('ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84','hex');
assert.equal(ecb(staticKey,Buffer.from(request.R,'base64')).toString(),'test-v1|');
const mid=ecb(Buffer.from(session[0],'base64'),Buffer.from(request.B,'base64'));const mask=mid.subarray(0,16);const rotated=mid.subarray(16);const rot=(mask[0]&15)%rotated.length;const unrotate=Buffer.concat([rotated.subarray(rotated.length-rot),rotated.subarray(0,rotated.length-rot)]);const xor=Buffer.from(unrotate.toString(),'base64');for(let i=0;i<xor.length;i++)xor[i]^=mask[i&15];
const plain=JSON.parse(ecb(staticKey,xor));assert.equal(plain.queryString,'e_r=true');const body=new URLSearchParams(Buffer.from(plain.body,'base64').toString());assert.equal(body.get('ids'),'[123]');assert.equal(body.get('level'),'standard');assert.equal(body.has('e_r'),false);
console.log('PASS: Java X25519, GCM authentication, R version, B double AES/mask/rotation, form payload independently decoded by Node crypto');

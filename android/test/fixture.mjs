// Deterministic local-only emulator fixture. Never included in the production APK.
import http from "node:http";
import { readFileSync } from "node:fs";

const token = "local-emulator-test-token-00000001";
const song = (id) => ({
  id,
  name:
    id === 1
      ? "晨间出发 · 测试音"
      : id === 2
        ? "沿途风景 · 测试音"
        : id === 3 ? "夜色归途 · 测试音" : "测试歌曲 " + id,
  ar: [{ name: "云途本地联调" }],
  al: { name: "合成测试音频" },
  dt: 60000,
});
const audio = Buffer.alloc(44 + 22050 * 2 * 60);
audio.write("RIFF", 0);
audio.writeUInt32LE(audio.length - 8, 4);
audio.write("WAVEfmt ", 8);
audio.writeUInt32LE(16, 16);
audio.writeUInt16LE(1, 20);
audio.writeUInt16LE(1, 22);
audio.writeUInt32LE(22050, 24);
audio.writeUInt32LE(44100, 28);
audio.writeUInt16LE(2, 32);
audio.writeUInt16LE(16, 34);
audio.write("data", 36);
audio.writeUInt32LE(audio.length - 44, 40);
for (let i = 0; i < (audio.length - 44) / 2; i++)
  audio.writeInt16LE(
    Math.round(Math.sin((i / 22050) * 2 * Math.PI * 220) * 1200),
    44 + i * 2,
  );
const store = {
  cookie: "MUSIC_U=fixture",
  get() {
    return this.cookie;
  },
  set(c) {
    this.cookie = c;
  },
};
const likedIds=new Set();
const audioRequests = new Map();
let retryLyric = true;
let retryPage = true;
let polls = 0,
  fm = 0;
const upstream = async (name, p) => {
  let body = { code: 200 };
  if (name === "login_status")
    body.data = {
      profile: store.cookie ? { userId: 42, nickname: "本地测试账号" } : null,
    };
  if(name === "likelist")body.ids=[...likedIds];
  if(name === "like"){await new Promise(r=>setTimeout(r,600));if(p.like === "true")likedIds.add(Number(p.id));else likedIds.delete(Number(p.id));}
  if(name === "playlist_detail")body.playlist={creator:{userId:42}};
  if(name === "playlist_tracks")return {status:200,body:{body:{code:200}}};
  if (name === "user_playlist")
    body = {
      ...body,
      playlist: [
        { id: 10, name: "一路向前 · 本地测试歌单", trackCount: 102, creator: {userId:42} },
        { id: 11, name: "晚风与城市", trackCount: 3, creator: {userId:42} },
      ],
      more: false,
    };
  if (name === "playlist_track_all" && Number(p.id) === 11)
    await new Promise((r) => setTimeout(r, 800));
  if (name === "playlist_track_all") {
    if ([345,346,347].includes(Number(p.id))) {
      if (p.offset >= 100) await new Promise(r=>setTimeout(r,Number(p.id)===346?1600:650));
      if (Number(p.id)===347 && p.offset===100 && retryPage) { retryPage=false; throw {status:504}; }
      body.songs=Array.from({length:Math.max(0,Math.min(100,345-p.offset))},(_,i)=>song(p.offset+i+1));
    } else body.songs = Array.from({ length: p.offset === 0 ? 100 : 2 }, (_, i) => song(p.offset + i + 1));
  }
  if (name === "cloudsearch") {
    if(p.keywords==='slow')await new Promise(r=>setTimeout(r,1000));
    if(p.keywords==='none')body.result={};
    else if(p.type===100)body.result={artists:[{id:1,name:p.offset?'歌手第二页':'王力宏 · 测试歌手',musicSize:103,albumSize:8}],artistCount:2};
    else if(p.type===10)body.result={albums:[{id:2,name:'沿途 · 测试专辑',artist:{name:'云途测试'},size:103}],albumCount:1};
    else if(p.type===1000)body.result={playlists:[{id:3,name:'旅途精选 · 测试歌单',trackCount:102,creator:{nickname:'云途测试'}}],playlistCount:1};
    else body.result = { songs: [song(1), song(2), song(3)], songCount: 3 };
  }
  if(name==='artist_songs')body={...body,songs:[song(1),song(2),song(3)],more:false};
  if(name==='album')body.songs=Array.from({length:103},(_,i)=>song(i+1));
  if (name === "personal_fm") {
    fm++;
    body.data = [song(fm * 10 + 1), song(fm * 10 + 2)];
  }
  if (name === "lyric") {
    if (Number(p.id) === 9003 && retryLyric) { retryLyric=false; throw {status:504}; }
    if (Number(p.id) === 9001) await new Promise(r=>setTimeout(r,1500));
    body.lrc={lyric:'[00:00]落日铺满安静的海面\n[00:05]沿着风的方向慢慢向前\n[00:10]城市的灯火在身后渐远\n[00:15]把今天的心事交给蓝天\n[00:20]让喜欢的旋律陪在身边\n[00:25]每一段路都有新的风景\n[00:30]不急着抵达某一个终点\n[00:35]就让晚风轻轻绕过指尖\n[00:40]下一站是更明亮的明天\n[00:50]听见旅途，听见自己'};
  }
  if(name === "lyric" && Number(p.id) === 9001)body.lrc={lyric:"[00:00]旧请求歌词不能覆盖新歌曲"};
  if(name === "lyric" && Number(p.id) === 9002)body.lrc={lyric:""};
  if (name === "song_detail") body.songs=[];
  if (name === "song_url_v1") {
    const id=Number(p.id);audioRequests.set(String(id),(audioRequests.get(String(id))||0)+1);
    if (id===997) throw {status:504};
    if (id===996) throw {status:301,body:{code:301}};
    if ([994,995].includes(id)) await new Promise(r=>setTimeout(r,1100));
    if (Number(p.id) === 9001) await new Promise((r) => setTimeout(r, 1200));
    if ([980,981,982,994,995,998,999].includes(Number(p.id))) body.data = [{ url: null, code: Number(p.id)===998?-110:404 }];
    else
      body.data = [
        {
          url: "http://10.0.2.2:3211/audio.wav?id=" + p.id,
          level: p.level,
          type: "wav",
          br: 352800,
        },
      ];
  }
  if (name === "login_qr_key") {
    polls = 0;
    body.data = { unikey: "fixture-key" };
  }
  if (name === "login_qr_create")
    body.data = {
      qrimg: "",
      qrurl: "https://example.invalid/fixture",
    };
  if (name === "login_qr_check") {
    polls++;
    body.code = polls < 3 ? 801 : 803;
    return {
      status: 200,
      body,
      cookie: polls < 3 ? [] : ["MUSIC_U=fixture; Path=/"],
    };
  }
  return { status: 200, body };
};

const server = http.createServer(async (req, res) => {
  if(req.url==='/native-test') {
    let chunks=[];for await(const chunk of req)chunks.push(chunk);
    const {uri,data}=JSON.parse(Buffer.concat(chunks));
    const names={'/api/song/enhance/player/url/v1':'song_url_v1','/api/song/lyric':'lyric','/api/v3/song/detail':'song_detail','/api/w/nuser/account/get':'login_status','/api/song/like/get':'likelist','/api/radio/like':'like','/api/v1/radio/get':'personal_fm'};
    const p={...data}; if(data.ids)p.id=JSON.parse(data.ids)[0];if(data.trackId)p.id=data.trackId;if('like' in p)p.like=String(p.like);
    try {const r=await upstream(names[uri],p);if(names[uri]==='login_status')r.body=r.body.data;res.end(JSON.stringify(r));}
    catch(e){res.end(JSON.stringify({failure:e.body?.code||e.status||502}));}return;
  }

  if(req.url==='/__test/stats'){res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(Object.fromEntries(audioRequests)));return;}
  if (req.url.startsWith("/cover.png")) {
    res.writeHead(200,{'Content-Type':'image/png'});res.end(readFileSync(new URL('./assets/cover.png',import.meta.url)));return;
  }
  if (req.url.startsWith("/audio.wav")) {
    const match = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
    let start = match ? Number(match[1]) : 0,
      end = match && match[2] ? Number(match[2]) : audio.length - 1;
    end = Math.min(end, audio.length - 1);
    if (start > end) {
      res.writeHead(416, { "Content-Range": "bytes */" + audio.length });
      return res.end();
    }
    res.writeHead(match ? 206 : 200, {
      "Content-Type": "audio/wav",
      "Content-Length": end - start + 1,
      "Accept-Ranges": "bytes",
      ...(match
        ? { "Content-Range": `bytes ${start}-${end}/${audio.length}` }
        : {}),
    });
    res.end(audio.subarray(start, end + 1));
    return;
  }
  res.writeHead(404);res.end();
});
server.listen(3211, "127.0.0.1", () =>
  console.log(
    "Local emulator fixture ready on 127.0.0.1:3211 (synthetic audio only)",
  ),
);
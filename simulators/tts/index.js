/**
 * TTS 시뮬레이터
 *
 * POST /synthesize
 *   Header: X-Call-Id
 *   Body:   { "text": "안녕하세요" }
 *   →  audio/ 디렉토리에서 텍스트 해시명 mp3 파일을 찾거나,
 *      없으면 최소 유효 무음 MP3 바이너리를 반환
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const app = express();
const PORT = 8083;
const AUDIO_DIR = path.join(__dirname, 'audio');

app.use(express.json());

/**
 * 최소 유효 MP3 프레임 (무음 0.026초 @ 44100Hz stereo)
 * ID3v2 헤더 없는 순수 MPEG1 Layer3 프레임
 */
function silentMp3() {
  // MPEG1, Layer3, 128kbps, 44100Hz, Stereo - 무음 프레임 (최소 유효 MP3)
  return Buffer.from([
    0xFF, 0xFB, 0x90, 0x00, // 프레임 헤더
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  ]);
}

app.post('/synthesize', (req, res) => {
  const callId = req.headers['x-call-id'] || 'unknown';
  const { text = '' } = req.body;

  console.log(`[TTS] callId=${callId} text="${text}"`);

  // 1) 텍스트 해시로 파일 검색 (audio/{md5}.mp3)
  const hash = crypto.createHash('md5').update(text).digest('hex');
  const hashFile = path.join(AUDIO_DIR, `${hash}.mp3`);

  // 2) 텍스트 그대로 파일명 검색 (audio/{text}.mp3) - 짧은 텍스트용
  const safeText = text.replace(/[^가-힣a-zA-Z0-9\s]/g, '').trim().substring(0, 30);
  const textFile = path.join(AUDIO_DIR, `${safeText}.mp3`);

  let audioBuffer;

  if (fs.existsSync(hashFile)) {
    console.log(`[TTS] 파일 사용: ${hash}.mp3`);
    audioBuffer = fs.readFileSync(hashFile);
  } else if (fs.existsSync(textFile)) {
    console.log(`[TTS] 파일 사용: ${safeText}.mp3`);
    audioBuffer = fs.readFileSync(textFile);
  } else {
    console.log(`[TTS] 파일 없음 → 무음 MP3 반환`);
    audioBuffer = silentMp3();
  }

  res
    .status(200)
    .type('audio/mpeg')
    .set('Content-Length', audioBuffer.length)
    .send(audioBuffer);
});

// 헬스체크
app.get('/health', (req, res) => res.json({ status: 'ok', service: 'tts-simulator' }));

// 사용 가능한 오디오 파일 목록
app.get('/audio', (req, res) => {
  const files = fs.existsSync(AUDIO_DIR)
    ? fs.readdirSync(AUDIO_DIR).filter(f => f.endsWith('.mp3'))
    : [];
  res.json({ total: files.length, files });
});

app.listen(PORT, () => {
  console.log(`[TTS-SIM] listening on :${PORT}`);
  const files = fs.existsSync(AUDIO_DIR)
    ? fs.readdirSync(AUDIO_DIR).filter(f => f.endsWith('.mp3'))
    : [];
  console.log(`[TTS-SIM] ${files.length} audio file(s) in ${AUDIO_DIR}`);
});

const WebSocket = require('ws');
const fs = require('fs');

const WS_URL = 'ws://localhost:8080/ws/cti';
const AUDIO_FILE = '/tmp/korean-test.pcm';
const WAV_HEADER_SIZE = 44;   // RIFF WAV 헤더 크기
const CHUNK_SIZE = 4096;
const CHUNK_INTERVAL_MS = 80; // 청크 전송 간격
const RESPONSE_TIMEOUT_MS = 20000;

let passed = 0;
let failed = 0;
const results = [];

function log(msg) {
  const ts = new Date().toISOString().substring(11, 23);
  console.log(`[${ts}] ${msg}`);
}

function assert(condition, label) {
  if (condition) {
    passed++;
    results.push({ label, ok: true });
    log(`✅ PASS: ${label}`);
  } else {
    failed++;
    results.push({ label, ok: false });
    log(`❌ FAIL: ${label}`);
  }
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function runTest() {
  log('=== CTI WebSocket E2E 테스트 시작 (real profile) ===');

  // 오디오 파일 로드 (WAV 헤더 제외)
  const audioBuffer = fs.readFileSync(AUDIO_FILE);
  const pcmData = audioBuffer.slice(WAV_HEADER_SIZE);
  log(`오디오 파일: ${AUDIO_FILE} (전체 ${audioBuffer.length}bytes, PCM ${pcmData.length}bytes)`);

  await new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const received = {};

    const responseTimer = setTimeout(() => {
      assert(!!received['STT_FINAL'],  'STT_FINAL  메시지 수신 (타임아웃)');
      assert(!!received['LLM_RESULT'], 'LLM_RESULT 메시지 수신 (타임아웃)');
      assert(!!received['TTS_TEXT'],   'TTS_TEXT   메시지 수신 (타임아웃)');
      ws.terminate();
      resolve();
    }, RESPONSE_TIMEOUT_MS);

    ws.on('open', async () => {
      assert(true, 'WebSocket 연결 수립');

      // 1. CTI_EVENT CALL_START
      ws.send(JSON.stringify({
        type: 'CTI_EVENT',
        event: 'CALL_START',
        callerNumber: '010-0000-0001',
        receiverNumber: '1588-0000',
        timestamp: new Date().toISOString(),
      }));
      assert(true, 'CTI_EVENT CALL_START 전송');

      // 2. PCM 청크 스트리밍
      log(`  → PCM 스트리밍 시작 (${Math.ceil(pcmData.length / CHUNK_SIZE)}개 청크)`);
      for (let i = 0; i < pcmData.length; i += CHUNK_SIZE) {
        if (ws.readyState !== WebSocket.OPEN) break;
        const chunk = pcmData.slice(i, i + CHUNK_SIZE);
        ws.send(chunk);
        await sleep(CHUNK_INTERVAL_MS);
      }
      assert(true, `PCM 스트리밍 완료 (${pcmData.length}bytes)`);

      // 3. CALL_END → STT 스트림 완료
      log('  → CTI_EVENT CALL_END 전송');
      ws.send(JSON.stringify({ type: 'CTI_EVENT', event: 'CALL_END' }));
    });

    ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data.toString());
        const preview = msg.text
          ? `text="${msg.text.substring(0, 60)}"`
          : msg.response
          ? `response="${msg.response.substring(0, 60)}"`
          : JSON.stringify(msg);
        log(`  ← 수신: type=${msg.type} ${preview}`);
        received[msg.type] = msg;

        if (received['STT_FINAL'] && received['LLM_RESULT'] && received['TTS_TEXT']) {
          clearTimeout(responseTimer);
          assert(true, 'STT_FINAL  메시지 수신');
          assert(!!received['STT_FINAL'].text,      'STT_FINAL.text 값 존재');
          assert(true, 'LLM_RESULT 메시지 수신');
          assert(!!received['LLM_RESULT'].response, 'LLM_RESULT.response 값 존재');
          assert(true, 'TTS_TEXT   메시지 수신');
          assert(!!received['TTS_TEXT'].text,       'TTS_TEXT.text 값 존재');
          ws.close();
          resolve();
        }
      } catch (e) {
        log(`  ← 파싱 오류: ${e.message}`);
      }
    });

    ws.on('error', (err) => {
      clearTimeout(responseTimer);
      assert(false, `WebSocket 연결 수립 (오류: ${err.message})`);
      resolve();
    });
  });

  log('');
  log('=== 테스트 결과 ===');
  log(`통과: ${passed} / 전체: ${passed + failed}`);
  if (failed > 0) {
    log('실패 항목:');
    results.filter(r => !r.ok).forEach(r => log(`  - ${r.label}`));
    process.exit(1);
  } else {
    log('모든 테스트 통과');
    process.exit(0);
  }
}

runTest().catch(err => {
  log(`테스트 오류: ${err.message}`);
  process.exit(1);
});

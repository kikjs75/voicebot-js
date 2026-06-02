/**
 * Call 시뮬레이터 API 서버 (:8084)
 *
 * POST /call/start   - Spring Boot /call/incoming 호출 (콜 시작)
 * POST /call/end     - 콜 종료 이벤트
 * GET  /calls        - 콜 이력 조회
 * GET  /health       - 헬스체크
 *
 * 웹 UI는 별도 프로세스 ui.js (:8085) 에서 서빙
 */
const express = require('express');
const axios = require('axios');

const app = express();
const PORT = 8084;

// Spring Boot 앱 주소 (같은 Docker 네트워크)
const VOICEBOT_URL = process.env.VOICEBOT_URL || 'http://voicebot-app:8080';

app.use(express.json());
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  next();
});

// 인메모리 콜 이력
const callHistory = [];
let callSeq = 1;

/** 콜 시작: Spring Boot /call/incoming 으로 전달 */
app.post('/call/start', async (req, res) => {
  const { phoneNumber = '01012345678', scenario = 'default' } = req.body;
  const callId = `CALL-${String(callSeq++).padStart(4, '0')}`;
  const startedAt = new Date().toISOString();

  console.log(`[CALL] 발신 callId=${callId} phoneNumber=${phoneNumber}`);

  const record = { callId, phoneNumber, scenario, startedAt, status: 'ringing', result: null };
  callHistory.unshift(record);

  try {
    // 더미 오디오(무음 PCM) 전송 — 실제 전화 수신 시뮬레이션
    const dummyAudio = Buffer.alloc(1600, 0); // 100ms @ 8kHz 16bit

    const response = await axios.post(`${VOICEBOT_URL}/call/incoming`, dummyAudio, {
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Call-Id': callId,
      },
      responseType: 'arraybuffer',
      timeout: 30000,
    });

    record.status = 'completed';
    record.result = { audioBytes: response.data.byteLength };
    record.endedAt = new Date().toISOString();

    console.log(`[CALL] 완료 callId=${callId} responseBytes=${response.data.byteLength}`);
    res.json({ callId, status: 'completed', result: record.result });

  } catch (err) {
    const errMsg = err.response
      ? `HTTP ${err.response.status}: ${JSON.stringify(err.response.data)}`
      : err.message;

    record.status = 'error';
    record.result = errMsg;
    record.endedAt = new Date().toISOString();

    console.error(`[CALL] 오류 callId=${callId} → ${errMsg}`);
    // Spring Boot 미실행 시에도 200 반환 (시뮬레이터 자체 테스트 가능)
    res.json({ callId, status: 'error', error: errMsg });
  }
});

/** 콜 이력 조회 */
app.get('/calls', (req, res) => {
  res.json(callHistory);
});

/** 이력 초기화 */
app.delete('/calls', (req, res) => {
  callHistory.length = 0;
  callSeq = 1;
  res.json({ cleared: true });
});

/** 헬스체크 */
app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'call-simulator-api', voicebotUrl: VOICEBOT_URL });
});

app.listen(PORT, () => {
  console.log(`[CALL-SIM API] listening on :${PORT}`);
  console.log(`[CALL-SIM API] voicebot target: ${VOICEBOT_URL}`);
});

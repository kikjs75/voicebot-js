/**
 * STT 시뮬레이터
 *
 * POST /recognize
 *   Header: X-Call-Id
 *   Body:   binary (audio bytes)
 *   →  시나리오 파일(scenarios/*.txt)에서 텍스트를 순서대로 반환
 *      시나리오가 없으면 기본 응답 반환
 */
const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = 8081;
const SCENARIOS_DIR = path.join(__dirname, 'scenarios');

// binary body 수신
app.use(express.raw({ type: '*/*', limit: '10mb' }));

// 콜별 시나리오 인덱스 관리 (callId → index)
const callIndex = new Map();

/** scenarios 디렉토리에서 .txt 파일 목록을 정렬해서 반환 */
function loadScenarioLines() {
  if (!fs.existsSync(SCENARIOS_DIR)) return [];

  const lines = [];
  const files = fs.readdirSync(SCENARIOS_DIR)
    .filter(f => f.endsWith('.txt'))
    .sort();

  for (const file of files) {
    const content = fs.readFileSync(path.join(SCENARIOS_DIR, file), 'utf8');
    const fileLines = content
      .split('\n')
      .map(l => l.trim())
      .filter(l => l.length > 0 && !l.startsWith('#'));
    lines.push(...fileLines);
  }
  return lines;
}

app.post('/recognize', (req, res) => {
  const callId = req.headers['x-call-id'] || 'unknown';
  const audioSize = req.body ? req.body.length : 0;

  console.log(`[STT] callId=${callId} audioSize=${audioSize}bytes`);

  const lines = loadScenarioLines();

  let text;
  if (lines.length === 0) {
    text = '안녕하세요, 문의사항이 있습니다.';
  } else {
    const idx = callIndex.get(callId) || 0;
    text = lines[idx % lines.length];
    callIndex.set(callId, idx + 1);
  }

  console.log(`[STT] callId=${callId} → "${text}"`);
  res.type('text/plain').send(text);
});

// 헬스체크
app.get('/health', (req, res) => res.json({ status: 'ok', service: 'stt-simulator' }));

// 시나리오 상태 확인용
app.get('/scenarios', (req, res) => {
  const lines = loadScenarioLines();
  res.json({ total: lines.length, lines });
});

// 콜 인덱스 리셋 (테스트 편의용)
app.post('/reset/:callId', (req, res) => {
  callIndex.delete(req.params.callId);
  res.json({ reset: req.params.callId });
});

app.listen(PORT, () => {
  console.log(`[STT-SIM] listening on :${PORT}`);
  const lines = loadScenarioLines();
  console.log(`[STT-SIM] loaded ${lines.length} scenario line(s) from ${SCENARIOS_DIR}`);
});

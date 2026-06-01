/**
 * LLM 시뮬레이터
 *
 * POST /chat
 *   Header: X-Call-Id
 *   Body:   { "messages": [{ "role": "user"|"assistant", "content": "..." }] }
 *   →  시나리오 파일(scenarios/*.json)의 키워드 매핑으로 응답 반환
 *      매칭 없으면 기본 응답 반환
 *
 * 시나리오 JSON 형식:
 *   [
 *     { "keywords": ["요금", "청구"], "response": "요금 관련 문의시 ..." },
 *     { "keywords": ["해지"],         "response": "해지 신청은 ..." }
 *   ]
 */
const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = 8082;
const SCENARIOS_DIR = path.join(__dirname, 'scenarios');

app.use(express.json());

/** 모든 시나리오 파일을 합쳐서 rules 배열로 반환 */
function loadRules() {
  if (!fs.existsSync(SCENARIOS_DIR)) return [];

  const rules = [];
  const files = fs.readdirSync(SCENARIOS_DIR)
    .filter(f => f.endsWith('.json'))
    .sort();

  for (const file of files) {
    try {
      const raw = fs.readFileSync(path.join(SCENARIOS_DIR, file), 'utf8');
      const data = JSON.parse(raw);
      if (Array.isArray(data)) rules.push(...data);
    } catch (e) {
      console.warn(`[LLM] 시나리오 파일 파싱 실패: ${file} - ${e.message}`);
    }
  }
  return rules;
}

/** 마지막 user 메시지에서 키워드 매칭 */
function matchRule(rules, messages) {
  // 가장 최근 user 메시지
  const lastUser = [...messages].reverse().find(m => m.role === 'user');
  if (!lastUser) return null;

  const text = lastUser.content.toLowerCase();
  for (const rule of rules) {
    const keywords = rule.keywords || [];
    if (keywords.some(kw => text.includes(kw.toLowerCase()))) {
      return rule.response;
    }
  }
  return null;
}

app.post('/chat', (req, res) => {
  const callId = req.headers['x-call-id'] || 'unknown';
  const { messages = [] } = req.body;

  console.log(`[LLM] callId=${callId} messages=${messages.length}`);

  const rules = loadRules();
  const matched = matchRule(rules, messages);

  const response = matched || '네, 무엇을 도와드릴까요? 자세히 말씀해 주시면 안내해 드리겠습니다.';

  console.log(`[LLM] callId=${callId} → "${response.substring(0, 50)}..."`);
  res.type('text/plain').send(response);
});

// 헬스체크
app.get('/health', (req, res) => res.json({ status: 'ok', service: 'llm-simulator' }));

// 로드된 룰 확인용
app.get('/rules', (req, res) => {
  const rules = loadRules();
  res.json({ total: rules.length, rules });
});

app.listen(PORT, () => {
  console.log(`[LLM-SIM] listening on :${PORT}`);
  const rules = loadRules();
  console.log(`[LLM-SIM] loaded ${rules.length} rule(s) from ${SCENARIOS_DIR}`);
});

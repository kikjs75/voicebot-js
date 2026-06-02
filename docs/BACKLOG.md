# BACKLOG

개선사항, 버그, 기술부채를 기록하고 상태를 관리하는 문서.

## 작성 규칙

- 발견 즉시 추가 (테스트 후, 코드 분석 중, 리뷰 후)
- 완료 시 삭제하지 않고 상태만 `완료`로 변경 (히스토리 유지)
- 타입: `[개선]` 성능·품질 향상 / `[버그]` 오동작 / `[기술부채]` 리팩토링·정리 / `[검증]` 테스트·분석

---

## 목록

### [검증] 직접 테스트 및 소스 분석

- **상태**: 진행중
- **목적**: Phase 5 E2E 완료 후 실제 동작을 직접 확인하고 코드 흐름을 이해한 뒤 개선 작업 진행
- **방법**: docs/TESTING-GUIDE.md 순서대로 진행
  - [ ] sim profile 테스트 (한국어 음성 생성 → 콜 요청 → 로그/DB/Redis 확인)
  - [ ] real profile 테스트 (동일)
  - [ ] 핵심 소스 파일 분석 (CallHandler → RtzrWebSocketSttService → ClaudeApiLlmService → GoogleCloudTtsService)
  - [ ] 분석 중 발견된 개선점 BACKLOG에 추가
- **완료 기준**: 분석 완료 후 LLM/TTS 응답시간 개선 작업 착수

---

### [개선] LLM 응답시간 단축

- **상태**: 대기
- **발견**: Phase 5 E2E 테스트 (docs/E2E-TEST-REAL.md)
- **원인**: max_tokens 1024로 설정되어 있어 LLM이 긴 응답을 생성 → 4,849ms (목표 3,000ms)
- **방법**:
  - `ClaudeApiLlmService.java` — `max_tokens` 축소 (1024 → 200~300)
  - `ClaudeApiLlmService.java` — `system` 프롬프트 추가 ("2문장 이내로 간결하게 답하세요")

---

### [개선] TTS 응답시간 단축

- **상태**: 대기
- **발견**: Phase 5 E2E 테스트 (docs/E2E-TEST-REAL.md)
- **원인**: LLM이 생성한 긴 텍스트를 TTS가 합성 → 3,226ms (목표 1,000ms)
- **방법**:
  - LLM 응답시간 단축 항목 완료 시 자동으로 해결될 것으로 예상
  - LLM 개선 후에도 초과 시 Google TTS 음성 길이 제한 검토

---

### [개선] 전체 파이프라인 응답시간 5,000ms 이내

- **상태**: 대기
- **발견**: Phase 5 E2E 테스트 (docs/E2E-TEST-REAL.md)
- **원인**: LLM 4,849ms + TTS 3,226ms → 전체 8,532ms (목표 5,000ms)
- **방법**: 위 두 항목(LLM/TTS 단축) 완료 시 달성 여부 재측정

# BACKLOG

개선사항, 버그, 기술부채를 기록하고 상태를 관리하는 문서.

## 작성 규칙

- 발견 즉시 추가 (테스트 후, 코드 분석 중, 리뷰 후)
- 완료 시 삭제하지 않고 상태만 `완료`로 변경 (히스토리 유지)
- 타입: `[개선]` 성능·품질 향상 / `[버그]` 오동작 / `[기술부채]` 리팩토링·정리 / `[검증]` 테스트·분석

---

## 목록

### [검증] 직접 테스트 및 소스 분석

- **상태**: 완료 (2026-06-03)
- **결과**: real-google profile 기준 Host OS 브라우저(localhost:5173)에서 마이크 → STT → LLM → TTS 파이프라인 E2E 동작 확인
  - RTZR STT: PCM 16kHz 16-bit mono 정상 인식 확인
  - Claude LLM: intent 분류 + 구어체 응답 생성 확인
  - Google TTS: 텍스트 합성 호출 확인
- **발견된 이슈**: 아래 항목으로 분리 등록

---

### [개선] TTS 음성 브라우저 재생

- **상태**: 대기
- **발견**: 2026-06-03 E2E 테스트
- **현상**: TTS_TEXT 메시지로 텍스트만 화면에 표시됨. Google TTS가 합성한 오디오 바이트가 브라우저까지 전달되지 않아 실제 음성 재생 안 됨
- **방법**:
  - 백엔드: `handleFinalStt`에서 TTS 오디오 바이트를 WebSocket binary 메시지로 전송
  - 프론트엔드: binary 메시지 수신 시 `AudioContext`로 디코딩 후 재생
  - 또는 TTS_TEXT 수신 시 브라우저 Web Speech API(`SpeechSynthesis`)로 대체 재생 (간단한 방법)

---

### [개선] LLM 응답시간 단축

- **상태**: 부분완료 (시스템 프롬프트 추가됨, max_tokens 미조정)
- **발견**: Phase 5 E2E 테스트 (docs/E2E-TEST-REAL.md)
- **원인**: max_tokens 1024로 설정되어 있어 LLM이 긴 응답을 생성 → 4,849ms (목표 3,000ms)
- **방법**:
  - `ClaudeApiLlmService.java` — `max_tokens` 축소 (1024 → 200~300)

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

---

### [개선] 콜센터 시나리오 시스템 프롬프트 커스터마이징

- **상태**: 대기
- **발견**: 2026-06-03 E2E 테스트
- **현상**: 현재 시스템 프롬프트는 범용 콜센터 수준. 실제 업종(통신사, 쇼핑몰, 기술지원 등)에 맞는 시나리오 미반영
- **방법**:
  - `application-real-google.yml`에 `voicebot.llm.system-prompt` 설정값 추가
  - `ClaudeApiLlmService`에서 외부화된 프롬프트 읽어 사용
  - intent 분류 목록도 업종별로 조정

---

### [개선] 연속 발화 지원

- **상태**: 완료 (2026-06-04)
- **발견**: 2026-06-04 테스트 중
- **현상**: 첫 발화 후 RTZR WebSocket이 닫히면서 STT Flux가 완료되어 이후 발화 처리 불가
- **방법**:
  - `handleFinalStt` 완료 후 `startNextSttSession`으로 새 Sink 생성 + STT 재구독
  - `BOT_THINKING` / `BOT_READY` 메시지로 프론트엔드 마이크 전송 제어
- **결과**: 3회 이상 연속 발화 정상 동작 확인 (shared/0604_02.jpg)

---

### [개선] Spring Boot 재시작 없이 .env 환경변수 로드

- **상태**: 대기
- **발견**: 2026-06-03 작업 중
- **현상**: Spring Boot 재시작 시 `set -a && source .env && set +a` 수동 실행 필요. 터미널 세션이 바뀌면 환경변수 누락으로 기동 실패
- **방법**: `.env` 파일을 Spring Boot `application.yml`의 `spring.config.import`로 직접 읽거나, 시작 스크립트(`start.sh`) 작성

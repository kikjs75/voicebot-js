# TTS 오디오 재생 구현 계획

## 현재 상태와 문제점

```
C++ / Spring WebSocket 서버
  └─ ttsService.synthesize() 호출
       └─ 오디오 바이트(byte[]) 반환
            └─ ❌ 반환값 무시 — 브라우저로 전송 안 함

Frontend
  └─ TTS_TEXT (텍스트만) 수신
       └─ ❌ 오디오 재생 없음
```

추가 문제: Google Cloud TTS가 `LINEAR16` (raw PCM, 8000Hz) 포맷으로 반환 중.
브라우저는 raw PCM을 직접 재생할 수 없다.

---

## 검토 항목 결과

| # | 항목 | 결과 |
|---|---|---|
| 1 | `writeQueue_` binary 순서 보장 | 확장 필요 — `variant<string, vector<uint8_t>>`로 교체 |
| 2 | sim profile 오디오 포맷 | ✅ 이미 MP3 반환 — 변경 불필요 |
| 3 | 오디오 재생 중 STT 겹침 | 프론트엔드 `audioPlaying` 플래그로 처리 (아래 참조) |
| 4 | 브라우저 자동재생 정책 | ✅ 문제 없음 — "전화 걸기" 클릭이 사용자 인터랙션으로 충분 |

---

## 변경 계획

### 변경 1 — Google TTS 오디오 포맷 변경 (TTS만 해당)

**파일**: `src/main/resources/application-real.yml`

```yaml
# 변경 전
voicebot:
  tts:
    google:
      audio-encoding: LINEAR16   # raw PCM — 브라우저 재생 불가

# 변경 후
voicebot:
  tts:
    google:
      audio-encoding: MP3        # 브라우저 직접 재생 가능
```

> STT의 `encoding: LINEAR16`은 RTZR로 전송하는 포맷이므로 **변경하지 않는다.**
> sim profile의 TTS 시뮬레이터는 이미 MP3를 반환하므로 추가 변경 없음.

---

### 변경 2 — Spring CtiWebSocketHandler.java

**파일**: `src/main/java/com/voicebot/call/CtiWebSocketHandler.java`

현재 (145~148 라인):
```java
ttsService.synthesize(llmResponse, callId);          // 반환값 무시
// ...
sendJson(session, Map.of("type", "TTS_TEXT", ...));
```

변경 후:
```java
byte[] audioBytes = ttsService.synthesize(llmResponse, callId);
sendJson(session, Map.of("type", "TTS_TEXT", "text", llmResponse));
sendBinary(session, audioBytes);                     // 오디오 바이너리 전송 추가
```

추가할 `sendBinary()` 헬퍼:
```java
private void sendBinary(WebSocketSession session, byte[] data) throws Exception {
    if (session.isOpen()) {
        session.sendMessage(new BinaryMessage(data));
    }
}
```

---

### 변경 3 — C++ WsServer.cpp

**파일**: `cpp-ws-server/src/WsServer.cpp`

현재 (135라인):
```cpp
self->tts_->synthesize(response, self->callId_);  // 반환값 무시
```

변경 후:
```cpp
auto audioBytes = self->tts_->synthesize(response, self->callId_);
self->sendJson({{"type", "TTS_TEXT"}, {"text", response}});
self->enqueue(audioBytes);   // writeQueue_ 에 binary 추가
```

#### writeQueue 확장 설계

현재 `writeQueue_`는 `std::deque<std::string>` (텍스트 전용).
binary 지원을 위해 variant로 교체한다.

```cpp
using WriteItem = std::variant<std::string, std::vector<uint8_t>>;
std::deque<WriteItem> writeQueue_;
```

`doWrite()`에서 타입에 따라 `ws_.text(true)` / `ws_.binary(true)` 분기.
같은 큐를 사용하므로 `TTS_TEXT` JSON → binary 오디오 순서가 보장된다.

---

### 변경 4 — Frontend CtiSimulator.jsx

**파일**: `frontend/src/CtiSimulator.jsx`

#### 4-1. binary onmessage 핸들러 + MP3 재생

현재 `ws.binaryType = "arraybuffer"`로 설정되어 있으므로 `e.data`는 `ArrayBuffer`로 수신된다.

```js
ws.onmessage = (e) => {
  // 기존: 텍스트 JSON 처리
  if (typeof e.data === "string") {
    // ... 기존 로직
    return;
  }

  // 추가: ArrayBuffer → MP3 Blob → 재생
  if (e.data instanceof ArrayBuffer) {
    const blob = new Blob([e.data], { type: "audio/mpeg" });
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    setAudioPlaying(true);          // 재생 시작
    audio.play();
    audio.onended = () => {
      setAudioPlaying(false);       // 재생 완료
      URL.revokeObjectURL(url);
    };
  }
};
```

#### 4-2. audioPlaying 플래그 — STT 겹침 방지

봇 음성이 재생되는 동안 마이크 입력이 서버로 전달되면 봇 목소리가 STT에 잡힌다.
`audioPlaying` 상태가 `true`인 동안 청크 전송을 중단한다.

```
청크 전송 조건 (현재)  : botReadyRef.current === true
청크 전송 조건 (변경 후): botReadyRef.current === true AND audioPlaying === false
```

타임라인:

```
서버: BOT_READY 전송
서버: [binary MP3] 전송
        ↓
프론트: BOT_READY 수신  → botReady = true
프론트: ArrayBuffer 수신 → audio.play() → audioPlaying = true
        ↓
   청크 전송 안 함 (botReady=true 이지만 audioPlaying=true)
        ↓
프론트: audio.onended   → audioPlaying = false
        ↓
   청크 전송 시작 (botReady=true AND audioPlaying=false)
```

---

## 변경 파일 요약

| # | 파일 | 변경 내용 |
|---|---|---|
| 1 | `application-real.yml` | TTS `audio-encoding: LINEAR16` → `MP3` |
| 2 | `CtiWebSocketHandler.java` | TTS 반환값 캡처 + `sendBinary()` 추가 |
| 3 | `WsServer.cpp` | TTS 반환값 캡처 + `writeQueue_` binary 지원 |
| 4 | `CtiSimulator.jsx` | binary 핸들러 + MP3 재생 + `audioPlaying` 플래그 |

---

## 메시지 흐름 (변경 후)

```
서버                                브라우저
  │                                    │
  ├─ {"type":"TTS_TEXT","text":"..."} ─▶│  텍스트 UI 표시
  ├─ [binary: MP3 bytes] ─────────────▶│  audio.play() → audioPlaying=true
  │                                    │       ↓ (재생 완료)
  │                                    │  audioPlaying=false → 마이크 입력 재개
```

import { useState, useRef, useEffect, useCallback } from "react";

const WS_URL = "ws://localhost:8080/ws/cti";

const CHUNK_INTERVAL_MS = 250;
const FAKE_DELAY = (ms) => new Promise((r) => setTimeout(r, ms));

function StatusBadge({ status }) {
  const map = {
    idle: { label: "대기중", color: "#64748b" },
    calling: { label: "통화중", color: "#22c55e" },
    processing: { label: "처리중", color: "#f59e0b" },
    ended: { label: "통화종료", color: "#ef4444" },
  };
  const s = map[status] || map.idle;
  return (
    <span
      style={{
        background: s.color + "22",
        color: s.color,
        border: `1px solid ${s.color}55`,
        borderRadius: 6,
        padding: "2px 12px",
        fontSize: 12,
        fontWeight: 700,
        letterSpacing: 1,
      }}
    >
      {status === "calling" && (
        <span
          style={{
            display: "inline-block",
            width: 8,
            height: 8,
            borderRadius: "50%",
            background: s.color,
            marginRight: 6,
            animation: "pulse 1s infinite",
          }}
        />
      )}
      {s.label}
    </span>
  );
}

function LogItem({ item }) {
  const icons = { stt: "🎤", llm: "🧠", tts: "🔊", event: "📡", error: "❌" };
  const colors = {
    stt: "#38bdf8",
    llm: "#a78bfa",
    tts: "#34d399",
    event: "#94a3b8",
    error: "#f87171",
  };
  return (
    <div
      style={{
        display: "flex",
        gap: 10,
        padding: "8px 12px",
        borderBottom: "1px solid #1e293b",
        fontSize: 13,
        animation: "fadeIn 0.3s ease",
      }}
    >
      <span style={{ fontSize: 16 }}>{icons[item.type] || "•"}</span>
      <div style={{ flex: 1 }}>
        <span
          style={{
            color: colors[item.type] || "#94a3b8",
            fontWeight: 700,
            marginRight: 8,
            fontSize: 11,
            textTransform: "uppercase",
          }}
        >
          {item.type}
        </span>
        <span style={{ color: "#e2e8f0" }}>{item.text}</span>
      </div>
      <span style={{ color: "#475569", fontSize: 11, whiteSpace: "nowrap" }}>
        {item.time}
      </span>
    </div>
  );
}

function WaveBar({ active }) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "flex-end",
        gap: 3,
        height: 32,
        padding: "0 4px",
      }}
    >
      {[...Array(8)].map((_, i) => (
        <div
          key={i}
          style={{
            width: 4,
            borderRadius: 2,
            background: active ? "#22c55e" : "#334155",
            height: active ? `${Math.random() * 100}%` : "20%",
            transition: "height 0.15s ease",
            animation: active ? `wave ${0.4 + i * 0.07}s ease-in-out infinite alternate` : "none",
          }}
        />
      ))}
    </div>
  );
}

export default function CtiSimulator() {
  const [status, setStatus] = useState("idle");
  const [inputMode, setInputMode] = useState("mic");
  const [callerNumber, setCallerNumber] = useState("010-1234-5678");
  const [receiverNumber, setReceiverNumber] = useState("1588-0000");
  const [logs, setLogs] = useState([]);
  const [sttText, setSttText] = useState("");
  const [llmResult, setLlmResult] = useState(null);
  const [ttsText, setTtsText] = useState("");
  const [audioFileName, setAudioFileName] = useState("");
  const [waveActive, setWaveActive] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [audioLevel, setAudioLevel] = useState(0);
  const [micDevices, setMicDevices] = useState([]);
  const [selectedMicId, setSelectedMicId] = useState("");

  const [botState, setBotState] = useState("ready"); // "ready" | "thinking"

  const wsRef = useRef(null);
  const audioContextRef = useRef(null);
  const audioFileRef = useRef(null);
  const logEndRef = useRef(null);
  const botReadyRef = useRef(true); // onaudioprocess에서 참조 (state는 클로저 문제로 사용 불가)

  const addLog = useCallback((type, text) => {
    const now = new Date();
    const time = `${now.getHours().toString().padStart(2, "0")}:${now
      .getMinutes()
      .toString()
      .padStart(2, "0")}:${now.getSeconds().toString().padStart(2, "0")}`;
    setLogs((prev) => [...prev.slice(-50), { type, text, time, id: Date.now() + Math.random() }]);
  }, []);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [logs]);

  useEffect(() => {
    const loadDevices = async () => {
      // 권한이 있어야 label이 채워지므로 먼저 임시 스트림 요청
      try {
        const tmp = await navigator.mediaDevices.getUserMedia({ audio: true });
        tmp.getTracks().forEach((t) => t.stop());
      } catch {}
      const devices = await navigator.mediaDevices.enumerateDevices();
      const mics = devices.filter((d) => d.kind === "audioinput");
      setMicDevices(mics);
      if (mics.length > 0 && !selectedMicId) setSelectedMicId(mics[0].deviceId);
    };
    loadDevices();
  }, []);

  // WebSocket 연결
  const connectWs = useCallback(() => {
    try {
      const ws = new WebSocket(WS_URL);
      ws.binaryType = "arraybuffer";

      ws.onopen = () => {
        setWsConnected(true);
        addLog("event", `WebSocket 연결됨 → ${WS_URL}`);
      };

      ws.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          if (data.type === "STT_INTERIM") {
            setSttText(data.text);
          } else if (data.type === "STT_FINAL") {
            setSttText(data.text);
            addLog("stt", `[최종] ${data.text}`);
          } else if (data.type === "LLM_RESULT") {
            setLlmResult(data);
            addLog("llm", `intent=${data.intent} / ${data.response}`);
          } else if (data.type === "TTS_TEXT") {
            setTtsText(data.text);
            addLog("tts", data.text);
          } else if (data.type === "BOT_THINKING") {
            setBotState("thinking");
            botReadyRef.current = false;
          } else if (data.type === "BOT_READY") {
            setBotState("ready");
            botReadyRef.current = true;
          }
        } catch {
          addLog("event", `서버 메시지: ${e.data}`);
        }
      };

      ws.onclose = () => {
        setWsConnected(false);
        addLog("event", "WebSocket 연결 종료");
      };

      ws.onerror = () => {
        addLog("error", "WebSocket 연결 실패 (서버 실행 확인)");
        setWsConnected(false);
      };

      wsRef.current = ws;
    } catch (err) {
      addLog("error", `연결 오류: ${err.message}`);
    }
  }, [addLog]);

  // 전화 걸기
  const handleStartCall = async () => {
    setStatus("calling");
    setLogs([]);
    setSttText("");
    setLlmResult(null);
    setTtsText("");
    setWaveActive(true);

    addLog("event", `📞 통화 시작 | 발신: ${callerNumber} → 수신: ${receiverNumber}`);

    connectWs();

    // CTI 이벤트 전송
    await FAKE_DELAY(300);
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(
        JSON.stringify({
          type: "CTI_EVENT",
          event: "CALL_START",
          callerNumber,
          receiverNumber,
          timestamp: new Date().toISOString(),
        })
      );
    }

    if (inputMode === "mic") {
      await startMicStream();
    }
  };

  // 전화 끊기
  const handleEndCall = () => {
    setStatus("ended");
    setWaveActive(false);
    setBotState("ready");
    botReadyRef.current = true;
    addLog("event", "📵 통화 종료");

    audioContextRef.current?.close();
    audioContextRef.current = null;

    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(
        JSON.stringify({ type: "CTI_EVENT", event: "CALL_END" })
      );
      wsRef.current.close();
    }

    setTimeout(() => setStatus("idle"), 2000);
  };

  // 마이크 스트리밍 — Web Audio API로 raw PCM 16kHz 16-bit mono 전송 (RTZR 요구 포맷)
  const startMicStream = async () => {
    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        addLog("error", "이 브라우저는 마이크를 지원하지 않습니다 (HTTPS 또는 localhost 필요)");
        setStatus("ended");
        setTimeout(() => setStatus("idle"), 2000);
        return;
      }
      const audioConstraints = {
        channelCount: 1,
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        ...(selectedMicId ? { deviceId: { exact: selectedMicId } } : {}),
      };
      const stream = await navigator.mediaDevices.getUserMedia({ audio: audioConstraints });
      const usedTrack = stream.getAudioTracks()[0];
      addLog("event", `🎙 장치: ${usedTrack.label || "알 수 없음"}`);

      addLog("event", "🎤 마이크 권한 허용됨");

      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      const audioContext = new AudioCtx({ sampleRate: 16000 });
      await audioContext.resume();
      audioContextRef.current = audioContext;

      const source = audioContext.createMediaStreamSource(stream);
      const processor = audioContext.createScriptProcessor(4096, 1, 1);

      processor.onaudioprocess = (e) => {
        const float32 = e.inputBuffer.getChannelData(0);
        const int16 = new Int16Array(float32.length);
        let maxVal = 0;
        for (let i = 0; i < float32.length; i++) {
          int16[i] = Math.max(-32768, Math.min(32767, float32[i] * 32768));
          if (Math.abs(float32[i]) > maxVal) maxVal = Math.abs(float32[i]);
        }
        setAudioLevel(Math.round(maxVal * 100));
        if (wsRef.current?.readyState === WebSocket.OPEN && botReadyRef.current) {
          wsRef.current.send(int16.buffer);
        }
      };

      // destination에 직접 연결하면 피드백 루프 발생 → mute gain 경유
      const silentGain = audioContext.createGain();
      silentGain.gain.value = 0;
      source.connect(processor);
      processor.connect(silentGain);
      silentGain.connect(audioContext.destination);

      addLog("event", "🎤 마이크 스트리밍 시작 (PCM 16kHz, 16-bit, mono)");
    } catch (err) {
      addLog("error", `마이크 접근 실패: ${err.message} — 브라우저 주소창의 🔒 아이콘에서 마이크 권한을 확인하세요`);
      setWaveActive(false);
    }
  };

  // 파일 스트리밍
  const handleFileStream = async () => {
    const file = audioFileRef.current?.files?.[0];
    if (!file) return;

    if (status !== "calling") {
      addLog("error", "먼저 전화를 시작하세요");
      return;
    }

    addLog("event", `📁 파일 스트리밍 시작: ${file.name} (${(file.size / 1024).toFixed(1)}KB)`);
    setWaveActive(true);

    const buffer = await file.arrayBuffer();
    const chunkSize = 4096;

    for (let i = 0; i < buffer.byteLength; i += chunkSize) {
      if (wsRef.current?.readyState !== WebSocket.OPEN) break;
      const chunk = buffer.slice(i, i + chunkSize);
      wsRef.current.send(chunk);
      await FAKE_DELAY(80);
    }

    addLog("event", "📁 파일 스트리밍 완료");
    setWaveActive(false);
  };

  // 시뮬레이션 (서버 없이 테스트)
  const handleSimulate = async () => {
    if (status !== "calling") {
      addLog("error", "먼저 전화를 시작하세요");
      return;
    }

    setStatus("processing");
    addLog("event", "🔄 시뮬레이션 모드 (서버 없이 테스트)");

    await FAKE_DELAY(500);
    setSttText("환불 신청하고 싶어요");
    addLog("stt", "[최종] 환불 신청하고 싶어요");

    await FAKE_DELAY(1200);
    const fakeResult = { intent: "환불", response: "환불 절차를 안내해 드리겠습니다. 구매일로부터 7일 이내 신청 가능합니다." };
    setLlmResult(fakeResult);
    addLog("llm", `intent=${fakeResult.intent} / ${fakeResult.response}`);

    await FAKE_DELAY(600);
    setTtsText(fakeResult.response);
    addLog("tts", fakeResult.response);

    setStatus("calling");
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        background: "#0a0f1a",
        fontFamily: "'JetBrains Mono', 'Courier New', monospace",
        color: "#e2e8f0",
        padding: 24,
        display: "flex",
        flexDirection: "column",
        gap: 16,
      }}
    >
      <style>{`
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.4} }
        @keyframes fadeIn { from{opacity:0;transform:translateY(4px)} to{opacity:1;transform:translateY(0)} }
        @keyframes wave { from{height:20%} to{height:90%} }
        * { box-sizing: border-box; }
        input { outline: none; }
        button:hover { opacity: 0.85; }
        button:active { transform: scale(0.97); }
        ::-webkit-scrollbar { width: 4px; }
        ::-webkit-scrollbar-track { background: #0f172a; }
        ::-webkit-scrollbar-thumb { background: #334155; border-radius: 2px; }
      `}</style>

      {/* 헤더 */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <div style={{ fontSize: 20, fontWeight: 800, color: "#38bdf8", letterSpacing: 2 }}>
            ◈ CTI SIMULATOR
          </div>
          <div style={{ fontSize: 11, color: "#475569", marginTop: 2 }}>
            AI Voice Pipeline · STT → LLM → TTS
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span
            style={{
              width: 8, height: 8, borderRadius: "50%",
              background: wsConnected ? "#22c55e" : "#ef4444",
              display: "inline-block",
              boxShadow: wsConnected ? "0 0 8px #22c55e" : "none",
            }}
          />
          <span style={{ fontSize: 11, color: "#64748b" }}>
            {wsConnected ? "WS 연결됨" : "WS 미연결"}
          </span>
          <StatusBadge status={status} />
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        {/* 왼쪽: 컨트롤 패널 */}
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>

          {/* 전화 정보 */}
          <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 12, padding: 16 }}>
            <div style={{ fontSize: 11, color: "#475569", marginBottom: 12, letterSpacing: 1 }}>
              ── 통화 정보
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
              <div>
                <div style={{ fontSize: 11, color: "#64748b", marginBottom: 4 }}>발신번호</div>
                <input
                  value={callerNumber}
                  onChange={(e) => setCallerNumber(e.target.value)}
                  style={{
                    width: "100%", background: "#1e293b", border: "1px solid #334155",
                    borderRadius: 6, padding: "8px 12px", color: "#e2e8f0",
                    fontSize: 14, fontFamily: "inherit",
                  }}
                />
              </div>
              <div>
                <div style={{ fontSize: 11, color: "#64748b", marginBottom: 4 }}>수신번호</div>
                <input
                  value={receiverNumber}
                  onChange={(e) => setReceiverNumber(e.target.value)}
                  style={{
                    width: "100%", background: "#1e293b", border: "1px solid #334155",
                    borderRadius: 6, padding: "8px 12px", color: "#e2e8f0",
                    fontSize: 14, fontFamily: "inherit",
                  }}
                />
              </div>
            </div>
          </div>

          {/* 음성 입력 모드 */}
          <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 12, padding: 16 }}>
            <div style={{ fontSize: 11, color: "#475569", marginBottom: 12, letterSpacing: 1 }}>
              ── 음성 입력 모드
            </div>
            <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
              {["mic", "file"].map((m) => (
                <button
                  key={m}
                  onClick={() => setInputMode(m)}
                  style={{
                    flex: 1, padding: "8px 0", borderRadius: 8, border: "none", cursor: "pointer",
                    background: inputMode === m ? "#1d4ed8" : "#1e293b",
                    color: inputMode === m ? "#fff" : "#64748b",
                    fontSize: 13, fontFamily: "inherit", fontWeight: 600, transition: "all 0.2s",
                  }}
                >
                  {m === "mic" ? "🎤 마이크" : "📁 파일"}
                </button>
              ))}
            </div>

            {inputMode === "file" && (
              <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                <input
                  type="file"
                  accept="audio/*"
                  ref={audioFileRef}
                  onChange={(e) => setAudioFileName(e.target.files?.[0]?.name || "")}
                  style={{ display: "none" }}
                />
                <button
                  onClick={() => audioFileRef.current?.click()}
                  style={{
                    background: "#1e293b", border: "1px dashed #334155", borderRadius: 8,
                    padding: "10px 0", color: "#94a3b8", fontSize: 13, cursor: "pointer",
                    fontFamily: "inherit",
                  }}
                >
                  {audioFileName || "음성 파일 선택 (.wav, .mp3)"}
                </button>
                <button
                  onClick={handleFileStream}
                  disabled={!audioFileName || status !== "calling"}
                  style={{
                    background: audioFileName && status === "calling" ? "#0369a1" : "#1e293b",
                    border: "none", borderRadius: 8, padding: "10px 0",
                    color: audioFileName && status === "calling" ? "#fff" : "#475569",
                    fontSize: 13, cursor: "pointer", fontFamily: "inherit", fontWeight: 600,
                  }}
                >
                  ▶ 파일 스트리밍 전송
                </button>
              </div>
            )}

            {inputMode === "mic" && (
              <div style={{ display: "flex", flexDirection: "column", gap: 8, padding: "8px 0" }}>
                {micDevices.length > 0 && (
                  <select
                    value={selectedMicId}
                    onChange={(e) => setSelectedMicId(e.target.value)}
                    disabled={status === "calling"}
                    style={{
                      background: "#1e293b", border: "1px solid #334155", borderRadius: 6,
                      color: "#e2e8f0", padding: "6px 10px", fontSize: 12, fontFamily: "inherit",
                      cursor: status === "calling" ? "not-allowed" : "pointer",
                    }}
                  >
                    {micDevices.map((d) => (
                      <option key={d.deviceId} value={d.deviceId}>
                        {d.label || `마이크 ${d.deviceId.slice(0, 8)}`}
                      </option>
                    ))}
                  </select>
                )}
                <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                  <WaveBar active={waveActive && status === "calling" && botState === "ready"} />
                  <span style={{ fontSize: 12, color: botState === "thinking" ? "#f59e0b" : "#64748b" }}>
                    {status !== "calling" ? "대기중" : botState === "thinking" ? "봇 응답 중... (잠시 기다려주세요)" : "말씀하세요"}
                  </span>
                </div>
                {status === "calling" && (
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <div style={{ flex: 1, height: 8, background: "#1e293b", borderRadius: 4, overflow: "hidden" }}>
                      <div style={{
                        height: "100%", borderRadius: 4, transition: "width 0.1s ease",
                        width: `${Math.min(audioLevel, 100)}%`,
                        background: audioLevel < 2 ? "#ef4444" : audioLevel < 10 ? "#f59e0b" : "#22c55e",
                      }} />
                    </div>
                    <span style={{
                      fontSize: 11, fontWeight: 700, minWidth: 36,
                      color: audioLevel < 2 ? "#ef4444" : audioLevel < 10 ? "#f59e0b" : "#22c55e",
                    }}>
                      {audioLevel}%
                    </span>
                    {audioLevel < 2 && (
                      <span style={{ fontSize: 11, color: "#ef4444" }}>⚠ 무음</span>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* 통화 버튼 */}
          <div style={{ display: "flex", gap: 10 }}>
            <button
              onClick={handleStartCall}
              disabled={status === "calling" || status === "processing"}
              style={{
                flex: 1, padding: "14px 0", borderRadius: 10, border: "none",
                background: status === "idle" || status === "ended" ? "#15803d" : "#1e293b",
                color: status === "idle" || status === "ended" ? "#fff" : "#475569",
                fontSize: 18, cursor: "pointer", fontWeight: 700, transition: "all 0.2s",
              }}
            >
              📞 전화 걸기
            </button>
            <button
              onClick={handleEndCall}
              disabled={status !== "calling" && status !== "processing"}
              style={{
                flex: 1, padding: "14px 0", borderRadius: 10, border: "none",
                background: status === "calling" || status === "processing" ? "#b91c1c" : "#1e293b",
                color: status === "calling" || status === "processing" ? "#fff" : "#475569",
                fontSize: 18, cursor: "pointer", fontWeight: 700, transition: "all 0.2s",
              }}
            >
              📵 끊기
            </button>
          </div>

          {/* 시뮬레이션 버튼 */}
          <button
            onClick={handleSimulate}
            disabled={status !== "calling"}
            style={{
              width: "100%", padding: "12px 0", borderRadius: 10, border: "1px dashed #334155",
              background: "transparent",
              color: status === "calling" ? "#f59e0b" : "#334155",
              fontSize: 13, cursor: "pointer", fontWeight: 600, fontFamily: "inherit", transition: "all 0.2s",
            }}
          >
            ⚡ UI 더미 테스트 (서버 연결 없이 화면만 확인)
          </button>

          {/* 파이프라인 결과 */}
          <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 12, padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
            <div style={{ fontSize: 11, color: "#475569", letterSpacing: 1 }}>── 파이프라인 결과</div>

            <div>
              <div style={{ fontSize: 11, color: "#38bdf8", marginBottom: 4 }}>🎤 STT 결과</div>
              <div style={{
                background: "#1e293b", borderRadius: 8, padding: "10px 12px",
                fontSize: 13, color: sttText ? "#e2e8f0" : "#475569", minHeight: 40,
                border: "1px solid #334155",
              }}>
                {sttText || "음성 인식 대기중..."}
              </div>
            </div>

            <div>
              <div style={{ fontSize: 11, color: "#a78bfa", marginBottom: 4 }}>🧠 LLM 판단</div>
              <div style={{
                background: "#1e293b", borderRadius: 8, padding: "10px 12px",
                fontSize: 13, color: llmResult ? "#e2e8f0" : "#475569", minHeight: 40,
                border: "1px solid #334155",
              }}>
                {llmResult ? (
                  <div>
                    <span style={{ color: "#a78bfa", fontWeight: 700 }}>intent: </span>
                    <span style={{ color: "#fbbf24" }}>{llmResult.intent}</span>
                    <br />
                    <span style={{ color: "#a78bfa", fontWeight: 700 }}>response: </span>
                    {llmResult.response}
                  </div>
                ) : "LLM 분석 대기중..."}
              </div>
            </div>

            <div>
              <div style={{ fontSize: 11, color: "#34d399", marginBottom: 4 }}>🔊 TTS 출력</div>
              <div style={{
                background: "#1e293b", borderRadius: 8, padding: "10px 12px",
                fontSize: 13, color: ttsText ? "#34d399" : "#475569", minHeight: 40,
                border: "1px solid #334155",
              }}>
                {ttsText || "TTS 출력 대기중..."}
              </div>
            </div>
          </div>
        </div>

        {/* 오른쪽: 로그 패널 */}
        <div style={{
          background: "#0f172a", border: "1px solid #1e293b", borderRadius: 12,
          display: "flex", flexDirection: "column", overflow: "hidden",
        }}>
          <div style={{
            padding: "12px 16px", borderBottom: "1px solid #1e293b",
            display: "flex", justifyContent: "space-between", alignItems: "center",
          }}>
            <span style={{ fontSize: 11, color: "#475569", letterSpacing: 1 }}>── 실시간 로그</span>
            <button
              onClick={() => setLogs([])}
              style={{
                background: "transparent", border: "1px solid #334155", borderRadius: 4,
                color: "#64748b", fontSize: 11, padding: "2px 8px", cursor: "pointer",
                fontFamily: "inherit",
              }}
            >
              clear
            </button>
          </div>
          <div style={{ flex: 1, overflowY: "auto", maxHeight: 600 }}>
            {logs.length === 0 ? (
              <div style={{ padding: 24, textAlign: "center", color: "#334155", fontSize: 13 }}>
                통화를 시작하면 로그가 표시됩니다
              </div>
            ) : (
              logs.map((item) => <LogItem key={item.id} item={item} />)
            )}
            <div ref={logEndRef} />
          </div>
          <div style={{
            padding: "8px 16px", borderTop: "1px solid #1e293b",
            fontSize: 11, color: "#334155", display: "flex", justifyContent: "space-between",
          }}>
            <span>총 {logs.length}개 이벤트</span>
            <span>WS: {WS_URL}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

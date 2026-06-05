#include "Logger.h"
#include "WsServer.h"
#include "service/RtzrWebSocketSttService.h"
#include <atomic>
#include <boost/beast/http.hpp>
#include <nlohmann/json.hpp>
#include <thread>

namespace http = boost::beast::http;
using json     = nlohmann::json;

// ─────────────────────────────────────────────────────────
// WsSession — 브라우저 WebSocket 연결 1개를 관리한다
// ─────────────────────────────────────────────────────────
class WsSession : public std::enable_shared_from_this<WsSession> {
public:
    WsSession(tcp::socket socket,
              net::io_context& ioc,
              std::shared_ptr<RtzrTokenManager> tokenMgr,
              std::shared_ptr<LlmService> llm,
              std::shared_ptr<TtsService> tts)
        : ws_(std::move(socket))
        , strand_(net::make_strand(ioc))
        , ioc_(ioc)
        , tokenMgr_(std::move(tokenMgr))
        , llm_(std::move(llm))
        , tts_(std::move(tts))
    {
        static std::atomic<int> counter{0};
        sessionId_ = "S" + std::to_string(++counter);
        callId_    = "CTI-" + sessionId_;
    }

    void start() { doAccept(); }

private:
    // ── WebSocket 핸드셰이크 ──────────────────────────────
    void doAccept() {
        ws_.async_accept(net::bind_executor(strand_,
            [self = shared_from_this()](beast::error_code ec) {
                if (ec) return;
                LOG_INFO("[CTI] 연결됨 sessionId={} callId={}", self->sessionId_, self->callId_);
                self->startStt();
                self->doRead();
            }));
    }

    // ── 읽기 루프 ─────────────────────────────────────────
    void doRead() {
        ws_.async_read(readBuf_,
            net::bind_executor(strand_,
                [self = shared_from_this()](beast::error_code ec, size_t) {
                    if (ec == ws::error::closed || ec == net::error::eof) {
                        LOG_INFO("[CTI] 연결 종료 callId={}", self->callId_);
                        self->cancelled_ = true;
                        if (self->stt_) self->stt_->complete();
                        return;
                    }
                    if (ec) return;

                    bool isBinary = self->ws_.got_binary();
                    auto payload  = beast::buffers_to_string(self->readBuf_.data());
                    self->readBuf_.consume(self->readBuf_.size());

                    if (isBinary) {
                        if (self->stt_) {
                            auto* p = reinterpret_cast<const uint8_t*>(payload.data());
                            self->stt_->sendChunk({p, p + payload.size()});
                        }
                    } else {
                        self->handleTextMessage(payload);
                    }
                    self->doRead();
                }));
    }

    // ── CTI 이벤트 (JSON 텍스트 프레임) ───────────────────
    void handleTextMessage(const std::string& payload) {
        try {
            auto j    = json::parse(payload);
            auto type = j.value("type", std::string());
            LOG_INFO("[CTI] 이벤트 type={} callId={}", type, callId_);

            if (type == "CTI_EVENT" && j.value("event", "") == "CALL_END") {
                if (stt_) stt_->complete();
            }
        } catch (...) {}
    }

    // ── STT 세션 시작 ──────────────────────────────────────
    void startStt() {
        if (!stt_) stt_ = std::make_shared<RtzrWebSocketSttService>(tokenMgr_);
        stt_->recognize(callId_,
            [self = shared_from_this()](SttResult r) {
                if (!r.isFinal) return;
                net::post(self->strand_,
                    [self, text = r.text]() { self->handleFinalStt(text); });
            },
            [self = shared_from_this()](std::string err) {
                net::post(self->strand_, [self, err]() {
                    self->sendJson({{"type", "ERROR"}, {"message", err}});
                });
            });
    }

    // ── STT 최종 결과 → LLM → TTS 파이프라인 ──────────────
    void handleFinalStt(const std::string& text) {
        if (cancelled_) return;

        LOG_INFO("[CTI] STT 최종 callId={} text={}", callId_, text);
        sendJson({{"type", "STT_FINAL"},   {"text", text}});
        sendJson({{"type", "BOT_THINKING"}});

        // history_ 는 strand 위에서만 접근 — 복사본으로 넘긴다
        auto hist = history_;
        hist.push_back({"user", text});

        // LLM/TTS 는 blocking(libcurl) → 별도 스레드
        std::thread([self = shared_from_this(), hist]() mutable {
            try {
                if (self->cancelled_) return;
                auto llmRaw = self->llm_->chat(hist, self->callId_);

                if (self->cancelled_) return;
                std::string intent   = "기타";
                std::string response = llmRaw;
                try {
                    auto j   = json::parse(llmRaw);
                    intent   = j.value("intent",   "기타");
                    response = j.value("response", llmRaw);
                } catch (...) {}

                hist.push_back({"assistant", response});

                self->tts_->synthesize(response, self->callId_);

                if (self->cancelled_) return;
                net::post(self->strand_,
                    [self, intent, response, hist]() {
                        if (self->cancelled_) return;
                        self->history_ = hist;
                        self->sendJson({{"type", "LLM_RESULT"},
                                        {"intent", intent},
                                        {"response", response}});
                        self->sendJson({{"type", "TTS_TEXT"}, {"text", response}});
                        self->startStt();   // 다음 발화를 위해 RTZR 재연결
                        self->sendJson({{"type", "BOT_READY"}});
                        LOG_INFO("[CTI] 다음 발화 대기 callId={}", self->callId_);
                    });
            } catch (const std::exception& e) {
                net::post(self->strand_, [self, msg = std::string(e.what())]() {
                    if (self->cancelled_) return;
                    self->sendJson({{"type", "ERROR"}, {"message", msg}});
                });
            }
        }).detach();
    }

    // ── 브라우저로 JSON 전송 ───────────────────────────────
    void sendJson(json j) {
        writeQueue_.push_back(j.dump());
        if (!writing_) doWrite();
    }

    void doWrite() {
        if (writeQueue_.empty()) { writing_ = false; return; }
        writing_ = true;
        auto msg = std::make_shared<std::string>(std::move(writeQueue_.front()));
        writeQueue_.pop_front();

        ws_.text(true);
        ws_.async_write(net::buffer(*msg),
            net::bind_executor(strand_,
                [self = shared_from_this(), msg](beast::error_code ec, size_t) {
                    if (ec) { self->writing_ = false; return; }
                    self->doWrite();
                }));
    }

    // ─── 멤버 ──────────────────────────────────────────────
    ws::stream<tcp::socket>                    ws_;
    net::strand<net::io_context::executor_type> strand_;
    net::io_context&                            ioc_;
    beast::flat_buffer                          readBuf_;

    std::string sessionId_;
    std::string callId_;
    std::vector<LlmService::Message> history_;

    std::shared_ptr<RtzrTokenManager>      tokenMgr_;
    std::shared_ptr<LlmService>            llm_;
    std::shared_ptr<TtsService>            tts_;
    std::shared_ptr<RtzrWebSocketSttService> stt_;

    std::atomic<bool> cancelled_{false};

    std::deque<std::string> writeQueue_;
    bool writing_ = false;
};

// ─────────────────────────────────────────────────────────
// WsServer
// ─────────────────────────────────────────────────────────
WsServer::WsServer(net::io_context& ioc,
                   unsigned short port,
                   std::shared_ptr<RtzrTokenManager> tokenMgr,
                   std::shared_ptr<LlmService> llm,
                   std::shared_ptr<TtsService> tts)
    : ioc_(ioc)
    , acceptor_(ioc, {net::ip::make_address("0.0.0.0"), port})
    , tokenMgr_(std::move(tokenMgr))
    , llm_(std::move(llm))
    , tts_(std::move(tts))
{}

void WsServer::run() { doAccept(); }

void WsServer::doAccept() {
    acceptor_.async_accept(
        [this](beast::error_code ec, tcp::socket socket) {
            if (!ec) {
                std::make_shared<WsSession>(
                    std::move(socket), ioc_, tokenMgr_, llm_, tts_)->start();
            }
            doAccept();
        });
}

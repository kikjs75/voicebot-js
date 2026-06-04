#include "RtzrWebSocketSttService.h"
#include <boost/beast/http.hpp>
#include <iostream>
#include <nlohmann/json.hpp>

namespace http = boost::beast::http;
using json     = nlohmann::json;

static std::string getEnvOr(const char* name, const char* def) {
    const char* v = std::getenv(name);
    return v ? v : def;
}

RtzrWebSocketSttService::RtzrWebSocketSttService(
    std::shared_ptr<RtzrTokenManager> tokenMgr)
    : tokenMgr_(std::move(tokenMgr)) {
    sslCtx_.set_default_verify_paths();
}

RtzrWebSocketSttService::~RtzrWebSocketSttService() {
    ioc_.stop();
    if (thread_.joinable()) thread_.join();
}

void RtzrWebSocketSttService::recognize(const std::string& callId,
                                        SttCallback onResult,
                                        SttErrorCallback onError) {
    // Stop previous session if running
    if (thread_.joinable()) {
        ioc_.stop();
        thread_.join();
        ioc_.restart();
    }

    callId_       = callId;
    onResult_     = std::move(onResult);
    onError_      = std::move(onError);
    queue_.clear();
    eosSignaled_  = false;
    writing_      = false;
    connected_    = false;
    ws_.reset();
    readBuf_.consume(readBuf_.size());

    thread_ = std::thread([this]() {
        doConnect();
        ioc_.run();
    });
}

void RtzrWebSocketSttService::sendChunk(const std::vector<uint8_t>& chunk) {
    net::post(ioc_, [self = shared_from_this(), c = chunk]() mutable {
        if (!self->connected_) {
            self->queue_.push_back(std::move(c));
            return;
        }
        self->queue_.push_back(std::move(c));
        if (!self->writing_) self->doWrite();
    });
}

void RtzrWebSocketSttService::complete() {
    net::post(ioc_, [self = shared_from_this()]() {
        self->eosSignaled_ = true;
        if (self->connected_ && !self->writing_) self->doWrite();
    });
}

void RtzrWebSocketSttService::doConnect() {
    const std::string host = "openapi.vito.ai";
    const std::string port = "443";

    ws_ = std::make_unique<WsStream>(ioc_, sslCtx_);

    auto resolver = std::make_shared<tcp::resolver>(ioc_);
    resolver->async_resolve(host, port,
        [self = shared_from_this(), resolver, host]
        (beast::error_code ec, tcp::resolver::results_type results) {
            if (ec) { self->onError_(ec.message()); return; }
            net::async_connect(beast::get_lowest_layer(*self->ws_), results,
                [self, host](beast::error_code ec, const tcp::endpoint&) {
                    if (ec) { self->onError_(ec.message()); return; }
                    self->doSslHandshake(host);
                });
        });
}

void RtzrWebSocketSttService::doSslHandshake(const std::string& host) {
    ws_->next_layer().async_handshake(ssl::stream_base::client,
        [self = shared_from_this(), host](beast::error_code ec) {
            if (ec) { self->onError_(ec.message()); return; }
            self->doWsHandshake(host);
        });
}

void RtzrWebSocketSttService::doWsHandshake(const std::string& host) {
    const std::string sampleRate = getEnvOr("RTZR_SAMPLE_RATE", "16000");
    const std::string path =
        "/v1/transcribe:streaming"
        "?sample_rate=" + sampleRate +
        "&encoding=LINEAR16"
        "&use_itn=true"
        "&use_disfluency_filter=true"
        "&use_profanity_filter=false"
        "&use_punctuation=false";

    std::string token = tokenMgr_->getAccessToken();
    ws_->set_option(ws::stream_base::decorator(
        [token](ws::request_type& req) {
            req.set(http::field::authorization, "Bearer " + token);
            req.set(http::field::user_agent, "voicebot-cpp/1.0");
        }));

    ws_->async_handshake(host, path,
        [self = shared_from_this()](beast::error_code ec) {
            if (ec) { self->onError_(ec.message()); return; }
            std::cout << "[STT-RTZR] 연결됨 callId=" << self->callId_ << "\n";
            self->connected_ = true;
            self->doRead();
            // Flush any chunks that arrived before connection
            if (!self->queue_.empty() || self->eosSignaled_)
                self->doWrite();
        });
}

void RtzrWebSocketSttService::doRead() {
    ws_->async_read(readBuf_,
        [self = shared_from_this()](beast::error_code ec, size_t) {
            if (ec) {
                if (ec != ws::error::closed && ec != net::error::eof)
                    std::cerr << "[STT-RTZR] 읽기 오류 callId=" << self->callId_
                              << " " << ec.message() << "\n";
                return;
            }
            auto text = beast::buffers_to_string(self->readBuf_.data());
            self->readBuf_.consume(self->readBuf_.size());
            bool cont = self->onMessage(text);
            if (cont) self->doRead();
        });
}

void RtzrWebSocketSttService::doWrite() {
    if (queue_.empty()) {
        if (eosSignaled_) {
            // EOS: send text frame "EOS"
            writing_ = true;
            ws_->text(true);
            ws_->async_write(net::buffer(std::string("EOS")),
                [self = shared_from_this()](beast::error_code ec, size_t) {
                    self->ws_->text(false);
                    self->writing_ = false;
                    if (ec) self->onError_(ec.message());
                    std::cout << "[STT-RTZR] EOS 전송 callId=" << self->callId_ << "\n";
                });
        }
        return;
    }

    writing_ = true;
    auto chunk = std::move(queue_.front());
    queue_.pop_front();

    ws_->binary(true);
    ws_->async_write(net::buffer(chunk),
        [self = shared_from_this(), chunk](beast::error_code ec, size_t) {
            self->writing_ = false;
            if (ec) { self->onError_(ec.message()); return; }
            self->doWrite();
        });
}

bool RtzrWebSocketSttService::onMessage(const std::string& text) {
    try {
        auto j = json::parse(text);

        if (j.contains("error")) {
            onError_(j["error"].get<std::string>());
            return false;
        }

        bool isFinal = j.value("final", false);
        auto alts = j.value("alternatives", json::array());
        if (!alts.empty()) {
            std::string recognized = alts[0].value("text", std::string());
            std::cout << "[STT-RTZR] callId=" << callId_
                      << " final=" << isFinal
                      << " text=" << recognized << "\n";
            onResult_({recognized, isFinal});

            if (isFinal) {
                ws_->async_close(ws::close_code::normal,
                    [self = shared_from_this()](beast::error_code) {});
                return false;
            }
        }
    } catch (const std::exception& e) {
        std::cerr << "[STT-RTZR] 파싱 오류: " << e.what() << "\n";
    }
    return true;
}

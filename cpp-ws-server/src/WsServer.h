#pragma once
#include "service/LlmService.h"
#include "service/RtzrTokenManager.h"
#include "service/TtsService.h"
#include <boost/asio.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <memory>

namespace net   = boost::asio;
namespace beast = boost::beast;
namespace ws    = boost::beast::websocket;
using tcp       = boost::asio::ip::tcp;

class WsServer {
public:
    WsServer(net::io_context& ioc,
             unsigned short port,
             std::shared_ptr<RtzrTokenManager> tokenMgr,
             std::shared_ptr<LlmService> llm,
             std::shared_ptr<TtsService> tts);

    void run();

private:
    void doAccept();

    net::io_context& ioc_;
    tcp::acceptor    acceptor_;
    std::shared_ptr<RtzrTokenManager> tokenMgr_;
    std::shared_ptr<LlmService>       llm_;
    std::shared_ptr<TtsService>       tts_;
};

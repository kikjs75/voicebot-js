#pragma once
#include "RtzrTokenManager.h"
#include "SttService.h"
#include <atomic>
#include <boost/asio.hpp>
#include <boost/asio/ssl.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/ssl.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/beast/websocket/ssl.hpp>
#include <deque>
#include <memory>
#include <thread>

namespace beast = boost::beast;
namespace net   = boost::asio;
namespace ssl   = boost::asio::ssl;
namespace ws    = boost::beast::websocket;
using tcp       = boost::asio::ip::tcp;

class RtzrWebSocketSttService
    : public SttService,
      public std::enable_shared_from_this<RtzrWebSocketSttService> {

    using WsStream = ws::stream<ssl::stream<tcp::socket>>;

public:
    explicit RtzrWebSocketSttService(std::shared_ptr<RtzrTokenManager> tokenMgr);
    ~RtzrWebSocketSttService() override;

    void recognize(const std::string& callId,
                   SttCallback onResult,
                   SttErrorCallback onError) override;
    void sendChunk(const std::vector<uint8_t>& chunk) override;
    void complete() override;

private:
    void doConnect();
    void doSslHandshake(const std::string& host);
    void doWsHandshake(const std::string& host);
    void doRead();
    void doWrite();
    bool onMessage(const std::string& text);  // returns false if final (stop reading)

    std::shared_ptr<RtzrTokenManager> tokenMgr_;

    net::io_context ioc_;
    ssl::context    sslCtx_{ssl::context::tlsv12_client};
    std::unique_ptr<WsStream> ws_;

    std::string callId_;
    SttCallback      onResult_;
    SttErrorCallback onError_;

    std::deque<std::vector<uint8_t>> queue_;  // accessed only on ioc_ thread
    bool eosSignaled_ = false;
    bool writing_     = false;
    bool connected_   = false;

    beast::flat_buffer readBuf_;

    std::thread thread_;
};

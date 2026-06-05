#include "src/Logger.h"
#include "src/WsServer.h"
#include "src/service/RtzrTokenManager.h"
#include "src/service/SpringLlmService.h"
#include "src/service/SpringTtsService.h"
#include <boost/asio.hpp>
#include <string>

namespace net = boost::asio;

static std::string envOr(const char* name, const char* def) {
    const char* v = std::getenv(name);
    return v ? v : def;
}

int main() {
    initLogger();
    const auto port = static_cast<unsigned short>(
        std::stoi(envOr("PORT", "9090")));
    const std::string springUrl  = envOr("SPRING_URL", "http://localhost:8080");
    const std::string clientId   = envOr("RTZR_CLIENT_ID",     "");
    const std::string clientSecret = envOr("RTZR_CLIENT_SECRET", "");

    if (clientId.empty() || clientSecret.empty()) {
        LOG_WARN("[MAIN] 경고: RTZR_CLIENT_ID / RTZR_CLIENT_SECRET 미설정");
    }

    auto tokenMgr = std::make_shared<RtzrTokenManager>(clientId, clientSecret);
    tokenMgr->startScheduler();

    auto llm = std::make_shared<SpringLlmService>(springUrl);
    auto tts = std::make_shared<SpringTtsService>(springUrl);

    net::io_context ioc;

    auto server = std::make_shared<WsServer>(ioc, port, tokenMgr, llm, tts);
    server->run();

    LOG_INFO("[MAIN] 서버 시작 port={} spring={}", port, springUrl);

    ioc.run();
    return 0;
}

#pragma once
#include <atomic>
#include <ctime>
#include <mutex>
#include <string>
#include <thread>

class RtzrTokenManager {
public:
    RtzrTokenManager(const std::string& clientId, const std::string& clientSecret);
    ~RtzrTokenManager();

    std::string getAccessToken();
    void startScheduler();

private:
    void refreshToken();

    std::string clientId_;
    std::string clientSecret_;
    std::string accessToken_;
    long expireAt_ = 0;
    std::mutex tokenMutex_;
    std::atomic<bool> running_{false};
    std::thread schedulerThread_;
};

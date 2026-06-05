#pragma once
#include <spdlog/sinks/daily_file_sink.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/spdlog.h>
#include <filesystem>
#include <memory>
#include <vector>

inline void initLogger() {
    std::filesystem::create_directories("logs");

    auto consoleSink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
    consoleSink->set_pattern("%H:%M:%S.%e [%^%-5l%$] %v");

    // 자정(00:00)에 날짜별 롤링, 최대 7일 보관
    auto fileSink = std::make_shared<spdlog::sinks::daily_file_sink_mt>(
        "logs/cpp-ws.log", 0, 0, false, 7);
    fileSink->set_pattern("%Y-%m-%d %H:%M:%S.%e [%-5l] %v");

    std::vector<spdlog::sink_ptr> sinks{consoleSink, fileSink};
    auto logger = std::make_shared<spdlog::logger>("voicebot", sinks.begin(), sinks.end());
    logger->set_level(spdlog::level::info);
    logger->flush_on(spdlog::level::info);
    spdlog::set_default_logger(logger);
}

#define LOG_INFO(...)  spdlog::info(__VA_ARGS__)
#define LOG_WARN(...)  spdlog::warn(__VA_ARGS__)
#define LOG_ERROR(...) spdlog::error(__VA_ARGS__)

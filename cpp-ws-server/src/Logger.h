#pragma once
#include <spdlog/sinks/base_sink.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/spdlog.h>
#include <algorithm>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>

namespace fs = std::filesystem;

// Spring logback 스타일: 현재 파일은 항상 cpp-ws.log,
// 날짜가 바뀌면 cpp-ws.2026-06-05.log 로 이름 변경 후 새 cpp-ws.log 시작
class SpringStyleDailyFileSink : public spdlog::sinks::base_sink<std::mutex> {
public:
    SpringStyleDailyFileSink(fs::path baseFile, int maxDays = 7)
        : baseFile_(std::move(baseFile)), maxDays_(maxDays) {
        currentDate_ = todayStr();
        openFile();
    }

protected:
    void sink_it_(const spdlog::details::log_msg& msg) override {
        auto today = todayStr();
        if (today != currentDate_) {
            rotate();
            currentDate_ = today;
        }
        spdlog::memory_buf_t buf;
        formatter_->format(msg, buf);
        file_.write(buf.data(), static_cast<std::streamsize>(buf.size()));
    }

    void flush_() override { file_.flush(); }

private:
    static std::string todayStr() {
        auto t  = std::time(nullptr);
        auto tm = *std::localtime(&t);
        std::ostringstream oss;
        oss << std::put_time(&tm, "%Y-%m-%d");
        return oss.str();
    }

    void openFile() {
        file_.open(baseFile_, std::ios::app);
    }

    void rotate() {
        file_.close();

        // logs/cpp-ws.log → logs/cpp-ws.2026-06-05.log (어제 날짜)
        auto stem     = baseFile_.stem().string();       // "cpp-ws"
        auto ext      = baseFile_.extension().string();  // ".log"
        auto dir      = baseFile_.parent_path();
        auto archived = dir / (stem + "." + currentDate_ + ext);
        fs::rename(baseFile_, archived);

        cleanOldFiles(dir, stem, ext);
        openFile();
    }

    void cleanOldFiles(const fs::path& dir, const std::string& stem, const std::string& ext) {
        // "cpp-ws.YYYY-MM-DD.log" 패턴 파일을 오름차순 정렬 후 maxDays_ 초과분 삭제
        const auto expectedLen = stem.size() + 1 + 10 + ext.size();  // stem + '.' + date(10) + ext
        std::vector<fs::path> archived;
        for (const auto& entry : fs::directory_iterator(dir)) {
            auto name = entry.path().filename().string();
            if (name.size() == expectedLen &&
                name.substr(0, stem.size() + 1) == stem + "." &&
                name.substr(stem.size() + 1 + 10) == ext) {
                archived.push_back(entry.path());
            }
        }
        std::sort(archived.begin(), archived.end());
        while (static_cast<int>(archived.size()) > maxDays_) {
            fs::remove(archived.front());
            archived.erase(archived.begin());
        }
    }

    fs::path      baseFile_;
    int           maxDays_;
    std::ofstream file_;
    std::string   currentDate_;
};

inline void initLogger() {
    fs::create_directories("logs");

    auto consoleSink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
    consoleSink->set_pattern("%H:%M:%S.%e [%^%-5l%$] %v");

    auto fileSink = std::make_shared<SpringStyleDailyFileSink>("logs/cpp-ws.log", 7);
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

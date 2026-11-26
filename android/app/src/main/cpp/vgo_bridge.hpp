#pragma once

#include "engine.hpp"
#include "vni.hpp"

#include <cstdint>
#include <set>
#include <string>

namespace vgo_android {

struct Snapshot {
    bool empty = true;
    std::string display;
    std::string raw;
    std::string altDisplay;
    bool showingRaw = false;
    bool corrected = false;
};

struct RevertOutcome {
    bool reverted = false;
    std::string committedToDelete;
    std::string replacement;
};

// Mirrors fcitx5/vgoime.cpp state, learning, and persistence logic on top of pure vgo C++ engines.
class VgoBridge {
public:
    VgoBridge();

    void init(const std::string& baseDir, const std::string& userDir);
    void setConfig(bool vniMode, bool oldStyleTone, bool wAlwaysU);
    void setApp(const std::string& app);

    bool isVni() const { return vni_; }
    bool empty() const;
    bool acceptsChar(char c, bool isNumpad) const;

    Snapshot key(char c);
    // Returns true if the engine consumed Backspace (word was non-empty).
    bool backspace();
    // Returns true if the engine consumed Esc/Flip (word was non-empty).
    bool flipAndLearn();
    // boundary = Unicode scalar value of separator (space, punctuation), or 0 for non-text boundary.
    void finalize(uint32_t boundary);
    RevertOutcome tryRevertCorrection(const std::string& textBeforeCursor);

    void clearCanRevert() { canRevert_ = false; }
    void reset();
    void clearContext();

    Snapshot snapshot() const;
    void reloadUserFiles();
    void forgetEntry(const std::string& relativePath, const std::string& line);

private:
    bool vni_ = true;
    bool oldStyleTone_ = true;
    bool wAlwaysU_ = false;

    vgo::Engine engine_;
    vgo::VniEngine vniEngine_;

    std::string baseDir_;
    std::string userDir_;
    std::string app_;
    std::set<std::string> loadedApps_;

    std::string badRaw_;
    bool canRevert_ = false;
    std::string revRaw_;
    std::string revShown_;
    std::string revSep_;

    std::string userDictPath() const { return userDir_ + "/words.txt"; }
    std::string userVNDictPath() const { return userDir_ + "/vn-words.txt"; }
    std::string userBigramPath() const { return userDir_ + "/bigram.txt"; }
    std::string userTypoPath() const { return userDir_ + "/typos.txt"; }
    std::string vniRawPath() const { return userDir_ + "/vni-raw.txt"; }
    std::string vniVNPath() const { return userDir_ + "/vni-vn.txt"; }
    std::string vniBigramPath() const { return userDir_ + "/vni-bigram.txt"; }
    std::string vniTypoPath() const { return userDir_ + "/vni-typos.txt"; }
    std::string appPath(const std::string& app) const;

    void loadAppOnce(const std::string& app);
    void learnApp(const std::string& app, const char* kind, const std::string& w, bool keepRaw);
    void learnEnglish(std::string w);
    void learnVN(std::string w);
    void learnBigram(std::string prev, std::string w, bool english);
    void learnVni(std::string w, bool keepRaw);
    void learnVniCtx(std::string prev, std::string w, bool keepRaw);
    void noteTypo(const std::string& kind, const std::string& bad, const std::string& good);
};

} // namespace vgo_android

#include "vgo_bridge.hpp"

#include "learn.hpp"
#include "viet.hpp"

#include <cctype>
#include <filesystem>
#include <fstream>
#include <sstream>

namespace vgo_android {

namespace {

bool g_baseLoaded = false;

std::string lowerAscii(std::string s) {
    for (char& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

void appendLine(const std::string& path, const std::string& line) {
    if (path.empty()) return;
    try {
        std::filesystem::create_directories(std::filesystem::path(path).parent_path());
    } catch (...) {}
    std::ofstream f(path, std::ios::app);
    if (f) f << line << '\n';
}

void removeLine(const std::string& path, const std::string& target) {
    if (path.empty()) return;
    std::ifstream in(path);
    if (!in) return;
    std::string out, line;
    bool found = false;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line == target) {
            found = true;
            continue;
        }
        out += line + '\n';
    }
    in.close();
    if (!found) return;
    std::ofstream f(path, std::ios::trunc);
    if (f) f << out;
}

std::string encodeUtf8Scalar(uint32_t cp) {
    std::string out;
    if (cp == 0) return out;
    vgo::core::encodeOne(out, static_cast<char32_t>(cp));
    return out;
}

} // namespace

VgoBridge::VgoBridge()
    : engine_(vgo::Config{true, false, vgo::Method::Telex}),
      vniEngine_(vgo::VniConfig{true}) {}

void VgoBridge::init(const std::string& baseDir, const std::string& userDir) {
    baseDir_ = baseDir;
    userDir_ = userDir;
    if (!g_baseLoaded && !baseDir_.empty()) {
        g_baseLoaded = true;
        vgo::dictLoadFile(baseDir_ + "/en-base.txt", true);
        vgo::vnLoadFile(baseDir_ + "/vn-base.txt");
        vgo::vnLexLoadFile(baseDir_ + "/vn-syllables.txt");
        vgo::bigramLoadFile(baseDir_ + "/bigram-base.txt");
    }
    reloadUserFiles();
}

void VgoBridge::reloadUserFiles() {
    if (userDir_.empty()) return;
    try {
        std::filesystem::create_directories(userDir_);
        std::filesystem::create_directories(userDir_ + "/apps");
    } catch (...) {}

    vgo::dictLoadFile(userDictPath(), false);
    vgo::vnLoadFile(userVNDictPath());
    vgo::bigramLoadFile(userBigramPath());
    vgo::typoLoadFile("telex", userTypoPath());
    vgo::vniLoadFile(vniRawPath(), true);
    vgo::vniLoadFile(vniVNPath(), false);
    vgo::ctxLoadFile("vni", vniBigramPath());
    vgo::typoLoadFile("vni", vniTypoPath());
    loadedApps_.clear();
    if (!app_.empty()) {
        loadAppOnce(app_);
    }
}

void VgoBridge::forgetEntry(const std::string& relativePath, const std::string& line) {
    std::istringstream in(line);
    if (relativePath == "words.txt") {
        std::string w;
        if (in >> w) vgo::dictRemoveWord(lowerAscii(w));
    } else if (relativePath == "vn-words.txt") {
        std::string w;
        if (in >> w) vgo::vnRemoveWord(lowerAscii(w));
    } else if (relativePath == "vni-bigram.txt") {
        std::string prev, word;
        if (in >> prev >> word) vgo::ctxSet("vni", lowerAscii(prev), lowerAscii(word), 0);
    } else if (relativePath.rfind("apps/", 0) == 0 && relativePath.size() > 9 &&
               relativePath.compare(relativePath.size() - 4, 4, ".txt") == 0) {
        std::string app = relativePath.substr(5, relativePath.size() - 9);
        std::string kind, word;
        if (in >> kind >> word) vgo::appSet(app, kind, lowerAscii(word), 0);
    }
}

void VgoBridge::setConfig(bool vniMode, bool oldStyleTone, bool wAlwaysU) {
    bool engineCfgChanged = (oldStyleTone_ != oldStyleTone) || (wAlwaysU_ != wAlwaysU);
    bool methodChanged = (vni_ != vniMode);
    vni_ = vniMode;
    oldStyleTone_ = oldStyleTone;
    wAlwaysU_ = wAlwaysU;

    if (engineCfgChanged) {
        engine_ = vgo::Engine(vgo::Config{oldStyleTone_, wAlwaysU_, vgo::Method::Telex});
        vniEngine_ = vgo::VniEngine(vgo::VniConfig{oldStyleTone_});
        engine_.setApp(app_);
        vniEngine_.setApp(app_);
        badRaw_.clear();
        canRevert_ = false;
    } else if (methodChanged) {
        reset();
    }
}

std::string VgoBridge::appPath(const std::string& app) const {
    std::string name;
    for (char c : app) {
        name += (std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '-') ? c : '_';
    }
    return userDir_ + "/apps/" + name + ".txt";
}

void VgoBridge::loadAppOnce(const std::string& app) {
    if (app.empty() || userDir_.empty()) return;
    if (!loadedApps_.insert(app).second) return;
    vgo::appLoadFile(app, appPath(app));
}

void VgoBridge::setApp(const std::string& app) {
    if (app_ != app) {
        app_ = app;
    }
    loadAppOnce(app_);
    engine_.setApp(app_);
    vniEngine_.setApp(app_);
}

bool VgoBridge::empty() const {
    return vni_ ? vniEngine_.empty() : engine_.empty();
}

bool VgoBridge::acceptsChar(char c, bool isNumpad) const {
    bool isLetter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    if (vni_) {
        bool isDigit = (c >= '0' && c <= '9') && !isNumpad && !vniEngine_.empty();
        return isLetter || isDigit;
    }
    return isLetter;
}

Snapshot VgoBridge::key(char c) {
    canRevert_ = false;
    if (vni_) {
        vniEngine_.key(c);
    } else {
        engine_.key(c);
    }
    return snapshot();
}

bool VgoBridge::backspace() {
    if (vni_) {
        canRevert_ = false;
        if (vniEngine_.empty()) {
            badRaw_.clear();
            vniEngine_.clearContext();
            return false;
        }
        if (badRaw_.empty() && vniEngine_.rejected()) {
            badRaw_ = vniEngine_.raw();
        }
        vniEngine_.backspace();
        return true;
    }

    if (engine_.empty()) {
        badRaw_.clear();
        return false;
    }
    canRevert_ = false;
    if (badRaw_.empty() && engine_.raw().size() >= 2 &&
        engine_.isShowingRaw() && !engine_.plausible()) {
        badRaw_ = engine_.raw();
    }
    engine_.backspace();
    return true;
}

void VgoBridge::learnApp(const std::string& app, const char* kind, const std::string& w, bool keepRaw) {
    if (app.empty() || w.size() < 2 || userDir_.empty()) return;
    vgo::appSet(app, kind, w, keepRaw ? 1 : -1);
    std::string p = appPath(app);
    try {
        std::filesystem::create_directories(std::filesystem::path(p).parent_path());
    } catch (...) {}
    vgo::appSaveFile(app, p);
}

void VgoBridge::learnEnglish(std::string w) {
    w = lowerAscii(std::move(w));
    if (w.size() < 2) return;
    if (vgo::vnContains(w)) {
        vgo::vnRemoveWord(w);
        removeLine(userVNDictPath(), w);
    }
    if (vgo::dictContains(w)) return;
    vgo::dictAddWord(w);
    appendLine(userDictPath(), w);
}

void VgoBridge::learnVN(std::string w) {
    w = lowerAscii(std::move(w));
    if (w.size() < 2) return;
    vgo::dictRemoveWord(w);
    removeLine(userDictPath(), w);
    if (vgo::vnContains(w)) return;
    vgo::vnAddWord(w);
    appendLine(userVNDictPath(), w);
}

void VgoBridge::learnBigram(std::string prev, std::string w, bool english) {
    prev = lowerAscii(std::move(prev));
    w = lowerAscii(std::move(w));
    if (w.size() < 2 || vgo::bigramLookup(prev, w) == (english ? 1 : -1)) return;
    vgo::bigramAdd(prev, w, english);
    appendLine(userBigramPath(), prev + " " + w + (english ? " en" : " vn"));
}

void VgoBridge::learnVni(std::string w, bool keepRaw) {
    w = lowerAscii(std::move(w));
    if (w.size() < 2 || w.find_first_of("0123456789") == std::string::npos) return;
    if (vgo::vniLookup(w) == (keepRaw ? 1 : -1)) return;
    vgo::vniLearn(w, keepRaw);
    removeLine(keepRaw ? vniVNPath() : vniRawPath(), w);
    appendLine(keepRaw ? vniRawPath() : vniVNPath(), w);
}

void VgoBridge::learnVniCtx(std::string prev, std::string w, bool keepRaw) {
    prev = lowerAscii(std::move(prev));
    w = lowerAscii(std::move(w));
    if (w.size() < 2 || w.find_first_of("0123456789") == std::string::npos) return;
    if (vgo::ctxGet("vni", prev, w) == (keepRaw ? 1 : -1)) return;
    vgo::ctxSet("vni", prev, w, keepRaw ? 1 : -1);
    appendLine(vniBigramPath(), prev + " " + w + (keepRaw ? " raw" : " vn"));
}

void VgoBridge::noteTypo(const std::string& kind, const std::string& bad, const std::string& good) {
    if (!vgo::typoLearnable(bad, good)) return;
    std::string b = lowerAscii(bad);
    std::string g = lowerAscii(good);
    vgo::typoNote(kind, b, g);
    appendLine(kind == "vni" ? vniTypoPath() : userTypoPath(), b + " " + g);
}

bool VgoBridge::flipAndLearn() {
    canRevert_ = false;
    badRaw_.clear();
    if (vni_) {
        if (vniEngine_.empty()) return false;
        bool wasVN = vniEngine_.showingVN();
        std::string word = vniEngine_.raw();
        const std::string& prev = vniEngine_.prev();
        if (prev.empty()) {
            learnVni(word, wasVN);
            if (word.find_first_of("0123456789") != std::string::npos) {
                learnApp(app_, "vni", word, wasVN);
            }
        } else {
            learnVniCtx(prev, word, wasVN);
        }
        if (vniEngine_.showingVN() == wasVN) {
            vniEngine_.flip();
        }
        return true;
    }

    if (engine_.empty()) return false;
    std::string word = engine_.raw();
    const std::string& prev = engine_.prev();
    if (engine_.isShowingRaw()) {
        bool keep = engine_.plausible() ||
                    word.find_first_of("aeiouyAEIOUY") == std::string::npos;
        engine_.forceVN();
        if (keep) {
            if (prev.empty()) {
                learnVN(word);
                learnApp(app_, "telex", word, false);
            } else {
                learnBigram(prev, word, false);
            }
        }
    } else {
        engine_.forceRaw();
        if (prev.empty()) {
            learnEnglish(word);
            learnApp(app_, "telex", word, true);
        } else {
            learnBigram(prev, word, true);
        }
    }
    return true;
}

void VgoBridge::finalize(uint32_t boundary) {
    canRevert_ = !vni_ && boundary != 0 && !engine_.empty() && engine_.isCorrected();
    if (canRevert_) {
        revRaw_ = engine_.raw();
        revShown_ = engine_.display();
        revSep_ = encodeUtf8Scalar(boundary);
    }

    if (!vni_ && !badRaw_.empty() && !engine_.empty() &&
        !engine_.isShowingRaw() && engine_.complete()) {
        noteTypo("telex", badRaw_, engine_.raw());
    }
    if (vni_ && !badRaw_.empty() && !vniEngine_.empty() &&
        vniEngine_.showingVN() && !vniEngine_.isCorrected() &&
        vniEngine_.complete()) {
        noteTypo("vni", badRaw_, vniEngine_.raw());
    }
    badRaw_.clear();

    if (vni_) {
        vniEngine_.endWord(static_cast<char32_t>(boundary));
        if (boundary == 0) vniEngine_.clearContext();
    } else {
        engine_.endWord(static_cast<char32_t>(boundary));
        if (boundary == 0) engine_.clearContext();
    }
}

RevertOutcome VgoBridge::tryRevertCorrection(const std::string& textBeforeCursor) {
    RevertOutcome res;
    if (!canRevert_) return res;
    canRevert_ = false;
    const std::string committed = revShown_ + revSep_;
    if (textBeforeCursor.size() < committed.size()) return res;
    if (textBeforeCursor.compare(textBeforeCursor.size() - committed.size(),
                                 committed.size(), committed) != 0) {
        return res;
    }
    engine_.clearContext();
    res.reverted = true;
    res.committedToDelete = committed;
    res.replacement = revRaw_ + revSep_;
    return res;
}

void VgoBridge::reset() {
    engine_.reset();
    engine_.clearContext();
    vniEngine_.reset();
    vniEngine_.clearContext();
    badRaw_.clear();
    canRevert_ = false;
}

void VgoBridge::clearContext() {
    engine_.clearContext();
    vniEngine_.clearContext();
    badRaw_.clear();
    canRevert_ = false;
}

Snapshot VgoBridge::snapshot() const {
    Snapshot s;
    if (vni_) {
        s.empty = vniEngine_.empty();
        if (s.empty) return s;
        s.display = vniEngine_.display();
        s.raw = vniEngine_.raw();
        s.showingRaw = vniEngine_.isShowingRaw();
        s.corrected = vniEngine_.isCorrected();
        vgo::VniEngine tmp = vniEngine_;
        tmp.flip();
        std::string alt = tmp.display();
        if (alt != s.display) s.altDisplay = std::move(alt);
        return s;
    }

    s.empty = engine_.empty();
    if (s.empty) return s;
    s.display = engine_.display();
    s.raw = engine_.raw();
    s.showingRaw = engine_.isShowingRaw();
    s.corrected = engine_.isCorrected();
    std::string alt;
    if (s.corrected) {
        alt = s.raw;
    } else if (s.showingRaw) {
        alt = engine_.preedit();
    } else {
        alt = s.raw;
    }
    if (alt != s.display) s.altDisplay = std::move(alt);
    return s;
}

} // namespace vgo_android

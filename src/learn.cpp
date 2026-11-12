#include "learn.hpp"
#include <cctype>
#include <fstream>
#include <map>
#include <sstream>
#include <unordered_map>

namespace vgo {
namespace {

// app -> ("kind\ttừ" -> 1 thô / -1 Việt). map để file ghi ra có thứ tự ổn định.
std::unordered_map<std::string, std::map<std::string, int>>& store() {
    static std::unordered_map<std::string, std::map<std::string, int>> s;
    return s;
}

std::string lower(const std::string& s) {
    std::string r = s;
    for (char& c : r) c = (char)std::tolower((unsigned char)c);
    return r;
}

} // namespace

void appSet(const std::string& app, const std::string& kind, const std::string& word, int value) {
    if (app.empty() || word.empty()) return;
    auto& m = store()[app];
    std::string k = kind + "\t" + lower(word);
    if (value) m[k] = value > 0 ? 1 : -1;
    else m.erase(k);
}

int appGet(const std::string& app, const std::string& kind, const std::string& word) {
    if (app.empty()) return 0;
    auto it = store().find(app);
    if (it == store().end()) return 0;
    auto jt = it->second.find(kind + "\t" + lower(word));
    return jt == it->second.end() ? 0 : jt->second;
}

void appLoadFile(const std::string& app, const std::string& path) {
    std::ifstream f(path);
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream in(line);
        std::string kind, word, val;
        if (in >> kind >> word >> val && (val == "raw" || val == "vn"))
            appSet(app, kind, word, val == "raw" ? 1 : -1);
    }
}

bool appSaveFile(const std::string& app, const std::string& path) {
    std::ofstream f(path, std::ios::trunc);
    if (!f) return false;
    f << "# vgo: lựa chọn Esc riêng của app " << app << " (kind từ raw|vn)\n";
    auto it = store().find(app);
    if (it == store().end()) return true;
    for (auto& [k, v] : it->second) {
        size_t tab = k.find('\t');
        f << k.substr(0, tab) << ' ' << k.substr(tab + 1) << ' ' << (v > 0 ? "raw" : "vn") << '\n';
    }
    return true;
}

} // namespace vgo

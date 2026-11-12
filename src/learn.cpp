#include "learn.hpp"
#include <algorithm>
#include <cctype>
#include <vector>
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

// ================= Ngữ cảnh =================
namespace {
std::unordered_map<std::string, int>& ctxMap() {   // "kind\tprev\ttừ" -> 1 / -1
    static std::unordered_map<std::string, int> m;
    return m;
}
} // namespace

void ctxSet(const std::string& kind, const std::string& prev, const std::string& word, int value) {
    if (prev.empty() || word.empty()) return;
    std::string k = kind + "\t" + lower(prev) + "\t" + lower(word);
    if (value) ctxMap()[k] = value > 0 ? 1 : -1;
    else ctxMap().erase(k);
}
int ctxGet(const std::string& kind, const std::string& prev, const std::string& word) {
    if (prev.empty()) return 0;
    auto it = ctxMap().find(kind + "\t" + lower(prev) + "\t" + lower(word));
    return it == ctxMap().end() ? 0 : it->second;
}
void ctxLoadFile(const std::string& kind, const std::string& path) {
    std::ifstream f(path);
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream in(line);
        std::string prev, word, val;
        if (in >> prev >> word >> val && (val == "raw" || val == "vn"))
            ctxSet(kind, prev, word, val == "raw" ? 1 : -1);
    }
}

// ================= Lỗi gõ riêng =================
namespace {
// "kind\tphím sai" -> (phím đúng -> số lần gặp)
std::unordered_map<std::string, std::unordered_map<std::string, int>>& typoMap() {
    static std::unordered_map<std::string, std::unordered_map<std::string, int>> m;
    return m;
}
bool allAlnum(const std::string& s) {
    for (char c : s) if (!std::isalnum((unsigned char)c)) return false;
    return !s.empty();
}
} // namespace

int keyEditDistance(const std::string& a, const std::string& b) {
    int n = (int)a.size(), m = (int)b.size();
    std::vector<std::vector<int>> d(n + 1, std::vector<int>(m + 1));
    for (int i = 0; i <= n; ++i) d[i][0] = i;
    for (int j = 0; j <= m; ++j) d[0][j] = j;
    for (int i = 1; i <= n; ++i)
        for (int j = 1; j <= m; ++j) {
            d[i][j] = std::min({d[i-1][j] + 1, d[i][j-1] + 1,
                                d[i-1][j-1] + (a[i-1] != b[j-1])});
            if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1])
                d[i][j] = std::min(d[i][j], d[i-2][j-2] + 1);
        }
    return d[n][m];
}

// Chỉ học khi phím sai và phím đúng gần nhau (<= 2 phép sửa): gõ lại một từ khác hẳn
// là đổi ý, không phải lỗi gõ.
bool typoLearnable(const std::string& bad, const std::string& good) {
    std::string b = lower(bad), g = lower(good);
    if (b.size() < 2 || g.size() < 2 || b == g || !allAlnum(b) || !allAlnum(g)) return false;
    return keyEditDistance(b, g) <= 2;
}
void typoNote(const std::string& kind, const std::string& bad, const std::string& good) {
    if (typoLearnable(bad, good)) ++typoMap()[kind + "\t" + lower(bad)][lower(good)];
}
void typoLoadFile(const std::string& kind, const std::string& path) {
    std::ifstream f(path);
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream in(line);
        std::string bad, good;
        if (in >> bad >> good) typoNote(kind, bad, good);
    }
}
std::string typoLookup(const std::string& kind, const std::string& bad) {
    auto it = typoMap().find(kind + "\t" + lower(bad));
    if (it == typoMap().end()) return "";
    std::string best;
    int bestN = 0;
    for (auto& [good, n] : it->second)
        if (n > bestN || (n == bestN && good < best)) { best = good; bestN = n; }
    return bestN >= TYPO_MIN ? best : "";
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

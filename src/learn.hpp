// vgo — phần tự học dùng chung cho Telex (kind "telex") và VNI (kind "vni").
#pragma once
#include <string>

namespace vgo {

// ---- Nhớ riêng từng app: lựa chọn Esc (giữ thô / ra tiếng Việt) của một app thắng
// lựa chọn chung. value: 1 = giữ thô (Anh / số), -1 = ra tiếng Việt. app rỗng = bỏ qua.
void appSet(const std::string& app, const std::string& kind, const std::string& word, int value);
int  appGet(const std::string& app, const std::string& kind, const std::string& word);  // 0 = chưa có

// File mỗi app: mỗi dòng "kind từ raw|vn", vd "telex ban raw", "vni a4 vn".
void appLoadFile(const std::string& app, const std::string& path);
bool appSaveFile(const std::string& app, const std::string& path);

// ---- Ngữ cảnh (từ trước + phím thô) -> giữ thô / ra Việt. Hiện VNI dùng (Telex có
// bigram riêng trong engine). prev = chữ đã hiện ra của từ trước.
void ctxSet(const std::string& kind, const std::string& prev, const std::string& word, int value);
int  ctxGet(const std::string& kind, const std::string& prev, const std::string& word);  // 0 = chưa có
void ctxLoadFile(const std::string& kind, const std::string& path);  // dòng "prev từ raw|vn"

// ---- LỖI GÕ RIÊNG (tự học thói quen): phím gõ sai -> phím gõ lại cho đúng ----
// Người gõ xoá một từ không ra tiếng Việt rồi gõ lại thành âm tiết đúng -> ghi 1 lần.
// Gặp đủ TYPO_MIN lần (thói quen, không phải lỡ tay) thì tự sửa.
constexpr int TYPO_MIN = 2;
int  keyEditDistance(const std::string& a, const std::string& b);  // Damerau (đảo 2 phím kề = 1)
bool typoLearnable(const std::string& bad, const std::string& good);  // đủ gần để là lỗi gõ?
void typoNote(const std::string& kind, const std::string& bad, const std::string& good);
void typoLoadFile(const std::string& kind, const std::string& path);  // dòng "sai đúng" = 1 lần
std::string typoLookup(const std::string& kind, const std::string& bad);  // "" = chưa đủ / không có

} // namespace vgo

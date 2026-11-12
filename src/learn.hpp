// vgo — nhớ riêng từng app: lựa chọn Esc (giữ thô / ra tiếng Việt) của một app thắng
// lựa chọn chung. Dùng chung cho Telex (kind "telex") và VNI (kind "vni").
#pragma once
#include <string>

namespace vgo {

// value: 1 = giữ thô (Anh / số), -1 = ra tiếng Việt. app rỗng = không làm gì.
void appSet(const std::string& app, const std::string& kind, const std::string& word, int value);
int  appGet(const std::string& app, const std::string& kind, const std::string& word);  // 0 = chưa có

// File mỗi app: mỗi dòng "kind từ raw|vn", vd "telex ban raw", "vni a4 vn".
void appLoadFile(const std::string& app, const std::string& path);
bool appSaveFile(const std::string& app, const std::string& path);

} // namespace vgo

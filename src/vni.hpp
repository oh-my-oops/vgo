// vgo-vni — engine VNI thuần (không phụ thuộc OS), module riêng, không đụng Telex.
// Số 1-5 = sắc huyền hỏi ngã nặng, 6 = mũ (â ê ô), 7 = móc (ơ ư), 8 = trăng (ă),
// 9 = đ, 0 = xoá thanh. Gõ lại cùng số = hoàn tác ("a11" -> "a1").
// Số chỉ thành dấu khi kết quả vẫn là âm tiết Việt hợp lệ (như UniKey), nên
// "win10", "h264", "user1", "iphone15", "2026" giữ nguyên.
#pragma once
#include <string>

namespace vgo {

struct VniConfig {
    // true = kiểu cũ "hòa/thúy", false = kiểu mới "hoà/thuý"
    bool oldStyleTone = true;
};

// Xử lý MỘT từ đang gõ. Tầng tích hợp (fcitx/CLI) chốt từ rồi gọi reset().
class VniEngine {
public:
    explicit VniEngine(VniConfig cfg = {}) : cfg_(cfg) {}

    void key(char c);          // nạp 1 phím ASCII (chữ hoặc số)
    void backspace();          // lùi đúng 1 phím thô
    void reset() { buf_.clear(); raw_.clear(); flipped_ = false; strayDigit_ = false;
                   noCorrect_ = false; }
    void flip();               // Esc: lật thô <-> tiếng Việt (từ này); bản tự sửa -> phím thô
    // App đang gõ: lựa chọn Esc riêng của app thắng lựa chọn chung (xem learn.hpp).
    void setApp(const std::string& app) { app_ = app; }

    // Ngữ cảnh (như Telex): chốt từ đang gõ trước ranh giới `boundary` (0 = không có)
    // rồi reset(). "giấy a4": tới lúc gõ "a4" thì prev() == "giấy".
    void endWord(char32_t boundary);
    void clearContext() { tok_.clear(); prev_.clear(); }
    const std::string& prev() const { return prev_; }

    bool empty() const { return raw_.empty(); }
    const std::string& raw() const { return raw_; }
    bool isShowingRaw() const;
    bool isCorrected() const;  // đang hiện bản tự sửa theo lỗi gõ riêng (typoLookup "vni")
    bool showingVN() const { return isCorrected() || !isShowingRaw(); }
    // Từ "hỏng": có số không thành dấu được / làm hỏng âm tiết, chưa Esc, chưa học, chưa
    // tự sửa. Người gõ xoá từ hỏng rồi gõ lại đúng -> học lỗi gõ (tầng fcitx).
    bool rejected() const;
    bool complete() const;     // âm tiết Việt TRỌN hợp lệ (không còn số thô)
    std::string display() const;            // chuỗi để hiện ra (UTF-8)

private:
    VniConfig cfg_;
    std::u32string buf_;       // buffer đã biến đổi
    std::string raw_;          // phím thô đã gõ
    bool flipped_ = false;
    bool strayDigit_ = false;  // có số không thành dấu được ("i18n": 8) -> không phải từ Việt
    std::string app_;          // app đang gõ, "" = không rõ
    bool noCorrect_ = false;   // Esc trên bản tự sửa -> hiện phím thô
    std::string tok_, prev_;   // token đang gõ (phần đã chốt) / token trước

    bool rulesRaw() const;     // luật cấu trúc: số làm hỏng âm tiết -> thô
    int learned() const;       // đã học: ngữ cảnh > riêng app > chung. 1 thô, -1 Việt, 0 chưa
    std::string correction() const;  // bản sửa theo lỗi gõ riêng, "" nếu không có

    void appendLiteral(char c);
    void normalizeTone();
    bool applyDigit(int d, char typed);
    bool handleTone(int tone, char typed);
    bool handleRemoveTone();
    bool handleCircum(char typed);
    bool handleHorn(char typed);
    bool handleBreve(char typed);
    bool handleDStroke(char typed);
};

// ---- Esc tự nhớ (chung mọi app), theo phím thô có số: "a4" giữ thô, "ba3n" ra Việt ----
// keepRaw = true: lần sau giữ thô; false: lần sau ra tiếng Việt. Lần lật gần nhất thắng.
void vniLearn(const std::string& raw, bool keepRaw);
void vniLoadFile(const std::string& path, bool keepRaw);   // mỗi dòng 1 chuỗi phím
int  vniLookup(const std::string& raw);                    // 1 thô, -1 Việt, 0 chưa học

// Tiện ích cho test/CLI: nạp cả chuỗi phím, chốt từ khi gặp ký tự không phải chữ/số.
std::string vniTypeString(const std::string& keys, VniConfig cfg = {});

} // namespace vgo

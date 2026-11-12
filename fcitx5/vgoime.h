// vgo — fcitx5 input method addon (bọc engine Telex + VNI thuần; chọn kiểu gõ trong cài đặt).
// Chế độ chính: KHÔNG preedit — commit thẳng + xoá-ghi-đè tại chỗ bằng
// surrounding text (giống UniKey/Lotus, không phá autofill). Tối ưu remote:
// in-process, ghi-đè theo diff (chỉ phần đuôi đổi), tự track ký tự đã commit.
// App không hỗ trợ surrounding text -> fallback preedit (vd terminal).
#ifndef VGO_FCITX5_VGOIME_H
#define VGO_FCITX5_VGOIME_H

#include <string>

#include <fcitx-config/configuration.h>
#include <fcitx-config/enum.h>
#include <fcitx-utils/i18n.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputcontextproperty.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/instance.h>

#include "engine.hpp"
#include "vni.hpp"

// Kiểu gõ, chọn trong fcitx5-configtool (lưu ở ~/.config/fcitx5/conf/vgo.conf).
enum class VgoMethod { Telex, VNI };
FCITX_CONFIG_ENUM_NAME_WITH_I18N(VgoMethod, N_("Telex"), N_("VNI"));

FCITX_CONFIGURATION(
    VgoConfig,
    fcitx::OptionWithAnnotation<VgoMethod, VgoMethodI18NAnnotation> method{
        this, "InputMethod", "Kiểu gõ", VgoMethod::Telex};
    // Chromium/Electron mặc định ép preedit (xoá chữ lỗi trên GNOME). KDE Wayland
    // (KWin text-input) có thể xoá được -> cho người dùng bật thử.
    fcitx::Option<bool> chromiumNoPreedit{
        this, "ChromiumNoPreedit",
        "Không gạch chân trong Chrome/Slack/VS Code (thử nghiệm)", false};);

// Trạng thái soạn thảo riêng từng ô nhập.
class VgoState : public fcitx::InputContextProperty {
public:
    vgo::Engine engine;         // Telex
    vgo::VniEngine vniEngine;   // VNI
    bool vni = false;           // kiểu gõ của từ đang gõ (đổi cài đặt giữa chừng -> chốt từ cũ)
    bool empty() const { return vni ? vniEngine.empty() : engine.empty(); }
    std::string display() const { return vni ? vniEngine.display() : engine.display(); }
    std::string shown;          // text đã commit cho từ đang gõ (chế độ surrounding)
    bool usingPreedit = false;  // true nếu ô này phải fallback preedit
    // Từ vừa chốt là bản tự sửa -> Backspace NGAY sau đó trả lại phím thô
    // (như LatinIME InputLogic.revertCommit). Phím khác bất kỳ -> hết hiệu lực.
    bool canRevert = false;
    std::string revRaw, revShown, revSep;
    // Phím thô của từ KHÔNG ra tiếng Việt lúc người gõ bắt đầu xoá nó (Telex). Gõ lại
    // thành âm tiết đúng rồi chốt -> ghi 1 lần lỗi gõ riêng (xem vgo::typoNote).
    std::string badRaw;
};

class VgoEngine : public fcitx::InputMethodEngineV2 {
public:
    explicit VgoEngine(fcitx::Instance *instance);

    void keyEvent(const fcitx::InputMethodEntry &entry,
                  fcitx::KeyEvent &keyEvent) override;
    void reset(const fcitx::InputMethodEntry &entry,
               fcitx::InputContextEvent &event) override;

    const fcitx::Configuration *getConfig() const override { return &config_; }
    void setConfig(const fcitx::RawConfig &raw) override;
    void reloadConfig() override;
    std::string subModeLabelImpl(const fcitx::InputMethodEntry &,
                                 fcitx::InputContext &) override;

private:
    void render(fcitx::InputContext *ic, VgoState *state);       // áp display() ra ô nhập
    void showPreedit(fcitx::InputContext *ic, VgoState *state);  // fallback
    void finalize(fcitx::InputContext *ic, VgoState *state,
                  uint32_t boundary = 0);                        // chốt từ + dọn
    bool revertCorrection(fcitx::InputContext *ic, VgoState *state);  // Backspace hoàn tác

    bool vniMode() const { return *config_.method == VgoMethod::VNI; }

    fcitx::Instance *instance_;
    VgoConfig config_;
    fcitx::FactoryFor<VgoState> factory_;
};

class VgoEngineFactory : public fcitx::AddonFactory {
public:
    fcitx::AddonInstance *create(fcitx::AddonManager *manager) override {
        return new VgoEngine(manager->instance());
    }
};

#endif

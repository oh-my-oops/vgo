// Test VNI tự nhớ Esc (chung + riêng từng app). Ca gõ thường nằm ở test/vni-cases.txt.
#include "learn.hpp"
#include "vni.hpp"
#include <cstdio>
#include <string>
#include <vector>

using namespace vgo;

static std::string typeIn(const char* app, const char* keys) {
    VniEngine e;
    e.setApp(app);
    for (char c : std::string(keys)) e.key(c);
    return e.display();
}

int main() {
    int pass = 0, fail = 0;
    struct Case { const char* app; const char* keys; const char* want; const char* note; };
    auto run = [&](const std::vector<Case>& cases) {
        for (auto& c : cases) {
            std::string got = typeIn(c.app, c.keys);
            if (got == c.want) ++pass;
            else { ++fail; std::printf("FAIL(%s) [%s] %s -> got [%s]  want [%s]\n",
                                       c.note, c.app, c.keys, got.c_str(), c.want); }
        }
    };

    run({{"", "a4", "a4", "mặc định"}, {"", "ba3n", "bản", "mặc định"}});

    vniLearn("a4", false);     // Esc "a4" -> "ã": lần sau ra Việt
    vniLearn("ba3n", true);    // Esc "bản" -> "ba3n": lần sau giữ thô
    run({{"", "a4", "ã", "chung"}, {"", "A4", "Ã", "chung"}, {"", "ba3n", "ba3n", "chung"},
         {"", "ba1n", "bán", "chung"}});

    vniLearn("ba3n", false);   // lật lại -> lần gần nhất thắng
    run({{"", "ba3n", "bản", "lật lại"}});

    appSet("konsole", "vni", "ba3n", 1);   // riêng terminal giữ thô
    run({{"konsole", "ba3n", "ba3n", "app"}, {"slack", "ba3n", "bản", "app"},
         {"", "ba3n", "bản", "app"}});

    {   // Esc trong lúc gõ vẫn lật được từ đã học
        VniEngine e;
        e.setApp("konsole");
        for (char c : std::string("ba3n")) e.key(c);
        e.flip();
        if (e.display() == "bản") ++pass;
        else { ++fail; std::printf("FAIL(flip) -> [%s]\n", e.display().c_str()); }
    }

    auto check = [&](bool ok, const char* note, const std::string& got) {
        if (ok) ++pass;
        else { ++fail; std::printf("FAIL(%s) -> [%s]\n", note, got.c_str()); }
    };

    {   // Ngữ cảnh (như bigram Telex): "a4" chung đã học ra "ã", nhưng sau "giấy" giữ thô
        ctxSet("vni", "giấy", "a4", 1);
        VniEngine e;
        for (char c : std::string("gia61y")) e.key(c);
        e.endWord(' ');
        check(e.prev() == "giấy", "ctx prev", e.prev());
        for (char c : std::string("a4")) e.key(c);
        check(e.display() == "a4", "ctx giấy a4", e.display());
        e.endWord(' ');
        for (char c : std::string("a4")) e.key(c);
        check(e.display() == "ã", "ctx a4 a4 (không phải sau giấy)", e.display());
        e.reset(); e.clearContext();
        for (char c : std::string("a4")) e.key(c);
        check(e.display() == "ã", "ctx đầu dòng", e.display());
    }

    {   // Lỗi gõ quen tay: "t1ie6ng" (bấm dấu quá sớm) xoá rồi gõ lại "tie6ng1" 2 lần
        VniEngine e;
        for (char c : std::string("t1ie6ng")) e.key(c);
        check(e.rejected() && e.display() == "t1ie6ng", "typo trước khi học", e.display());
        VniEngine g;
        for (char c : std::string("tie6ng1")) g.key(c);
        check(g.complete() && !g.rejected(), "typo bản đúng complete", g.display());
        check(typoLearnable("t1ie6ng", "tie6ng1"), "typo learnable", "");
        typoNote("vni", "t1ie6ng", "tie6ng1");
        check(typeIn("", "t1ie6ng") == "t1ie6ng", "typo 1 lần chưa sửa", typeIn("", "t1ie6ng"));
        typoNote("vni", "t1ie6ng", "tie6ng1");
        run({{"", "t1ie6ng", "tiếng", "typo 2 lần"}, {"", "T1ie6ng", "Tiếng", "typo hoa"}});
        VniEngine f;
        for (char c : std::string("t1ie6ng")) f.key(c);
        check(f.isCorrected() && !f.rejected(), "typo isCorrected", f.display());
        f.flip();   // Esc trên bản tự sửa -> về đúng phím thô
        check(f.display() == "t1ie6ng", "typo Esc", f.display());
        check(typoLookup("telex", "t1ie6ng").empty(), "typo VNI không lẫn Telex", "");
    }

    std::printf("\n%d passed, %d failed (%d total)\n", pass, fail, pass + fail);
    return fail ? 1 : 0;
}

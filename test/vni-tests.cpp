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

    std::printf("\n%d passed, %d failed (%d total)\n", pass, fail, pass + fail);
    return fail ? 1 : 0;
}

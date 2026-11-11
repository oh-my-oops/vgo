cmake_minimum_required(VERSION 3.6)
project(vgo VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)  # header fcitx5 >= 5.1.1x dùng std::span/ranges
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Cài vào đúng thư mục fcitx5 của distro (/usr/lib/.../fcitx5, /usr/share/fcitx5)
# — nơi fcitx5 chắc chắn quét, cùng chỗ Lotus. Tránh /usr/local (có thể không nạp).
set(FCITX_INSTALL_USE_FCITX_SYS_PATHS ON CACHE BOOL "Dùng thư mục cài của fcitx5 distro")

find_package(ECM 1.0.0 REQUIRED)
list(APPEND CMAKE_MODULE_PATH "${ECM_MODULE_PATH}")
find_package(Fcitx5Core REQUIRED)
find_package(Fcitx5Utils REQUIRED)
find_package(Fcitx5Config REQUIRED)

# Một bộ gõ "vgo" gồm cả Telex (src/engine.cpp) lẫn VNI (src/vni.cpp); chọn kiểu gõ
# trong fcitx5-configtool. Chỉ export factory của fcitx (ẩn symbol còn lại).
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(ICON_DIR "${FCITX_INSTALL_DATADIR}/icons/hicolor/scalable/apps")

add_library(vgo SHARED
    src/engine.cpp
    src/vni.cpp
    src/viet.cpp
    fcitx5/vgoime.cpp
)
set_target_properties(vgo PROPERTIES PREFIX "lib")
target_include_directories(vgo PRIVATE src)
target_link_libraries(vgo Fcitx5::Core Fcitx5::Config Fcitx5::Utils)

install(TARGETS vgo LIBRARY DESTINATION "${FCITX_INSTALL_LIBDIR}/fcitx5")
install(FILES "${CMAKE_SOURCE_DIR}/fcitx5/vgo-addon.conf" RENAME vgo.conf
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon")
install(FILES "${CMAKE_SOURCE_DIR}/fcitx5/vgo.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod")
# Từ điển base (Anh) + danh sách ép-Việt + bigram + âm tiết Việt -> /usr/share/vgo/ (chỉ Telex dùng)
install(FILES "${CMAKE_SOURCE_DIR}/data/en-base.txt"
              "${CMAKE_SOURCE_DIR}/data/vn-base.txt"
              "${CMAKE_SOURCE_DIR}/data/bigram-base.txt"
              "${CMAKE_SOURCE_DIR}/data/vn-syllables.txt"
        DESTINATION "${FCITX_INSTALL_DATADIR}/vgo")
install(FILES "${CMAKE_SOURCE_DIR}/fcitx5/vgo.svg" RENAME fcitx-vgo.svg
        DESTINATION "${ICON_DIR}")

# Cập nhật icon-theme cache để fcitx5 thấy icon mới (nếu không, hiện icon mặc định).
install(CODE "execute_process(COMMAND gtk-update-icon-cache -f -q \"${FCITX_INSTALL_DATADIR}/icons/hicolor\" ERROR_QUIET)")

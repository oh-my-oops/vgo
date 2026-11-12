# Cài đặt + sự cố vgo (Telex / VNI)

Bộ gõ chỉ chạy trên **Linux có fcitx5**. Bản build sẵn trong `bin/` làm trên
Ubuntu 24.04 (GNOME, fcitx5 5.1.7); máy khác thì build từ source (xem `HUONG-DAN.md`).

## 1. Bật fcitx5 (một lần)

Ubuntu mặc định dùng IBus, phải đổi sang fcitx5:

```sh
sudo apt install fcitx5 fcitx5-frontend-all fcitx5-config-qt im-config
im-config -n fcitx5
```

Rồi **đăng xuất / đăng nhập lại**. Kiểm tra:

```sh
echo $GTK_IM_MODULE $QT_IM_MODULE $XMODIFIERS   # phải ra: fcitx fcitx @im=fcitx
pgrep -a fcitx5                                 # phải thấy tiến trình fcitx5
```

## 2. Cài bộ gõ

```sh
./install.sh        # cài những gì có trong bin/ (hỏi mật khẩu sudo)
fcitx5 -r -d        # khởi động lại fcitx5
fcitx5-configtool   # Input Method -> bỏ tick "Only Show Current Language"
                    # -> tìm "vgo" -> thêm vào cột bên trái -> Apply
```

Chọn **Telex hay VNI**: fcitx5-configtool -> chọn "vgo" ở cột trái -> nút cấu hình (bánh
răng) -> "Kiểu gõ". Đổi xong có hiệu lực ngay, không cần khởi động lại. Nhãn trên khay:
`T` = Telex, `V` = VNI.

Chọn bộ gõ: bấm biểu tượng fcitx5 trên thanh hệ thống, hoặc dùng phím tắt — xem / đổi trong
fcitx5-configtool -> Global Options -> Hotkey ("Trigger Input Method" = bật/tắt,
"Enumerate Input Method" = chuyển giữa các bộ gõ đã thêm).
Gỡ: `./uninstall.sh` (chỉ gỡ bộ gõ của gói này).

### Tự học (lưu ở `~/.config/vgo/`, sửa tay được)

| Học gì | Khi nào | File |
|---|---|---|
| Lật Anh/Việt (Telex) | Esc khi đang gõ dở | `words.txt`, `vn-words.txt`, `bigram.txt` |
| Lật thô/Việt (VNI) | Esc khi đang gõ dở từ có số (`a4` -> `ã`) | `vni-raw.txt`, `vni-vn.txt` |
| Riêng từng app | Esc như trên: app nhớ lựa chọn của nó, thắng lựa chọn chung | `apps/<tên app>.txt` |
| Lỗi gõ quen tay (Telex) | Gõ ra chữ không phải tiếng Việt, xoá rồi gõ lại đúng. Đủ 2 lần thì tự sửa | `typos.txt` (mỗi dòng 1 lần) |

Lựa chọn chung = lần Esc gần nhất ở bất kỳ app nào. Học sai: xoá dòng trong file rồi
`pkill -x fcitx5` (fcitx5 tự chạy lại) để nạp lại.

## 3. Sự cố thường gặp

| Triệu chứng | Nguyên nhân / cách xử |
|---|---|
| `install.sh` báo "khác bản fcitx5" | Máy khác bản Ubuntu/fcitx5 -> build từ source |
| Không thấy "vgo" trong fcitx5-configtool | Chưa `fcitx5 -r -d`; hoặc chưa bỏ tick "Only Show Current Language" |
| Gõ không ra gì ở mọi app | fcitx5 chưa phải bộ gõ hệ thống -> làm lại mục 1, nhớ đăng xuất |
| Gõ được ở app này, app kia không | App cài kiểu **snap / flatpak** chạy sandbox, chặn mọi bộ gõ (không phải lỗi vgo). Cài bản .deb / bản tải thẳng từ trang chủ |
| Chrome / VS Code / Slack có gạch chân dưới chữ đang gõ | Bình thường: app nền Chromium phải dùng chế độ preedit |
| Chữ bị lặp / nhân đôi ở một app lạ (`các` -> `caác`) | App đó xoá chữ lỗi -> thêm tên chương trình vào `isChromiumApp()` trong `fcitx5/*ime.cpp`, build lại |
| Đổi icon mà không thấy | `pkill -x fcitx5; fcitx5 -d` (`-r` không xả cache icon) |
| Muốn xem log | `fcitx5 -r --verbose '*=5'` chạy trong terminal (không `-d`), gõ thử, đọc log; Ctrl+C rồi `fcitx5 -d` để chạy lại bình thường |

## 4. Sửa code xong thì cài lại

```sh
make test                       # phải qua hết trước khi cài
cmake --build build
sudo cmake --install build
fcitx5 -r -d
```

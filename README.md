# XAPK Installer (Kotlin / Android Studio)

## Cách mở
1. Android Studio > Open > chọn thư mục `XapkInstaller`.
2. Dùng JDK 17 (Settings > Build, Execution, Deployment > Build Tools > Gradle).
3. Cho Gradle đồng bộ, cài Android SDK Platform 35 nếu được hỏi.
4. Cắm điện thoại Android 8.0+ (API 26+), bật USB Debugging, bấm Run.
5. Chọn XAPK và xác nhận quyền `Install unknown apps` khi hệ thống hỏi.

Lưu ý: mã nguồn đi kèm `gradle-wrapper.properties` nhưng không kèm file nhị phân
`gradle-wrapper.jar` hoặc script `gradlew`. Android Studio có thể đồng bộ project
qua Gradle distribution; nếu yêu cầu wrapper đầy đủ, chạy `gradle wrapper
--gradle-version 8.9` bằng một bản Gradle đã cài trên máy, rồi Sync lại.
Chưa thực hiện build APK thực tế vì môi trường tạo project không có Android SDK.

## Công nghệ
- Storage Access Framework / `ActivityResultContracts.OpenDocument` (đọc XAPK không cần quyền bộ nhớ).
- `ZipInputStream`, chặn Zip Slip, giới hạn giải nén 12 GiB.
- Kotlin Coroutines, làm việc I/O trên `Dispatchers.IO`.
- `PackageInstaller.Session` để cài base APK và split APK cùng một session.
- `PendingIntent.getActivity` (explicit + mutable) để nhận trạng thái cài đặt và xử lý xác nhận từ người dùng.
- `FileProvider` được khai báo để mở rộng nếu cần chia sẻ file; không dùng trong đường cài đặt Session.

## Giới hạn quan trọng
- Android 11+ giới hạn nghiêm ngặt `Android/obb`. `MANAGE_EXTERNAL_STORAGE` không đảm bảo ghi được thư mục này và SAF không được chọn `Android/obb`. Ứng dụng sẽ thử thao tác ghi, thông báo chính xác khi thất bại và hỏi người dùng có muốn chỉ cài APK hay không.
- Để chép OBB trên máy hạn chế, có thể dùng ADB với quyền của `adb shell`, hoặc tích hợp chức năng nhập dữ liệu từ chính app đích. Không có API Android công khai bảo đảm ứng dụng bên thứ ba được ghi OBB của ứng dụng khác.
- Bộ tách APK tìm `base.apk`, `apk_file` trong `manifest.json`, `<package>.apk`, `app.apk`, `base-*` hoặc APK duy nhất. Các bộ split không có cách nhận diện base theo những quy ước này sẽ báo lỗi thay vì đoán bừa.
- Các file split phải cùng ứng dụng, phiên bản và chữ ký. Hệ thống PackageInstaller kiểm tra chữ ký và tính tương thích, bao gồm ABI/SDK và split bị thiếu.
- Hỗ trợ XAPK ở dạng ZIP. Không hỗ trợ `.apks` nhúng ZIP khác, XAPK mã hóa hoặc các định dạng đặc thù.
- Tệp XAPK không đáng tin có thể chứa mã độc; chỉ cài ứng dụng từ nguồn bạn tin cậy.
- Phiên bản mẫu xử lý công việc khi Activity đang hoạt động; nếu muốn bảo đảm tiếp tục sau khi ứng dụng bị đóng trong lúc giải nén, hãy chuyển pipeline sang foreground service với notification.

## Kiểm thử OBB bằng ADB (khi thiết bị cho phép)
Ví dụ package `com.example.game`, OBB là `main.1.com.example.game.obb`:

```bash
adb shell mkdir -p /sdcard/Android/obb/com.example.game
adb push main.1.com.example.game.obb /sdcard/Android/obb/com.example.game/
```

## Chính sách Google Play
`MANAGE_EXTERNAL_STORAGE` và `REQUEST_INSTALL_PACKAGES` bị giới hạn mục đích sử dụng khi phát hành Play Store. Với APK cài nội bộ, vẫn phải xin quyền đặc biệt trên thiết bị.

# Truy vấn Tham số hóa vs. Nối chuỗi SQL trong Java JPA

> Tài liệu đào tạo code review sử dụng ví dụ thực tế từ `UserService.java`

---

## 1. Khái niệm cốt lõi

| Khía cạnh | Truy vấn tham số hóa (`:param` / `?1`) | Nối chuỗi (`"..." + biến + "..."`) |
|---|---|---|
| **Cách giá trị đi vào SQL** | Được gửi dưới dạng tham số bind riêng biệt; DB engine **không bao giờ** phân tích chúng như cú pháp SQL | Được chèn trực tiếp vào chuỗi SQL; DB engine phân tích giá trị người dùng **như một phần của câu lệnh** |
| **Rủi ro SQL Injection** | ✅ **Miễn nhiễm** — giá trị không thể thay đổi cấu trúc truy vấn | ❌ **Dễ bị tấn công** — chuỗi do attacker kiểm soát có thể viết lại truy vấn |
| **Hiệu suất** | DB có thể cache & tái sử dụng prepared-statement plan | Mỗi input khác nhau tạo ra plan mới (không cache được) |
| **Khả năng đọc** | Tách rõ ràng giữa logic và dữ liệu | Khó đọc, dễ sai khi đặt dấu nháy |

---

## 2. Lỗ hổng thực tế phát hiện trong mã nguồn của bạn

> [!CAUTION]
> Phương thức `searchUsers` trong [UserService.java](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L176-L215) chứa **lỗ hổng SQL Injection đang hoạt động** thông qua nối chuỗi.

### 🔴 Mã không an toàn — Dòng 196-202

```java
// ❌ KHÔNG AN TOÀN: Nối chuỗi chèn input người dùng trực tiếp vào SQL
String fetchSql =
    "SELECT * FROM users " +
    "WHERE deleted = false " +
    "AND (LOWER(username) LIKE LOWER('%" + keyWord + "%') " +  // 💀 SQLi Ở ĐÂY
    "OR LOWER(email) LIKE LOWER('%" + keyWord + "%')) " +      // 💀 SQLi Ở ĐÂY
    "ORDER BY username ASC";

List<User> content = entityManager
        .createNativeQuery(fetchSql, User.class)
        .setParameter("keyword", searchParam)   // ← các param này KHÔNG được dùng trong fetchSql!
        .setParameter("limit", size)
        .setParameter("offset", page * size)
        .getResultList();
```

**Tại sao nó nguy hiểm:**
1. `keyWord` được nhúng **thô** vào chuỗi SQL trước khi nó đến database driver.
2. Các lệnh `.setParameter("keyword", ...)` tồn tại nhưng là **mã chết** — `fetchSql` không có placeholder `:keyword`, `:limit`, hay `:offset`.
3. Attacker có thể kết thúc mệnh đề `LIKE` và tiêm SQL tùy ý.

### Ví dụ tấn công

```
GET /api/users/search?keyword=' OR 1=1) -- 
```

Câu SQL được sinh ra sẽ trở thành:

```sql
SELECT * FROM users 
WHERE deleted = false 
AND (LOWER(username) LIKE LOWER('%' OR 1=1) -- %')   ← phần còn lại bị comment hết
OR LOWER(email) LIKE LOWER('%' OR 1=1) -- %'))
ORDER BY username ASC
```

Điều này bypass bộ lọc tìm kiếm và **trả về toàn bộ người dùng**, bao gồm cả những user đã bị xóa mềm.

Payload phá hủy nghiêm trọng hơn:

```
keyword='); DROP TABLE users; --
```

---

## 3. Phiên bản an toàn — Truy vấn tham số hóa

### ✅ Mã an toàn

```java
// ✅ AN TOÀN: Tất cả input người dùng đi qua tham số bind
@SuppressWarnings("unchecked")
public Page<User> searchUsers(String keyWord, int page, int size) {

    String searchParam =
            (keyWord == null || keyWord.trim().isEmpty())
                    ? "%"
                    : "%" + keyWord.trim() + "%";

    // Truy vấn COUNT — đã tham số hóa
    String countSql =
            "SELECT COUNT(*) " +
            "FROM users " +
            "WHERE deleted = false " +
            "AND (LOWER(username) LIKE LOWER(:keyword) " +
            "OR LOWER(email) LIKE LOWER(:keyword))";

    Number total = (Number) entityManager
            .createNativeQuery(countSql)
            .setParameter("keyword", searchParam)
            .getSingleResult();

    // Truy vấn FETCH — đã tham số hóa (bao gồm LIMIT/OFFSET)
    String fetchSql =
            "SELECT * FROM users " +
            "WHERE deleted = false " +
            "AND (LOWER(username) LIKE LOWER(:keyword) " +
            "OR LOWER(email) LIKE LOWER(:keyword)) " +
            "ORDER BY username ASC " +
            "LIMIT :limit OFFSET :offset";

    List<User> content = entityManager
            .createNativeQuery(fetchSql, User.class)
            .setParameter("keyword", searchParam)
            .setParameter("limit", size)
            .setParameter("offset", page * size)
            .getResultList();

    return new PageImpl<>(
            content,
            PageRequest.of(page, size),
            total.longValue());
}
```

**Tại sao nó an toàn:**
- `keyWord` **không bao giờ** chạm vào chuỗi SQL trực tiếp.
- `:keyword`, `:limit`, `:offset` đều là tham số bind được xử lý bởi JDBC driver.
- Ngay cả khi `keyWord = "' OR 1=1) --"`, database coi nó là **chuỗi tìm kiếm thông thường**, không phải cú pháp SQL.

---

## 4. So sánh song song

```carousel
### 🔴 Pattern không an toàn
```java
// Input người dùng được NỐI CHUỖI vào query
String sql = "SELECT * FROM users "
    + "WHERE username = '" + username + "'";

entityManager.createNativeQuery(sql, User.class)
             .getSingleResult();
```

Database nhận được:
```sql
-- Nếu username = "admin' OR '1'='1"
SELECT * FROM users WHERE username = 'admin' OR '1'='1'
```
`OR '1'='1'` được phân tích như SQL → **trả về tất cả bản ghi**.
<!-- slide -->
### ✅ Pattern an toàn
```java
// Input người dùng được BIND như tham số
String sql = "SELECT * FROM users "
    + "WHERE username = :username";

entityManager.createNativeQuery(sql, User.class)
             .setParameter("username", username)
             .getSingleResult();
```

Database nhận được:
```sql
-- Giá trị được gửi riêng biệt, không bao giờ bị phân tích như SQL
SELECT * FROM users WHERE username = ?
-- Tham số 1: "admin' OR '1'='1"  (được coi là văn bản thuần túy)
```
Toàn bộ chuỗi được so sánh theo nghĩa đen → **không bị injection**.
````

---

## 5. Các ví dụ an toàn đã có sẵn trong mã nguồn

Mã nguồn của bạn đã có **nhiều ví dụ đúng** để tham khảo:

| Phương thức | Dòng | Kỹ thuật | Trạng thái |
|---|---|---|---|
| `getUserByUsernameNative` | [D41-49](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L41-L49) | Tham số đặt tên `:username` | ✅ An toàn |
| `updateProfile` | [D60-67](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L60-L67) | Tất cả field đều tham số hóa | ✅ An toàn |
| `changePassword` | [D89-93](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L89-L93) | `:password`, `:id` | ✅ An toàn |
| `createUser` | [D258-270](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L258-L270) | Tất cả 8 field đều tham số hóa | ✅ An toàn |
| `deleteUser` | [D330-333](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L330-L333) | Tham số `:id` | ✅ An toàn |
| **`searchUsers` (fetch)** | [D196-202](file:///e:/CODECNTT/DU_AN/VGA_OWASP/vga-store-owasp/backend/vgashop/src/main/java/com/example/vgashop/service/UserService.java#L196-L202) | **Nối chuỗi trực tiếp** | ❌ **Có lỗ hổng** |

> [!NOTE]
> Phương thức `getAllUsers` (D163-166) sử dụng `safeSort` / `safeDir` cho mệnh đề `ORDER BY`. Đây là pattern chấp nhận được vì **tên cột và hướng sắp xếp không thể tham số hóa** — việc xác thực theo allowlist (`matches("^[a-zA-Z0-9_]+$")`) cung cấp bảo vệ đầy đủ.

---

## 6. Checklist Code Review

Khi review các native query JPA/Hibernate, đánh dấu cảnh báo cho bất kỳ dòng nào khớp với các pattern sau:

| 🚩 Dấu hiệu cảnh báo | Ví dụ |
|---|---|
| `"..." + biến + "..."` bên trong `createNativeQuery()` | `"WHERE name = '" + name + "'"` |
| `String.format()` với input người dùng | `String.format("WHERE id = %s", id)` |
| Lệnh `.setParameter()` không khớp với placeholder trong SQL | Tham số chết (như bug ở trên) |
| `StringBuilder.append()` với giá trị từ người dùng | `sb.append(request.getFilter())` |
| JPQL có nối chuỗi | `"SELECT u FROM User u WHERE u.name = '" + name + "'"` |

### ✅ Các pattern an toàn cần tìm:

```java
// Tham số đặt tên (ưu tiên dùng)
.createNativeQuery("... WHERE col = :param", Entity.class)
.setParameter("param", value)

// Tham số theo vị trí
.createNativeQuery("... WHERE col = ?1", Entity.class)
.setParameter(1, value)

// Spring Data JPA @Query
@Query("SELECT u FROM User u WHERE u.username = :username")
User findByUsername(@Param("username") String username);

// Criteria API (tham số hóa sẵn)
cb.equal(root.get("username"), username);
```

---

## 7. Kết luận quan trọng

> [!IMPORTANT]
> **Nguyên tắc vàng**: Input của người dùng **không bao giờ** được xuất hiện bên trong chuỗi SQL. Nó phải luôn đi qua `.setParameter()` và được gắn với một `:placeholder` hoặc `?n` tương ứng trong truy vấn.

Ngoại lệ duy nhất là cho các **thành phần cấu trúc SQL** (tên cột, hướng sắp xếp, tên bảng) — những thứ này không thể tham số hóa — chúng phải được xác thực theo một **danh sách cho phép (allowlist)** các giá trị hợp lệ, không bao giờ truyền trực tiếp.

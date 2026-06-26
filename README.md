# VGA Store OWASP Lab

VGA Store OWASP Lab là ứng dụng thương mại điện tử bán VGA/card đồ họa dùng cho học tập và báo cáo bảo mật OWASP. Dự án mô phỏng đầy đủ luồng người dùng, quản trị và API backend, đồng thời có các điểm lab để kiểm thử SQL Injection trong môi trường cục bộ.

> Chỉ sử dụng dự án này cho mục đích học tập, demo và kiểm thử trên môi trường được phép.

## Thành Phần Chính

| Thành phần | Công nghệ | Cổng Docker |
|---|---|---|
| User Frontend | React 19, Vite, Tailwind CSS | `4175` |
| Admin Frontend | React 19, Vite, Redux Toolkit, Recharts | `4176` |
| Backend API | Java 17, Spring Boot 3.3, Spring Security, JPA | `8082` |
| Database | PostgreSQL 17 | `4434` |

URL sau khi chạy bằng Docker:

- User: `http://localhost:4175`
- Admin: `http://localhost:4176`
- API: `http://localhost:8082/api`
- PostgreSQL: `localhost:4434`

## Chức Năng

### Người dùng

- Xem danh sách sản phẩm VGA, tìm kiếm, lọc theo hãng, giá và danh mục.
- Xem chi tiết sản phẩm, đánh giá, sản phẩm liên quan.
- Đăng ký, đăng nhập, đăng nhập Google.
- Quản lý giỏ hàng, đặt hàng, theo dõi đơn hàng.
- Thanh toán COD, VNPay sandbox và MoMo sandbox.
- Xem bài viết/blog và thông tin chính sách dịch vụ.
- Chat AI hỗ trợ tư vấn sản phẩm nếu cấu hình `VITE_GEMINI_API_KEY`.

### Quản trị

- Dashboard thống kê doanh thu, đơn hàng, sản phẩm và biểu đồ.
- Quản lý sản phẩm, tồn kho, ảnh sản phẩm.
- Quản lý danh mục, thương hiệu, bài viết.
- Quản lý người dùng, vai trò, trạng thái tài khoản.
- Quản lý đơn hàng, thanh toán, đánh giá và cài đặt hệ thống.

### Lab OWASP

Dự án có chủ đích giữ một số luồng phục vụ kiểm thử SQL Injection, ví dụ:

- Endpoint tìm kiếm sản phẩm lab: `GET /api/products/search-vulnerable`.
- Các nhánh/phiên bản có thể được dùng để so sánh giữa bản có lỗi và bản đã khắc phục.
- Một số phần code có comment hoặc endpoint riêng cho mục đích đào tạo.

Không dùng các endpoint lab như một mẫu triển khai production.

## Cấu Trúc Thư Mục

```text
vga-store-owasp/
|-- backend/
|   `-- vgashop/              # Spring Boot backend
|-- database/
|   |-- create_tables.sql     # Schema PostgreSQL
|   |-- seed_vga.sql          # Dữ liệu mẫu chính
|   |-- mock_data.sql         # Dữ liệu mẫu phụ
|   `-- Dockerfile            # Image database có seed dữ liệu
|-- fontend/
|   |-- user/                 # Giao diện khách hàng
|   `-- admin/                # Giao diện quản trị
|-- docs/                     # Tài liệu/báo cáo bổ sung nếu có
|-- docker-compose.yml
`-- README.md
```

Lưu ý: thư mục frontend trong repo hiện được đặt tên là `fontend`.

## Chạy Nhanh Bằng Docker

Yêu cầu:

- Docker Desktop
- Git

Chạy toàn bộ hệ thống:

```powershell
docker compose up -d --build
```

Kiểm tra container:

```powershell
docker compose ps
```

Dừng hệ thống:

```powershell
docker compose down
```

Nếu muốn xóa cả dữ liệu PostgreSQL và chạy lại từ seed:

```powershell
docker compose down -v
docker compose up -d --build
```

## Chạy Local Khi Phát Triển

### Backend

Yêu cầu:

- Java 17
- Maven hoặc Maven Wrapper có sẵn trong `backend/vgashop`
- PostgreSQL đang chạy và có database `vga_store`

Chạy backend:

```powershell
cd backend/vgashop
.\mvnw.cmd spring-boot:run
```

Mặc định file `application.properties` dùng:

- JDBC URL: `jdbc:postgresql://localhost:5432/vga_store`
- Username: `postgres`
- Password: `1234567`
- Backend local: `http://localhost:8080`

Khi chạy bằng Docker Compose, các biến môi trường trong `docker-compose.yml` sẽ ghi đè cấu hình database và backend được map ra `http://localhost:8082`.

### User Frontend

```powershell
cd fontend/user
npm install
npm run dev
```

Vite mặc định chạy ở `http://localhost:5173`.

Nếu dùng AI chat, tạo file `.env` từ `.env.example`:

```powershell
copy .env.example .env
```

Sau đó điền:

```env
VITE_GEMINI_API_KEY=your_gemini_api_key_here
```

### Admin Frontend

```powershell
cd fontend/admin
npm install
npm run dev
```

Admin Vite đang cấu hình chạy ở `http://localhost:5174`.

## Tài Khoản Demo

Dữ liệu mẫu nằm trong `database/seed_vga.sql`. Một số tài khoản có sẵn:

| Vai trò | Username | Ghi chú |
|---|---|---|
| Admin | `admin` | Tài khoản seed chính |
| Admin | `hai123` | Tài khoản admin mẫu trong seed |

Mật khẩu phụ thuộc dữ liệu seed/hash hiện tại. Nếu không đăng nhập được, có thể chạy lại database từ seed hoặc tạo admin mới qua API/chức năng quản trị tùy phiên bản đang dùng. Khi backend tự seed database rỗng, tài khoản mặc định trong `DataSeeder` là:

```text
Username: admin
Password: 123
```

## API Chính

Một số nhóm API chính:

| Nhóm | Đường dẫn |
|---|---|
| Xác thực | `/api/auth/**` |
| Sản phẩm | `/api/products/**` |
| Danh mục | `/api/categories/**` |
| Thương hiệu | `/api/brands/**` |
| Giỏ hàng | `/api/cart/**` |
| Đơn hàng | `/api/orders/**` |
| Thanh toán | `/api/payments/**` |
| Blog | `/api/blogs/**` |
| Quản trị | `/api/admin/**` |

Các API quản trị yêu cầu token có vai trò `ADMIN`.

## Ghi Chú Về Branch Và SQL Injection

Repo có thể có nhiều branch phục vụ báo cáo:

- `tanvinh`: bản lab/demo, có thể chứa các điểm SQL Injection có chủ đích.
- `tanvinh_fix_sqli`: bản dùng để đối chiếu sau khi khắc phục nếu branch tồn tại trong repo.
- `main`: nhánh chính của dự án, trạng thái phụ thuộc lần merge gần nhất.

Trước khi kết luận một branch đã an toàn, cần kiểm tra trực tiếp code và retest payload. README này mô tả dự án hiện tại ở góc độ lab, không cam kết toàn bộ endpoint đã đạt chuẩn production.

## Kiểm Thử Và Build

Backend:

```powershell
cd backend/vgashop
.\mvnw.cmd test
```

Build frontend user:

```powershell
cd fontend/user
npm run build
```

Build frontend admin:

```powershell
cd fontend/admin
npm run build
```

Lint frontend:

```powershell
npm run lint
```

Chạy trong từng thư mục frontend tương ứng.

## Xử Lý Lỗi Thường Gặp

### Docker không tải được image

Nếu gặp lỗi dạng:

```text
lookup registry-1.docker.io: no such host
```

hãy kiểm tra Internet, DNS, VPN/proxy hoặc khởi động lại Docker Desktop.

### Frontend không gọi được API

Kiểm tra backend đã chạy ở đúng cổng:

- Docker: `http://localhost:8082/api`
- Local Spring Boot: `http://localhost:8080/api`

Frontend hiện đang cấu hình gọi `http://localhost:8082/api`, phù hợp với Docker Compose. Nếu chạy backend local ở cổng `8080`, cần chỉnh base URL trong:

- `fontend/user/src/api/axiosClient.js`
- `fontend/admin/src/api/axiosClient.js`

### Lệnh `vite` không chạy

Cài dependency trước:

```powershell
npm install
npm run dev
```

### Muốn seed lại dữ liệu

Dữ liệu PostgreSQL được lưu trong Docker volume `pgdata`. Muốn tạo lại database từ đầu:

```powershell
docker compose down -v
docker compose up -d --build
```

## Lưu Ý Bảo Mật

- Không dùng cấu hình, khóa sandbox hoặc tài khoản demo trong môi trường production.
- Không expose database hoặc API lab ra Internet.
- Các payload và endpoint SQLi chỉ phục vụ học tập trong môi trường cục bộ.
- Khi phát triển bản production, cần loại bỏ endpoint lab, dùng parameter binding nhất quán, kiểm soát phân quyền, ẩn lỗi nội bộ và kiểm thử lại toàn bộ luồng xác thực.

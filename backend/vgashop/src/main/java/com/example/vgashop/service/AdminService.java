package com.example.vgashop.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Date;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

import com.example.vgashop.dto.*;
import com.example.vgashop.entity.*;
import com.example.vgashop.exception.ResourceNotFoundException;

@Service
public class AdminService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminService.class);

    @Autowired
    private EntityManager entityManager;

    public AdminService() {}

    // Lấy user bằng Native SQL
    private User getUserByIdNative(Long id) {
        try {
            return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE id = :id AND deleted = false", User.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy user với ID: " + id);
        }
    }

    private Order getOrderByIdNative(Long id) {
        try {
            return (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE id = :id AND deleted = false", Order.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy đơn hàng");
        }
    }

    private Product getProductByIdNative(Long id) {
        try {
            return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE id = :id AND deleted = false", Product.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm");
        }
    }

    private Category getCategoryByIdNative(Long id) {
        try {
            return (Category) entityManager.createNativeQuery("SELECT * FROM categories WHERE id = :id AND deleted = false", Category.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy danh mục");
        }
    }

    private Brand getBrandByIdNative(Long id) {
        try {
            return (Brand) entityManager.createNativeQuery("SELECT * FROM brands WHERE id = :id AND deleted = false", Brand.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy thương hiệu");
        }
    }

    private Blog getBlogByIdNative(Long id) {
        try {
            return (Blog) entityManager.createNativeQuery("SELECT * FROM blogs WHERE id = :id AND deleted = false", Blog.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy bài viết");
        }
    }

    // Dashboard
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        log.info("Admin đang lấy dữ liệu dashboard (Native SQL)");

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        List<Order> allOrders = entityManager.createNativeQuery("SELECT * FROM orders WHERE deleted = false", Order.class).getResultList();

        long totalOrders = 0;
        long todayOrders = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal todayRevenue = BigDecimal.ZERO;

        for (Order o : allOrders) {
            if (o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.CANCEL_REQUESTED) {
                totalOrders++;
                BigDecimal amt = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
                totalRevenue = totalRevenue.add(amt);

                if (o.getCreatedAt() != null && !o.getCreatedAt().isBefore(startOfToday)) {
                    todayOrders++;
                    todayRevenue = todayRevenue.add(amt);
                }
            }
        }

        Number totalUsers = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE deleted = false").getSingleResult();
        Number totalProducts = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM products WHERE deleted = false").getSingleResult();

        Long lowStockProducts = 0L;
        try {
            Number countLowStock = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM products WHERE deleted = false AND stock <= :stockLimit")
                    .setParameter("stockLimit", 10)
                    .getSingleResult();
            lowStockProducts = countLowStock.longValue();
        } catch (Exception e) {
            log.warn("Lỗi đếm hàng tồn kho: {}", e.getMessage());
        }

        return new AdminDashboardResponse(
                totalUsers != null ? totalUsers.longValue() : 0L,
                totalOrders,
                todayOrders,
                totalRevenue,
                todayRevenue,
                totalProducts != null ? totalProducts.longValue() : 0L,
                lowStockProducts,
                LocalDateTime.now());
    }

    // User
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<UserAdminResponse> getAllUsers(int page, int size, String sortBy, String direction, String search, String roleStr) {
        log.info("Admin lấy danh sách user - search: {}, role: {}", search, roleStr);

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM users WHERE deleted = false");
        StringBuilder fetchSql = new StringBuilder("SELECT * FROM users WHERE deleted = false");
        
        if (search != null && !search.trim().isEmpty()) {
            countSql.append(" AND (LOWER(username) LIKE LOWER(:search) OR LOWER(email) LIKE LOWER(:search))");
            fetchSql.append(" AND (LOWER(username) LIKE LOWER(:search) OR LOWER(email) LIKE LOWER(:search))");
        }
        
        if (roleStr != null && !roleStr.trim().isEmpty()) {
            countSql.append(" AND role = :role");
            fetchSql.append(" AND role = :role");
        }
        
        fetchSql.append(" ORDER BY ").append(safeSort).append(" ").append(safeDir).append(" LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString());
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), User.class);

        if (search != null && !search.trim().isEmpty()) {
            countQuery.setParameter("search", "%" + search.trim() + "%");
            fetchQuery.setParameter("search", "%" + search.trim() + "%");
        }
        
        if (roleStr != null && !roleStr.trim().isEmpty()) {
            try {
                Role roleObj = Role.valueOf(roleStr.toUpperCase());
                countQuery.setParameter("role", roleObj.name());
                fetchQuery.setParameter("role", roleObj.name());
            } catch (Exception e) {
                // Ignore invalid role
            }
        }
        
        fetchQuery.setParameter("limit", size);
        fetchQuery.setParameter("offset", page * size);

        Number total = (Number) countQuery.getSingleResult();
        List<User> users = fetchQuery.getResultList();

        List<UserAdminResponse> content = users.stream().map(user -> new UserAdminResponse(
                user.getId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.getPhone(), user.getRole(), user.getStatus(), user.isDeleted(),
                user.getCreatedAt(), user.getUpdatedAt())).collect(Collectors.toList());

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @Transactional
    public UserAdminResponse createUser(com.example.vgashop.dto.UserDTO dto) {
        log.info("Admin tạo user mới: {}", dto.getUsername());

        Number countUsername = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE username = :username")
                .setParameter("username", dto.getUsername()).getSingleResult();
        if (countUsername.intValue() > 0) throw new com.example.vgashop.exception.DuplicateResourceException("Username đã tồn tại!");
        
        Number countEmail = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE email = :email")
                .setParameter("email", dto.getEmail()).getSingleResult();
        if (countEmail.intValue() > 0) throw new com.example.vgashop.exception.DuplicateResourceException("Email đã tồn tại!");

        String roleStr = dto.getRole() != null ? dto.getRole().toUpperCase() : "USER";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String encPass = encoder.encode(dto.getPassword());

        String insertSql = "INSERT INTO users (username, email, password, full_name, role, status, deleted, created_at, updated_at) " +
                           "VALUES (:username, :email, :password, :fullName, :role, true, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
                           
        entityManager.createNativeQuery(insertSql)
                .setParameter("username", dto.getUsername())
                .setParameter("email", dto.getEmail())
                .setParameter("password", encPass)
                .setParameter("fullName", dto.getFullName())
                .setParameter("role", roleStr)
                .executeUpdate();

        User saved = (User) entityManager.createNativeQuery("SELECT * FROM users WHERE username = :username", User.class)
                .setParameter("username", dto.getUsername()).getSingleResult();
        
        return new UserAdminResponse(saved.getId(), saved.getUsername(), saved.getEmail(), 
                                     saved.getFullName(), saved.getPhone(), saved.getRole(), 
                                     saved.getStatus(), saved.isDeleted(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Transactional
    public void changeUserRole(Long userId, String newRole) {
        log.info("Admin thay đổi role userId={} thành {}", userId, newRole);
        User user = getUserByIdNative(userId); // checks existence
        try {
            Role.valueOf(newRole.toUpperCase());
            entityManager.createNativeQuery("UPDATE users SET role = :role, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .setParameter("role", newRole.toUpperCase())
                    .setParameter("id", userId)
                    .executeUpdate();
            log.info("Đã thay đổi role userId={} thành {}", userId, newRole);
        } catch (IllegalArgumentException e) {
            log.error("Role không hợp lệ: {}", newRole);
            throw new IllegalArgumentException("Role không hợp lệ. Các Role hợp lệ là: ADMIN, USER, STAFF");
        }
    }

    @Transactional
    public void toggleUserStatus(Long userId, String currentAdminUsername) {
        log.info("Admin '{}' toggle status userId={}", currentAdminUsername, userId);
        User user = getUserByIdNative(userId);

        if (user.getUsername().equals(currentAdminUsername)) throw new IllegalStateException("Không thể khóa tài khoản đang đăng nhập!");
        if (user.getRole() == Role.ADMIN) throw new IllegalStateException("Không thể khóa tài khoản Quản trị viên!");

        boolean newStatus = !user.getStatus();
        entityManager.createNativeQuery("UPDATE users SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("status", newStatus)
                .setParameter("id", userId)
                .executeUpdate();
    }

    @Transactional
    public void softDeleteUser(Long userId, String currentAdminUsername) {
        log.info("Admin '{}' xóa mềm userId={}", currentAdminUsername, userId);
        User user = getUserByIdNative(userId);

        if (user.getUsername().equals(currentAdminUsername)) throw new IllegalStateException("Không thể xóa tài khoản đang đăng nhập!");
        if (user.getRole() == Role.ADMIN) throw new IllegalStateException("Không thể xóa tài khoản Quản trị viên!");

        entityManager.createNativeQuery("UPDATE users SET deleted = true, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> getAllOrders(int page, int size, String sortBy, String direction, String status) {
        log.info("Admin lấy danh sách tất cả đơn hàng - page: {}, status: {}", page, status);

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM orders WHERE deleted = false");
        StringBuilder fetchSql = new StringBuilder("SELECT * FROM orders WHERE deleted = false");
        
        if (status != null && !status.isBlank()) {
            try {
                OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
                countSql.append(" AND status = :status");
                fetchSql.append(" AND status = :status");
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        
        fetchSql.append(" ORDER BY ").append(safeSort).append(" ").append(safeDir).append(" LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString());
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), Order.class);

        if (status != null && !status.isBlank()) {
            try {
                OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
                countQuery.setParameter("status", orderStatus.name());
                fetchQuery.setParameter("status", orderStatus.name());
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        
        fetchQuery.setParameter("limit", size);
        fetchQuery.setParameter("offset", page * size);

        Number total = (Number) countQuery.getSingleResult();
        List<Order> orders = fetchQuery.getResultList();

        List<OrderSummaryResponse> content = orders.stream().map(this::convertToOrderSummary).collect(Collectors.toList());

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatusUpdateRequest request) {
        log.info("Admin cập nhật trạng thái đơn hàng {} thành {}", orderId, request.getStatus());
        Order order = getOrderByIdNative(orderId);

        String updateSql = "UPDATE orders SET status = :status";
        
        switch (request.getStatus()) {
            case CONFIRMED -> updateSql += ", confirmed_at = CURRENT_TIMESTAMP";
            case SHIPPING -> updateSql += ", shipped_at = CURRENT_TIMESTAMP";
            case DELIVERED -> updateSql += ", delivered_at = CURRENT_TIMESTAMP";
            default -> {}
        }
        updateSql += " WHERE id = :id";
        
        entityManager.createNativeQuery(updateSql)
                .setParameter("status", request.getStatus().name())
                .setParameter("id", orderId)
                .executeUpdate();

        entityManager.refresh(order);
        return convertToOrderResponse(order);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<ProductAdminResponse> getAllProductForAdmin(int page, int size, String search) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM products WHERE deleted = false");
        StringBuilder fetchSql = new StringBuilder("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE deleted = false");
        
        if (search != null && !search.trim().isEmpty()) {
            countSql.append(" AND LOWER(name) LIKE LOWER(:search)");
            fetchSql.append(" AND LOWER(name) LIKE LOWER(:search)");
        }
        
        fetchSql.append(" ORDER BY display_order ASC, id DESC LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString());
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), Product.class);

        if (search != null && !search.trim().isEmpty()) {
            countQuery.setParameter("search", "%" + search.trim() + "%");
            fetchQuery.setParameter("search", "%" + search.trim() + "%");
        }
        
        fetchQuery.setParameter("limit", size);
        fetchQuery.setParameter("offset", page * size);

        Number total = (Number) countQuery.getSingleResult();
        List<Product> products = fetchQuery.getResultList();

        List<ProductAdminResponse> content = products.stream().map(p -> new ProductAdminResponse(
                p.getId(), p.getName(), p.getPrice(), p.getStock(),
                p.getBrand() != null ? p.getBrand().getName() : "N/A",
                p.getCategory() != null ? p.getCategory().getName() : "N/A",
                p.getStatus(), p.getImgUrl() != null ? p.getImgUrl() : "")).collect(Collectors.toList());

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(Sort.by(Sort.Direction.DESC, "id")));
        return new PageImpl<>(content, pageable, total.longValue());
    }

    @Transactional
    public void updateProductStock(Long productId, Integer stock) {
        log.info("Admin cập nhật stock sản phẩm {} thành {}", productId, stock);
        getProductByIdNative(productId);

        if (stock < 0) throw new IllegalArgumentException("Stock không được âm");

        entityManager.createNativeQuery("UPDATE products SET stock = :stock WHERE id = :id")
                .setParameter("stock", stock)
                .setParameter("id", productId)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Category> getAllCategoriesForAdmin(int page, int size) {
        String countSql = "SELECT COUNT(*) FROM categories WHERE deleted = false";
        Number total = (Number) entityManager.createNativeQuery(countSql).getSingleResult();
        
        String fetchSql = "SELECT * FROM categories WHERE deleted = false ORDER BY display_order ASC, id ASC LIMIT :limit OFFSET :offset";
        List<Category> content = entityManager.createNativeQuery(fetchSql, Category.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(Sort.by(Sort.Direction.ASC, "id")));
        return new PageImpl<>(content, pageable, total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Brand> getAllBrandsForAdmin(int page, int size) {
        String countSql = "SELECT COUNT(*) FROM brands WHERE deleted = false";
        Number total = (Number) entityManager.createNativeQuery(countSql).getSingleResult();
        
        String fetchSql = "SELECT * FROM brands WHERE deleted = false ORDER BY display_order ASC, id ASC LIMIT :limit OFFSET :offset";
        List<Brand> content = entityManager.createNativeQuery(fetchSql, Brand.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(Sort.by(Sort.Direction.ASC, "id")));
        return new PageImpl<>(content, pageable, total.longValue());
    }

    // CONVERT METHODS
    private OrderSummaryResponse convertToOrderSummary(Order order) {
        int totalItems = order.getItems().stream()
                .filter(item -> !item.isDeleted())
                .mapToInt(OrderItem::getQuantity)
                .sum();

        return new OrderSummaryResponse(
                order.getId(), order.getOrderCode(), order.getFullName(), order.getPhone(),
                order.getTotalAmount(), order.getStatus(), order.getPaymentStatus(),
                order.getCreatedAt(), totalItems);
    }

    private OrderResponse convertToOrderResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .filter(item -> !item.isDeleted())
                .map(item -> new OrderItemResponse(
                        item.getProduct().getId(), item.getProduct().getName(), item.getProduct().getImgUrl(),
                        item.getPrice(), item.getQuantity(), item.getSubtotal()))
                .collect(Collectors.toList());

        String paymentMethodStr = "Chưa rõ";
        try {
            Payment payment = (Payment) entityManager.createNativeQuery("SELECT * FROM payments WHERE order_id = :orderId AND deleted = false ORDER BY id DESC LIMIT 1", Payment.class)
                    .setParameter("orderId", order.getId()).getSingleResult();
            if (payment != null && payment.getPaymentMethod() != null) {
                paymentMethodStr = payment.getPaymentMethod().name();
            }
        } catch (NoResultException e) {
            // Ignore
        }

        return new OrderResponse(
                order.getId(), order.getOrderCode(), order.getTotalAmount(),
                order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO,
                order.getStatus(), order.getPaymentStatus(), order.getShippingAddress(),
                order.getPhone(), order.getNote() != null ? order.getNote() : "",
                order.getCreatedAt(), order.getConfirmedAt(), order.getShippedAt(), order.getDeliveredAt(),
                itemResponses,
                order.getFullName() != null && !order.getFullName().trim().isEmpty() ? order.getFullName() : (order.getUser() != null ? order.getUser().getUsername() : "Khách ẩn danh"),
                order.getUser() != null ? order.getUser().getEmail() : "Không có",
                paymentMethodStr
        );
    }

    @Transactional
    public void softDeleteProduct(Long productId) {
        getProductByIdNative(productId);
        entityManager.createNativeQuery("UPDATE products SET deleted = true WHERE id = :id")
                .setParameter("id", productId).executeUpdate();
    }

    @Transactional
    public Category addCategories(Category category) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM categories WHERE LOWER(name) = LOWER(:name)")
                .setParameter("name", category.getName()).getSingleResult();
        if (count.intValue() > 0) throw new IllegalArgumentException("Tên danh mục đã tồn tại");

        String insertSql = "INSERT INTO categories (name, description, active, deleted) VALUES (:name, :desc, :active, false)";
        entityManager.createNativeQuery(insertSql)
                .setParameter("name", category.getName())
                .setParameter("desc", category.getDescription())
                .setParameter("active", category.getActive() != null ? category.getActive() : true)
                .executeUpdate();
                
        return (Category) entityManager.createNativeQuery("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name)", Category.class)
                .setParameter("name", category.getName()).getSingleResult();
    }

    @Transactional
    public Category updateCategory(Long id, Category categoryReq) {
        getCategoryByIdNative(id);
        entityManager.createNativeQuery("UPDATE categories SET name = :name, description = :desc, active = :active WHERE id = :id")
                .setParameter("name", categoryReq.getName())
                .setParameter("desc", categoryReq.getDescription())
                .setParameter("active", categoryReq.getActive() != null ? categoryReq.getActive() : true)
                .setParameter("id", id).executeUpdate();
        return getCategoryByIdNative(id);
    }

    @Transactional
    public void deleteCategory(Long id) {
        getCategoryByIdNative(id);
        entityManager.createNativeQuery("UPDATE categories SET deleted = true WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    @Transactional
    public Brand addBrand(Brand brand) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM brands WHERE LOWER(name) = LOWER(:name)")
                .setParameter("name", brand.getName()).getSingleResult();
        if (count.intValue() > 0) throw new IllegalArgumentException("Tên thương hiệu đã tồn tại");

        String insertSql = "INSERT INTO brands (name, description, status, deleted) VALUES (:name, :desc, :status, false)";
        entityManager.createNativeQuery(insertSql)
                .setParameter("name", brand.getName())
                .setParameter("desc", brand.getDescription())
                .setParameter("status", brand.getStatus() != null ? brand.getStatus() : true)
                .executeUpdate();

        return (Brand) entityManager.createNativeQuery("SELECT * FROM brands WHERE LOWER(name) = LOWER(:name)", Brand.class)
                .setParameter("name", brand.getName()).getSingleResult();
    }

    @Transactional
    public Brand updateBrand(Long id, Brand brandReq) {
        getBrandByIdNative(id);
        entityManager.createNativeQuery("UPDATE brands SET name = :name, description = :desc, status = :status WHERE id = :id")
                .setParameter("name", brandReq.getName())
                .setParameter("desc", brandReq.getDescription())
                .setParameter("status", brandReq.getStatus() != null ? brandReq.getStatus() : true)
                .setParameter("id", id).executeUpdate();
        return getBrandByIdNative(id);
    }

    @Transactional
    public void deleteBrand(Long id) {
        getBrandByIdNative(id);
        entityManager.createNativeQuery("UPDATE brands SET deleted = true WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    @Transactional
    public Blog createBlog(BlogDTO dto, MultipartFile image) {
        String thumbnail = "";
        if (image != null && !image.isEmpty()) thumbnail = uploadBlogImage(image);

        String insertSql = "INSERT INTO blogs (title, category, excerpt, author, content, published_date, featured, tags, thumbnail, views, deleted) " +
                           "VALUES (:title, :category, :excerpt, :author, :content, CURRENT_TIMESTAMP, :featured, :tags, :thumbnail, 0, false)";
        entityManager.createNativeQuery(insertSql)
                .setParameter("title", dto.getTitle())
                .setParameter("category", dto.getCategory())
                .setParameter("excerpt", dto.getExcerpt())
                .setParameter("author", dto.getAuthor() != null ? dto.getAuthor() : "Admin")
                .setParameter("content", dto.getContent())
                .setParameter("featured", dto.getFeatured() != null ? dto.getFeatured() : false)
                .setParameter("tags", dto.getTags())
                .setParameter("thumbnail", thumbnail).executeUpdate();

        return (Blog) entityManager.createNativeQuery("SELECT * FROM blogs WHERE title = :title ORDER BY id DESC LIMIT 1", Blog.class)
                .setParameter("title", dto.getTitle()).getSingleResult();
    }

    @Transactional
    public Blog updateBlog(Long id, BlogDTO dto, MultipartFile image) {
        Blog blog = getBlogByIdNative(id);

        String thumbnail = blog.getThumbnail();
        if (image != null && !image.isEmpty()) thumbnail = uploadBlogImage(image);

        entityManager.createNativeQuery("UPDATE blogs SET title = :title, category = :category, excerpt = :excerpt, author = :author, content = :content, featured = :featured, tags = :tags, thumbnail = :thumbnail WHERE id = :id")
                .setParameter("title", dto.getTitle())
                .setParameter("category", dto.getCategory())
                .setParameter("excerpt", dto.getExcerpt() != null ? dto.getExcerpt() : blog.getExcerpt())
                .setParameter("author", dto.getAuthor() != null && !dto.getAuthor().isBlank() ? dto.getAuthor() : blog.getAuthor())
                .setParameter("content", dto.getContent() != null ? dto.getContent() : blog.getContent())
                .setParameter("featured", dto.getFeatured() != null ? dto.getFeatured() : blog.getFeatured())
                .setParameter("tags", dto.getTags() != null ? dto.getTags() : blog.getTags())
                .setParameter("thumbnail", thumbnail)
                .setParameter("id", id).executeUpdate();

        return getBlogByIdNative(id);
    }

    @Transactional
    public void deleteBlog(Long id) {
        getBlogByIdNative(id);
        entityManager.createNativeQuery("UPDATE blogs SET deleted = true WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    private String uploadBlogImage(MultipartFile file) {
        try {
            Path uploadPath = Paths.get("uploads", "blogs").toAbsolutePath();
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            file.transferTo(uploadPath.resolve(fileName).toFile());
            return "/uploads/blogs/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Không thể upload ảnh Blog: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetailsForAdmin(Long orderId) {
        return convertToOrderResponse(getOrderByIdNative(orderId));
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getDashboardCharts(String period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDate;
        java.util.Map<String, java.util.Map<String, Object>> timeStats = new java.util.LinkedHashMap<>();
        java.time.format.DateTimeFormatter dayFormatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM");

        if ("today".equals(period)) {
            startDate = now.toLocalDate().atStartOfDay();
            for (int i = 0; i <= 23; i++) {
                String label = String.format("%02d:00", i);
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("name", label); data.put("revenue", BigDecimal.ZERO); data.put("delivered", 0); data.put("cancelled", 0);
                timeStats.put(label, data);
            }
        } else if ("7days".equals(period)) {
            startDate = now.minusDays(6).toLocalDate().atStartOfDay();
            for (int i = 6; i >= 0; i--) {
                String label = now.minusDays(i).format(dayFormatter);
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("name", label); data.put("revenue", BigDecimal.ZERO); data.put("delivered", 0); data.put("cancelled", 0);
                timeStats.put(label, data);
            }
        } else if ("1month".equals(period)) {
            startDate = now.minusDays(29).toLocalDate().atStartOfDay();
            for (int i = 29; i >= 0; i--) {
                String label = now.minusDays(i).format(dayFormatter);
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("name", label); data.put("revenue", BigDecimal.ZERO); data.put("delivered", 0); data.put("cancelled", 0);
                timeStats.put(label, data);
            }
        } else if ("1year".equals(period)) {
            startDate = now.minusMonths(11).withDayOfMonth(1).toLocalDate().atStartOfDay();
            for (int i = 11; i >= 0; i--) {
                LocalDateTime m = now.minusMonths(i);
                String label = "T" + m.getMonthValue() + "/" + (m.getYear() % 100);
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("name", label); data.put("revenue", BigDecimal.ZERO); data.put("delivered", 0); data.put("cancelled", 0);
                timeStats.put(label, data);
            }
        } else {
            startDate = now.minusMonths(5).withDayOfMonth(1).toLocalDate().atStartOfDay();
            for (int i = 5; i >= 0; i--) {
                LocalDateTime m = now.minusMonths(i);
                String label = "T" + m.getMonthValue() + "/" + (m.getYear() % 100);
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("name", label); data.put("revenue", BigDecimal.ZERO); data.put("delivered", 0); data.put("cancelled", 0);
                timeStats.put(label, data);
            }
        }

        List<Order> orders = entityManager.createNativeQuery("SELECT * FROM orders WHERE deleted = false AND created_at >= :startDate", Order.class)
                .setParameter("startDate", startDate).getResultList();

        java.util.Map<String, Integer> brandSales = new java.util.HashMap<>();

        for (Order order : orders) {
            String label;
            if ("today".equals(period)) {
                label = String.format("%02d:00", order.getCreatedAt().getHour());
            } else if ("7days".equals(period) || "1month".equals(period)) {
                label = order.getCreatedAt().format(dayFormatter);
            } else {
                label = "T" + order.getCreatedAt().getMonthValue() + "/" + (order.getCreatedAt().getYear() % 100);
            }

            if (timeStats.containsKey(label)) {
                java.util.Map<String, Object> data = timeStats.get(label);

                if (order.getStatus() == OrderStatus.DELIVERED || order.getPaymentStatus() == PaymentStatus.SUCCESS) {
                    BigDecimal currentRev = (BigDecimal) data.get("revenue");
                    data.put("revenue", currentRev.add(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO));
                }
                if (order.getStatus() == OrderStatus.DELIVERED) {
                    data.put("delivered", (Integer) data.get("delivered") + 1);
                } else if (order.getStatus() == OrderStatus.CANCELLED) {
                    data.put("cancelled", (Integer) data.get("cancelled") + 1);
                }
            }

            if (order.getStatus() != OrderStatus.CANCELLED && order.getStatus() != OrderStatus.CANCEL_REQUESTED) {
                if (order.getItems() != null) {
                    for (OrderItem item : order.getItems()) {
                        if (!item.isDeleted() && item.getProduct() != null && item.getProduct().getBrand() != null) {
                            String brandName = item.getProduct().getBrand().getName();
                            brandSales.put(brandName, brandSales.getOrDefault(brandName, 0) + item.getQuantity());
                        }
                    }
                }
            }
        }

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("chartData", new java.util.ArrayList<>(timeStats.values()));
        List<java.util.Map<String, Object>> brandDataList = brandSales.entrySet().stream()
                .map(e -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("name", e.getKey()); map.put("sold", e.getValue()); return map;
                }).collect(Collectors.toList());
        response.put("brandData", brandDataList);
        return response;
    }

    @Transactional
    public void pinToTop(String entityType, Long id) {
        int newPriority = (int) -(System.currentTimeMillis() / 1000);
        String table = "";
        switch (entityType.toLowerCase()) {
            case "product": table = "products"; break;
            case "blog": table = "blogs"; break;
            case "category": table = "categories"; break;
            case "brand": table = "brands"; break;
            default: throw new IllegalArgumentException("Loại thực thể không hợp lệ");
        }
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table + " WHERE id = :id").setParameter("id", id).getSingleResult();
        if (count.intValue() == 0) throw new ResourceNotFoundException("Không tìm thấy thực thể");

        entityManager.createNativeQuery("UPDATE " + table + " SET display_order = :order WHERE id = :id")
                .setParameter("order", newPriority)
                .setParameter("id", id).executeUpdate();
    }
}

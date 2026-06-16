package com.example.vgashop.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.dto.CreateOrderRequest;
import com.example.vgashop.dto.OrderItemResponse;
import com.example.vgashop.dto.OrderRequest;
import com.example.vgashop.dto.OrderResponse;
import com.example.vgashop.dto.OrderStatusUpdateRequest;
import com.example.vgashop.dto.OrderSummaryResponse;
import com.example.vgashop.entity.Cart;
import com.example.vgashop.entity.CartItem;
import com.example.vgashop.entity.Order;
import com.example.vgashop.entity.OrderItem;
import com.example.vgashop.entity.OrderStatus;
import com.example.vgashop.entity.Payment;
import com.example.vgashop.entity.PaymentStatus;
import com.example.vgashop.entity.Product;
import com.example.vgashop.entity.User;
import com.example.vgashop.exception.ResourceNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class OrderService {

    @Autowired
    private EntityManager entityManager;

    private final UserService userService;

    public OrderService(UserService userService) {
        this.userService = userService;
    }

    private User getUserByUsername(String username) {
        try {
            return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE username = :username AND deleted = false", User.class)
                    .setParameter("username", username).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("User not found: " + username);
        }
    }

    private Product getProductById(Long id) {
        try {
            return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE id = :id AND deleted = false", Product.class)
                    .setParameter("id", id).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }
    }

    private Cart getCartByUserId(Long userId) {
        try {
            return (Cart) entityManager.createNativeQuery("SELECT * FROM carts WHERE user_id = :userId AND deleted = false", Cart.class)
                    .setParameter("userId", userId).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Cart not found");
        }
    }

    @Transactional
    public Map<String, Object> placeOrder(OrderRequest req, String username) {
        User user = getUserByUsername(username);

        String orderCode = "VGA-" + LocalDateTime.now().toLocalDate() + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (req.getPhone() == null || req.getPhone().trim().length() < 10) {
            throw new IllegalArgumentException("Số điện thoại không hợp lệ (phone error)");
        }

        double total = 0;
        List<Product> productsToUpdate = new ArrayList<>();
        
        for (OrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = getProductById(itemReq.getProductId());
            if (product.getStock() == null || product.getStock() < itemReq.getQuantity()) {
                throw new IllegalArgumentException("Insufficient stock for '" + product.getName() + "'. Available: " + product.getStock());
            }
            total += product.getPrice().doubleValue() * itemReq.getQuantity();
            productsToUpdate.add(product);
        }

        entityManager.createNativeQuery("INSERT INTO orders (user_id, status, payment_status, full_name, phone, shipping_address, note, order_code, total_amount, discount_amount, deleted, created_at) " +
                                        "VALUES (:userId, 'PENDING', 'UNPAID', :fullName, :phone, :address, :note, :orderCode, :total, 0, false, CURRENT_TIMESTAMP)")
                .setParameter("userId", user.getId())
                .setParameter("fullName", req.getFullName())
                .setParameter("phone", req.getPhone())
                .setParameter("address", req.getAddress())
                .setParameter("note", req.getNote())
                .setParameter("orderCode", orderCode)
                .setParameter("total", BigDecimal.valueOf(total))
                .executeUpdate();

        Order order = (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE order_code = :orderCode ORDER BY id DESC LIMIT 1", Order.class)
                .setParameter("orderCode", orderCode).getSingleResult();

        int i = 0;
        for (OrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = productsToUpdate.get(i++);
            entityManager.createNativeQuery("INSERT INTO order_items (order_id, product_id, quantity, price, deleted) VALUES (:orderId, :productId, :quantity, :price, false)")
                    .setParameter("orderId", order.getId())
                    .setParameter("productId", product.getId())
                    .setParameter("quantity", itemReq.getQuantity())
                    .setParameter("price", product.getPrice())
                    .executeUpdate();
                    
            entityManager.createNativeQuery("UPDATE products SET stock = stock - :quantity WHERE id = :id")
                    .setParameter("quantity", itemReq.getQuantity())
                    .setParameter("id", product.getId()).executeUpdate();
        }

        entityManager.refresh(order);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderId", order.getId());
        resp.put("orderCode", order.getOrderCode());
        resp.put("status", order.getStatus().name());
        resp.put("totalPrice", order.getTotalAmount());
        resp.put("fullName", order.getFullName());
        resp.put("phone", order.getPhone());
        resp.put("address", order.getShippingAddress());
        return resp;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUserOrders(String username) {
        User user = getUserByUsername(username);

        List<Order> orders = entityManager.createNativeQuery("SELECT * FROM orders WHERE user_id = :userId ORDER BY id DESC", Order.class)
                .setParameter("userId", user.getId()).getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", order.getId());
            o.put("orderCode", order.getOrderCode());
            o.put("status", order.getStatus().name());
            o.put("totalAmount", order.getTotalAmount());
            o.put("itemCount", order.getItems() != null ? order.getItems().size() : 0);
            o.put("createdAt", order.getCreatedAt() != null ? order.getCreatedAt().toString() : "");
            o.put("productIds", order.getItems() != null
                    ? order.getItems().stream().filter(i -> !i.isDeleted() && i.getProduct() != null)
                            .map(i -> i.getProduct().getId()).collect(Collectors.toList())
                    : new ArrayList<>());
            result.add(o);
        }
        return result;
    }

    @Transactional
    public OrderResponse createOrderFromCart(CreateOrderRequest request) {
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());

        if (cart.getCartItems().isEmpty()) throw new IllegalArgumentException("Cart is empty");

        String orderCode = "VGA-" + LocalDateTime.now().toLocalDate() + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String fullName = (currentUser.getFullName() != null && !currentUser.getFullName().isEmpty()) ? currentUser.getFullName() : currentUser.getUsername();

        double total = 0;
        for (CartItem cartItem : cart.getCartItems()) {
            if (cartItem.isDeleted()) continue;
            Product product = cartItem.getProduct();
            if (product.getStock() < cartItem.getQuantity()) throw new IllegalArgumentException("Insufficient stock for '" + product.getName() + "'");
            total += product.getPrice().doubleValue() * cartItem.getQuantity();
        }

        entityManager.createNativeQuery("INSERT INTO orders (user_id, status, payment_status, full_name, phone, shipping_address, note, order_code, total_amount, discount_amount, deleted, created_at) " +
                                        "VALUES (:userId, 'PENDING', 'UNPAID', :fullName, :phone, :address, :note, :orderCode, :total, 0, false, CURRENT_TIMESTAMP)")
                .setParameter("userId", currentUser.getId())
                .setParameter("fullName", fullName)
                .setParameter("phone", request.getPhone())
                .setParameter("address", request.getShippingAddress())
                .setParameter("note", request.getNote() != null ? request.getNote() : "")
                .setParameter("orderCode", orderCode)
                .setParameter("total", BigDecimal.valueOf(total))
                .executeUpdate();

        Order order = (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE order_code = :orderCode ORDER BY id DESC LIMIT 1", Order.class)
                .setParameter("orderCode", orderCode).getSingleResult();

        for (CartItem cartItem : cart.getCartItems()) {
            if (cartItem.isDeleted()) continue;
            Product product = cartItem.getProduct();
            
            entityManager.createNativeQuery("INSERT INTO order_items (order_id, product_id, quantity, price, deleted) VALUES (:orderId, :productId, :quantity, :price, false)")
                    .setParameter("orderId", order.getId())
                    .setParameter("productId", product.getId())
                    .setParameter("quantity", cartItem.getQuantity())
                    .setParameter("price", product.getPrice())
                    .executeUpdate();

            entityManager.createNativeQuery("UPDATE products SET stock = stock - :quantity WHERE id = :id")
                    .setParameter("quantity", cartItem.getQuantity())
                    .setParameter("id", product.getId()).executeUpdate();
        }

        entityManager.refresh(order);

        entityManager.createNativeQuery("UPDATE cart_items SET deleted = true WHERE cart_id = :cartId")
                .setParameter("cartId", cart.getId()).executeUpdate();
        entityManager.createNativeQuery("UPDATE carts SET total_amount = 0 WHERE id = :id")
                .setParameter("id", cart.getId()).executeUpdate();

        return toOrderResponse(order);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> getMyOrders(int page, int size, String sortBy, String direction) {
        User user = userService.getCurrentUser();
        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM orders WHERE user_id = :userId AND deleted = false")
                .setParameter("userId", user.getId()).getSingleResult();

        List<Order> orders = entityManager.createNativeQuery("SELECT * FROM orders WHERE user_id = :userId AND deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset", Order.class)
                .setParameter("userId", user.getId())
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        List<OrderSummaryResponse> content = orders.stream().map(this::toOrderSummaryResponse).collect(Collectors.toList());
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long orderId) {
        User user = userService.getCurrentUser();
        try {
            Order order = (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE id = :orderId AND user_id = :userId AND deleted = false", Order.class)
                    .setParameter("orderId", orderId)
                    .setParameter("userId", user.getId()).getSingleResult();
            return toOrderResponse(order);
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Order not found");
        }
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        User user = userService.getCurrentUser();
        Order order;
        try {
            order = (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE id = :orderId AND user_id = :userId AND deleted = false", Order.class)
                    .setParameter("orderId", orderId)
                    .setParameter("userId", user.getId()).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Order not found");
        }

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.CONFIRMED)
            throw new IllegalArgumentException("Order can only be cancelled when PENDING or CONFIRMED");

        String current = order.getNote() != null ? order.getNote() : "";
        String separator = current.isEmpty() ? "" : " | ";
        String newNote = current;
        if (reason != null && !reason.trim().isEmpty()) {
            newNote += separator + "[CANCEL REASON]: " + reason;
        }

        entityManager.createNativeQuery("UPDATE orders SET status = 'CANCEL_REQUESTED', note = :note WHERE id = :id")
                .setParameter("note", newNote)
                .setParameter("id", order.getId()).executeUpdate();

        entityManager.refresh(order);
        return toOrderResponse(order);
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatusUpdateRequest request) {
        Order order;
        try {
            order = (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE id = :orderId AND deleted = false", Order.class)
                    .setParameter("orderId", orderId).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Order not found");
        }

        OrderStatus oldStatus = order.getStatus();
        String updateSql = "UPDATE orders SET status = :status";
        
        switch (request.getStatus()) {
            case CONFIRMED -> updateSql += ", confirmed_at = CURRENT_TIMESTAMP, payment_status = 'PAID'";
            case SHIPPING -> {
                updateSql += ", shipped_at = CURRENT_TIMESTAMP";
                if (order.getPaymentStatus() == PaymentStatus.UNPAID) updateSql += ", payment_status = 'PAID'";
            }
            case DELIVERED -> {
                updateSql += ", delivered_at = CURRENT_TIMESTAMP";
                if (order.getPaymentStatus() == PaymentStatus.UNPAID) updateSql += ", payment_status = 'PAID'";
            }
            case CANCELLED -> {
                if (oldStatus != OrderStatus.CANCELLED) {
                    for (OrderItem item : order.getItems()) {
                        if (item.isDeleted()) continue;
                        entityManager.createNativeQuery("UPDATE products SET stock = stock + :quantity WHERE id = :id")
                                .setParameter("quantity", item.getQuantity())
                                .setParameter("id", item.getProduct().getId()).executeUpdate();
                    }
                }
            }
            default -> {}
        }
        updateSql += " WHERE id = :id";

        entityManager.createNativeQuery(updateSql)
                .setParameter("status", request.getStatus().name())
                .setParameter("id", order.getId()).executeUpdate();

        entityManager.refresh(order);
        return toOrderResponse(order);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> getAllOrders(int page, int size, String sortBy, String direction) {
        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM orders WHERE deleted = false").getSingleResult();

        List<Order> orders = entityManager.createNativeQuery("SELECT * FROM orders WHERE deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset", Order.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        List<OrderSummaryResponse> content = orders.stream().map(this::toOrderSummaryResponse).collect(Collectors.toList());
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    private OrderResponse toOrderResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .filter(item -> !item.isDeleted())
                .map(item -> new OrderItemResponse(
                        item.getProduct().getId(), item.getProduct().getName(),
                        item.getProduct().getImgUrl(), item.getPrice(),
                        item.getQuantity(), item.getSubtotal()))
                .collect(Collectors.toList());

        String paymentMethod = "UNKNOWN";
        try {
            Payment payment = (Payment) entityManager.createNativeQuery("SELECT * FROM payments WHERE order_id = :orderId AND deleted = false ORDER BY id DESC LIMIT 1", Payment.class)
                    .setParameter("orderId", order.getId()).getSingleResult();
            if (payment != null && payment.getPaymentMethod() != null)
                paymentMethod = payment.getPaymentMethod().name();
        } catch (Exception ignored) {}

        String name  = (order.getFullName() != null && !order.getFullName().trim().isEmpty())
                ? order.getFullName() : (order.getUser() != null ? order.getUser().getUsername() : "Guest");
        String email = order.getUser() != null ? order.getUser().getEmail() : "";

        return new OrderResponse(order.getId(), order.getOrderCode(), order.getTotalAmount(),
                order.getDiscountAmount(), order.getStatus(), order.getPaymentStatus(),
                order.getShippingAddress(), order.getPhone(), order.getNote(),
                order.getCreatedAt(), order.getConfirmedAt(), order.getShippedAt(),
                order.getDeliveredAt(), items, name, email, paymentMethod);
    }

    private OrderSummaryResponse toOrderSummaryResponse(Order order) {
        int totalItems = order.getItems().stream()
                .filter(item -> !item.isDeleted())
                .mapToInt(OrderItem::getQuantity).sum();
        return new OrderSummaryResponse(order.getId(), order.getOrderCode(), order.getFullName(),
                order.getPhone(), order.getTotalAmount(), order.getStatus(),
                order.getPaymentStatus(), order.getCreatedAt(), totalItems);
    }
}
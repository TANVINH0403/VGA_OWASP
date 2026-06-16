package com.example.vgashop.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.dto.PaymentRequest;
import com.example.vgashop.dto.PaymentResponse;
import com.example.vgashop.dto.PaymentSummaryResponse;
import com.example.vgashop.entity.Order;
import com.example.vgashop.entity.OrderStatus;
import com.example.vgashop.entity.Payment;
import com.example.vgashop.entity.PaymentStatus;
import com.example.vgashop.entity.User;
import com.example.vgashop.exception.ResourceNotFoundException;
import com.example.vgashop.utils.MomoUtils;
import com.example.vgashop.utils.VNPayUtils;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class PaymentService {

    @Autowired
    private EntityManager entityManager;

    private final UserService userService;

    @Value("${vnpay.tmn-code}")    private String vnpayTmnCode;
    @Value("${vnpay.hash-secret}") private String vnpayHashSecret;
    @Value("${vnpay.url}")         private String vnpayUrl;
    @Value("${vnpay.return-url}")  private String vnpayReturnUrl;

    @Value("${momo.partner-code}") private String momoPartnerCode;
    @Value("${momo.access-key}")   private String momoAccessKey;
    @Value("${momo.secret-key}")   private String momoSecretKey;
    @Value("${momo.ipn-url}")      private String momoIpnUrl;
    @Value("${momo.return-url}")   private String momoReturnUrl;

    public PaymentService(UserService userService) {
        this.userService = userService;
    }

    private Order getOrderById(Long orderId, Long userId) {
        try {
            return (Order) entityManager.createNativeQuery("SELECT * FROM orders WHERE id = :orderId AND user_id = :userId AND deleted = false", Order.class)
                    .setParameter("orderId", orderId)
                    .setParameter("userId", userId).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
    }

    private Payment getLatestPaymentForOrder(Long orderId) {
        try {
            return (Payment) entityManager.createNativeQuery("SELECT * FROM payments WHERE order_id = :orderId AND deleted = false ORDER BY id DESC LIMIT 1", Payment.class)
                    .setParameter("orderId", orderId).getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional
    public PaymentResponse createPayment(Long orderId, PaymentRequest request, String clientIp) {
        User user = userService.getCurrentUser();
        Order order = getOrderById(orderId, user.getId());

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot create payment for a cancelled order");
        }

        Payment existing = getLatestPaymentForOrder(orderId);
        if (existing != null && existing.getPaymentStatus() == PaymentStatus.SUCCESS) {
            throw new IllegalArgumentException("Order has already been paid");
        }

        String transactionCode = "PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String note = "";
        String paymentUrl = null;

        switch (request.getPaymentMethod()) {
            case COD -> note = "Cash on delivery";
            case BANK_TRANSFER -> note = "Bank transfer. Ref: " + order.getOrderCode();
            case VNPAY -> {
                paymentUrl = VNPayUtils.createPaymentUrl(order, transactionCode, vnpayReturnUrl, clientIp, vnpayTmnCode, vnpayHashSecret, vnpayUrl);
                note = "VNPay";
            }
            case MOMO -> {
                paymentUrl = MomoUtils.createPaymentUrl(order, transactionCode, momoReturnUrl, momoPartnerCode, momoAccessKey, momoSecretKey, momoIpnUrl);
                note = "MoMo";
            }
        }

        entityManager.createNativeQuery("INSERT INTO payments (order_id, payment_method, amount, payment_status, transaction_code, note, payment_url, deleted, created_at) " +
                                        "VALUES (:orderId, :method, :amount, 'PENDING', :code, :note, :url, false, CURRENT_TIMESTAMP)")
                .setParameter("orderId", order.getId())
                .setParameter("method", request.getPaymentMethod().name())
                .setParameter("amount", order.getTotalAmount())
                .setParameter("code", transactionCode)
                .setParameter("note", note)
                .setParameter("url", paymentUrl)
                .executeUpdate();

        Payment savedPayment = getLatestPaymentForOrder(orderId);
        return toPaymentResponse(savedPayment);
    }

    @Transactional
    public PaymentResponse updatePaymentStatus(Long paymentId, PaymentStatus newStatus, String transactionCode) {
        Payment payment;
        try {
            payment = (Payment) entityManager.createNativeQuery("SELECT * FROM payments WHERE id = :id AND deleted = false", Payment.class)
                    .setParameter("id", paymentId).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Payment not found: " + paymentId);
        }

        String updateSql = "UPDATE payments SET payment_status = :status";
        if (newStatus == PaymentStatus.SUCCESS) updateSql += ", paid_at = CURRENT_TIMESTAMP";
        if (transactionCode != null && !transactionCode.isEmpty()) updateSql += ", transaction_code = :code";
        updateSql += " WHERE id = :id";

        var query = entityManager.createNativeQuery(updateSql)
                .setParameter("status", newStatus.name())
                .setParameter("id", paymentId);
        if (transactionCode != null && !transactionCode.isEmpty()) query.setParameter("code", transactionCode);
        query.executeUpdate();

        if (newStatus == PaymentStatus.SUCCESS) {
            entityManager.createNativeQuery("UPDATE orders SET status = 'CONFIRMED', payment_status = 'PAID' WHERE id = :id")
                    .setParameter("id", payment.getOrder().getId()).executeUpdate();
        } else if (newStatus == PaymentStatus.FAILED) {
            entityManager.createNativeQuery("UPDATE orders SET payment_status = 'UNPAID' WHERE id = :id")
                    .setParameter("id", payment.getOrder().getId()).executeUpdate();
        }

        entityManager.refresh(payment);
        return toPaymentResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByOrderId(Long orderId) {
        userService.getCurrentUser();
        Payment payment = getLatestPaymentForOrder(orderId);
        if (payment == null) throw new ResourceNotFoundException("No payment for order: " + orderId);
        return toPaymentResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(Long paymentId) {
        try {
            Payment payment = (Payment) entityManager.createNativeQuery("SELECT * FROM payments WHERE id = :id AND deleted = false", Payment.class)
                    .setParameter("id", paymentId).getSingleResult();
            return toPaymentResponse(payment);
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Payment not found: " + paymentId);
        }
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<PaymentSummaryResponse> getMyPayments(int size, int page, String sortBy, String direction) {
        User user = userService.getCurrentUser();
        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";

        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM payments p JOIN orders o ON p.order_id = o.id WHERE o.user_id = :userId AND p.deleted = false")
                .setParameter("userId", user.getId()).getSingleResult();

        List<Payment> payments = entityManager.createNativeQuery("SELECT p.* FROM payments p JOIN orders o ON p.order_id = o.id WHERE o.user_id = :userId AND p.deleted = false ORDER BY p." + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset", Payment.class)
                .setParameter("userId", user.getId())
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        List<PaymentSummaryResponse> content = payments.stream().map(this::toPaymentSummaryResponse).collect(Collectors.toList());
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<PaymentSummaryResponse> getAllPayments(int size, int page, String sortBy, String direction) {
        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";

        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM payments WHERE deleted = false").getSingleResult();

        List<Payment> payments = entityManager.createNativeQuery("SELECT * FROM payments WHERE deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset", Payment.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        List<PaymentSummaryResponse> content = payments.stream().map(this::toPaymentSummaryResponse).collect(Collectors.toList());
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<PaymentSummaryResponse> getPaymentsByStatus(PaymentStatus status, int size, int page, String sortBy, String direction) {
        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";

        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM payments WHERE payment_status = :status AND deleted = false")
                .setParameter("status", status.name()).getSingleResult();

        List<Payment> payments = entityManager.createNativeQuery("SELECT * FROM payments WHERE payment_status = :status AND deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset", Payment.class)
                .setParameter("status", status.name())
                .setParameter("limit", size)
                .setParameter("offset", page * size).getResultList();

        List<PaymentSummaryResponse> content = payments.stream().map(this::toPaymentSummaryResponse).collect(Collectors.toList());
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    public String getVnpayHashSecret() { return vnpayHashSecret; }
    public String getMomoSecretKey()   { return momoSecretKey; }

    private PaymentResponse toPaymentResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrder().getId(), p.getOrder().getOrderCode(),
                p.getAmount(), p.getPaymentMethod(), p.getPaymentStatus(),
                p.getTransactionCode(), p.getPaymentUrl(), p.getPaidAt(), p.getNote());
    }

    private PaymentSummaryResponse toPaymentSummaryResponse(Payment p) {
        return new PaymentSummaryResponse(p.getId(), p.getOrder().getOrderCode(),
                p.getAmount(), p.getPaymentMethod(), p.getPaymentStatus(), p.getCreatedAt());
    }
}

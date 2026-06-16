package com.example.vgashop.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.entity.Order;
import com.example.vgashop.entity.OrderItem;
import com.example.vgashop.entity.Payment;
import com.example.vgashop.entity.PaymentMethod;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class PaymentExpiryScheduler {

    private static final int EXPIRY_MINUTES = 15;

    @Autowired
    private EntityManager entityManager;

    public PaymentExpiryScheduler() {}

    @SuppressWarnings("unchecked")
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireUnpaidBankTransferOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EXPIRY_MINUTES);
        List<Order> expired = entityManager.createNativeQuery(
                "SELECT * FROM orders WHERE status = 'PENDING' AND payment_status = 'UNPAID' AND created_at <= :cutoff AND deleted = false", Order.class)
                .setParameter("cutoff", cutoff).getResultList();

        for (Order order : expired) {
            Payment payment = null;
            try {
                payment = (Payment) entityManager.createNativeQuery(
                        "SELECT * FROM payments WHERE order_id = :orderId AND deleted = false ORDER BY id DESC LIMIT 1", Payment.class)
                        .setParameter("orderId", order.getId()).getSingleResult();
            } catch (NoResultException ignored) {}

            if (payment != null && payment.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
                continue;
            }

            if (payment != null) {
                entityManager.createNativeQuery("UPDATE payments SET payment_status = 'FAILED' WHERE id = :id")
                        .setParameter("id", payment.getId()).executeUpdate();
            }

            for (OrderItem item : order.getItems()) {
                if (item.isDeleted()) continue;
                entityManager.createNativeQuery("UPDATE products SET stock = stock + :quantity WHERE id = :id")
                        .setParameter("quantity", item.getQuantity())
                        .setParameter("id", item.getProduct().getId()).executeUpdate();
            }

            String existingNote = order.getNote() != null ? order.getNote() + " | " : "";
            String newNote = existingNote + "[SYSTEM]: Payment expired after " + EXPIRY_MINUTES + " minutes";
            
            entityManager.createNativeQuery(
                    "UPDATE orders SET status = 'CANCELLED', payment_status = 'UNPAID', note = :note WHERE id = :id")
                    .setParameter("note", newNote)
                    .setParameter("id", order.getId()).executeUpdate();
        }
    }
}

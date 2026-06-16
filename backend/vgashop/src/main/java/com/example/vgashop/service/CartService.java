package com.example.vgashop.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.dto.CartResponse;
import com.example.vgashop.entity.User;
import com.example.vgashop.service.ProductService;
import com.example.vgashop.service.UserService;

import java.util.stream.Collectors;

import com.example.vgashop.dto.AddToCartRequest;
import com.example.vgashop.dto.CartItemResponse;
import com.example.vgashop.dto.UpdateCartItemRequest;
import com.example.vgashop.entity.Cart;
import com.example.vgashop.entity.CartItem;
import com.example.vgashop.entity.Product;
import com.example.vgashop.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class CartService {

    @Autowired
    private EntityManager entityManager;
    
    private final UserService userService;

    public CartService(UserService userService) {
        this.userService = userService;
    }

    private Cart getCartByUserId(Long userId) {
        try {
            return (Cart) entityManager.createNativeQuery("SELECT * FROM carts WHERE user_id = :userId AND deleted = false", Cart.class)
                    .setParameter("userId", userId).getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    private Cart createNewCartForUser(User user) {
        entityManager.createNativeQuery("INSERT INTO carts (user_id, total_amount, deleted) VALUES (:userId, 0, false)")
                .setParameter("userId", user.getId()).executeUpdate();
        return getCartByUserId(user.getId());
    }

    private Product getProductByIdNative(Long id) {
        try {
            return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE id = :id AND deleted = false", Product.class)
                    .setParameter("id", id).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm");
        }
    }

    @Transactional
    public CartResponse getMyCart() {
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());
        if (cart == null) {
            cart = createNewCartForUser(currentUser);
        }
        return convertToCartResponse(cart);
    }

    @Transactional
    public CartResponse addToCart(AddToCartRequest request) {
        if (request.getQuantity() <= 0) {
            throw new IllegalArgumentException("Số lượng phải lớn hơn hoặc bằng 1");
        }
        
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());
        if (cart == null) cart = createNewCartForUser(currentUser);

        Product product = getProductByIdNative(request.getProductId());
        
        if (product.getStock() < request.getQuantity()) {
            throw new IllegalArgumentException("Sản phẩm không đủ số lượng trong kho");
        }

        CartItem existingItem = cart.getCartItems().stream()
                .filter(item -> item.getProduct().getId().equals(product.getId()) && !item.isDeleted())
                .findFirst().orElse(null);

        if (existingItem != null) {
            int newQuantity = existingItem.getQuantity() + request.getQuantity();
            if (newQuantity > product.getStock()) throw new IllegalArgumentException("Sản phẩm không đủ số lượng trong kho");
            
            entityManager.createNativeQuery("UPDATE cart_items SET quantity = :quantity WHERE id = :id")
                    .setParameter("quantity", newQuantity)
                    .setParameter("id", existingItem.getId()).executeUpdate();
        } else {
            entityManager.createNativeQuery("INSERT INTO cart_items (cart_id, product_id, quantity, price, deleted) VALUES (:cartId, :productId, :quantity, :price, false)")
                    .setParameter("cartId", cart.getId())
                    .setParameter("productId", product.getId())
                    .setParameter("quantity", request.getQuantity())
                    .setParameter("price", product.getPrice())
                    .executeUpdate();
        }

        entityManager.refresh(cart);
        cart.recalculateTotal();
        entityManager.createNativeQuery("UPDATE carts SET total_amount = :total WHERE id = :id")
                .setParameter("total", cart.getTotalAmount())
                .setParameter("id", cart.getId()).executeUpdate();

        return convertToCartResponse(cart);
    }

    @Transactional
    public CartResponse updateCartItem(Long cartItemId, UpdateCartItemRequest request) {
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());
        if (cart == null) throw new ResourceNotFoundException("Giỏ hàng không tồn tại");

        CartItem cartItem = cart.getCartItems().stream()
                .filter(item -> item.getId().equals(cartItemId) && !item.isDeleted())
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy item trong giỏ"));

        if (request.getQuantity() <= 0) {
            entityManager.createNativeQuery("UPDATE cart_items SET deleted = true WHERE id = :id")
                    .setParameter("id", cartItemId).executeUpdate();
            cart.getCartItems().remove(cartItem);
        } else {
            if (request.getQuantity() > cartItem.getProduct().getStock()) {
                throw new IllegalArgumentException("Vượt quá số lượng tồn kho của sản phẩm");
            }
            entityManager.createNativeQuery("UPDATE cart_items SET quantity = :quantity WHERE id = :id")
                    .setParameter("quantity", request.getQuantity())
                    .setParameter("id", cartItemId).executeUpdate();
            cartItem.setQuantity(request.getQuantity());
        }

        cart.recalculateTotal();
        entityManager.createNativeQuery("UPDATE carts SET total_amount = :total WHERE id = :id")
                .setParameter("total", cart.getTotalAmount())
                .setParameter("id", cart.getId()).executeUpdate();

        return convertToCartResponse(cart);
    }

    @Transactional
    public CartResponse removeCartItem(Long cartItemId) {
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());
        if (cart == null) throw new ResourceNotFoundException("Giỏ hàng không tồn tại");

        boolean removed = cart.getCartItems().removeIf(item -> item.getId().equals(cartItemId));
        if (!removed) throw new ResourceNotFoundException("Không tìm thấy item trong giỏ");

        entityManager.createNativeQuery("UPDATE cart_items SET deleted = true WHERE id = :id")
                .setParameter("id", cartItemId).executeUpdate();

        cart.recalculateTotal();
        entityManager.createNativeQuery("UPDATE carts SET total_amount = :total WHERE id = :id")
                .setParameter("total", cart.getTotalAmount())
                .setParameter("id", cart.getId()).executeUpdate();

        return convertToCartResponse(cart);
    }

    @Transactional
    public void clearCart() {
        User currentUser = userService.getCurrentUser();
        Cart cart = getCartByUserId(currentUser.getId());
        if (cart != null) {
            entityManager.createNativeQuery("UPDATE cart_items SET deleted = true WHERE cart_id = :cartId")
                    .setParameter("cartId", cart.getId()).executeUpdate();
            entityManager.createNativeQuery("UPDATE carts SET total_amount = 0 WHERE id = :id")
                    .setParameter("id", cart.getId()).executeUpdate();
        }
    }

    public CartResponse convertToCartResponse(Cart cart) {
        List<CartItemResponse> itemResponses = cart.getCartItems().stream()
            .filter(item -> !item.isDeleted())
            .map(item -> new CartItemResponse(
                item.getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getImgUrl(),
                item.getProduct().getPrice(),
                item.getQuantity(),
                item.getSubtotal()
            )).collect(Collectors.toList());

        int totalItems = cart.getCartItems().stream()
            .filter(item -> !item.isDeleted())
            .mapToInt(CartItem::getQuantity)
            .sum();

        return new CartResponse(cart.getId(), cart.getTotalAmount(), totalItems, itemResponses);
    }
}

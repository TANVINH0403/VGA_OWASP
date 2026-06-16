package com.example.vgashop.service;

import com.example.vgashop.dto.ChangePasswordRequest;
import com.example.vgashop.dto.UserAddressDto;
import com.example.vgashop.dto.UserProfileRequest;
import com.example.vgashop.dto.UserProfileResponse;
import com.example.vgashop.dto.UserDTO;
import com.example.vgashop.entity.Role;
import com.example.vgashop.entity.User;
import com.example.vgashop.entity.UserAddress;
import com.example.vgashop.exception.DuplicateResourceException;
import com.example.vgashop.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    public UserService() {
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    // Lấy user an toàn bằng Native SQL (tái sử dụng nội bộ)
    private User getUserByUsernameNative(String username) {
        try {
            return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE username = :username AND deleted = false", User.class)
                    .setParameter("username", username)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new RuntimeException("User not found");
        }
    }

    public UserProfileResponse getUserProfile(String username) {
        User user = getUserByUsernameNative(username);
        return mapToDtoProfile(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(String username, UserProfileRequest request) {
        User user = getUserByUsernameNative(username);

        String sql = "UPDATE users SET username = :newUsername, phone = :phone, gender = :gender, dob = :dob WHERE id = :id";
        entityManager.createNativeQuery(sql)
                .setParameter("newUsername", request.getUsername())
                .setParameter("phone", request.getPhone())
                .setParameter("gender", request.getGender())
                .setParameter("dob", request.getDob())
                .setParameter("id", user.getId())
                .executeUpdate();

        // Cập nhật state hiện tại để map sang DTO mà không cần query lại
        user.setUsername(request.getUsername());
        user.setPhone(request.getPhone());
        user.setGender(request.getGender());
        user.setDob(request.getDob());

        return mapToDtoProfile(user);
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest req) {
        User user = getUserByUsernameNative(username);

        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Mật khẩu cũ không đúng!");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Mật khẩu mới không được trùng mật khẩu cũ!");
        }

        String sql = "UPDATE users SET password = :password WHERE id = :id";
        entityManager.createNativeQuery(sql)
                .setParameter("password", passwordEncoder.encode(req.getNewPassword()))
                .setParameter("id", user.getId())
                .executeUpdate();
    }

    @Transactional
    public UserProfileResponse addAddress(String username, UserAddressDto dto) {
        User user = getUserByUsernameNative(username);

        boolean isDefault = (dto.getIsDefault() != null && dto.getIsDefault()) || user.getAddresses().isEmpty();

        if (isDefault) {
            String updateOldDefaultSql = "UPDATE user_addresses SET is_default = false WHERE user_id = :userId";
            entityManager.createNativeQuery(updateOldDefaultSql)
                    .setParameter("userId", user.getId())
                    .executeUpdate();
        }

        String insertAddressSql = "INSERT INTO user_addresses (user_id, recipient_name, phone, detailed_address, is_default) " +
                                  "VALUES (:userId, :recipientName, :phone, :detailedAddress, :isDefault)";
        entityManager.createNativeQuery(insertAddressSql)
                .setParameter("userId", user.getId())
                .setParameter("recipientName", dto.getRecipientName())
                .setParameter("phone", dto.getPhone())
                .setParameter("detailedAddress", dto.getDetailedAddress())
                .setParameter("isDefault", isDefault)
                .executeUpdate();

        // Tải lại user để có mảng addresses mới nhất
        entityManager.refresh(user);
        return mapToDtoProfile(user);
    }

    @Transactional
    public UserProfileResponse deleteAddress(String username, Long addressId) {
        User user = getUserByUsernameNative(username);

        String deleteAddressSql = "DELETE FROM user_addresses WHERE id = :addressId AND user_id = :userId";
        entityManager.createNativeQuery(deleteAddressSql)
                .setParameter("addressId", addressId)
                .setParameter("userId", user.getId())
                .executeUpdate();

        entityManager.refresh(user);
        return mapToDtoProfile(user);
    }

    private UserProfileResponse mapToDtoProfile(User user) {
        UserProfileResponse dto = new UserProfileResponse();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        if (user.getRole() != null) dto.setRole(user.getRole().name());
        dto.setPhone(user.getPhone());
        dto.setGender(user.getGender());
        dto.setDob(user.getDob());

        if (user.getAddresses() != null) {
            dto.setAddresses(user.getAddresses().stream().map(a -> 
                new UserAddressDto(a.getId(), a.getRecipientName(), a.getPhone(), a.getDetailedAddress(), a.getIsDefault())
            ).collect(Collectors.toList()));
        }
        return dto;
    }

    // ==========================================

    @SuppressWarnings("unchecked")
    public Page<User> getAllUsers(int page, int size, String sortBy, String direction) {
        String countSql = "SELECT COUNT(*) FROM users WHERE deleted = false";
        Number total = (Number) entityManager.createNativeQuery(countSql).getSingleResult();

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        String fetchSql = "SELECT * FROM users WHERE deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset";
        
        List<User> content = entityManager.createNativeQuery(fetchSql, User.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size)
                .getResultList();

        return new PageImpl<>(content, PageRequest.of(page, size), total.longValue());
    }

    @SuppressWarnings("unchecked")
    public Page<User> searchUsers(String keyWord, int page, int size) {
        String searchParam = (keyWord == null || keyWord.trim().isEmpty()) ? "%" : "%" + keyWord.trim() + "%";

        String countSql = "SELECT COUNT(*) FROM users WHERE deleted = false AND (LOWER(username) LIKE LOWER(:keyword) OR LOWER(email) LIKE LOWER(:keyword))";
        Number total = (Number) entityManager.createNativeQuery(countSql)
                .setParameter("keyword", searchParam)
                .getSingleResult();

        String fetchSql = "SELECT * FROM users WHERE deleted = false AND (LOWER(username) LIKE LOWER(:keyword) OR LOWER(email) LIKE LOWER(:keyword)) ORDER BY username ASC LIMIT :limit OFFSET :offset";
        List<User> content = entityManager.createNativeQuery(fetchSql, User.class)
                .setParameter("keyword", searchParam)
                .setParameter("limit", size)
                .setParameter("offset", page * size)
                .getResultList();

        return new PageImpl<>(content, PageRequest.of(page, size), total.longValue());
    }

    public User getUserById(Long id) {
        String sql = "SELECT * FROM users WHERE id = :id AND deleted = false";
        try {
            return (User) entityManager.createNativeQuery(sql, User.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng với ID " + id);
        }
    }

    public User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (username == null || username.trim().isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng đang đăng nhập");
        }
        
        String sql = "SELECT * FROM users WHERE username = :username AND deleted = false";
        try {
            return (User) entityManager.createNativeQuery(sql, User.class)
                    .setParameter("username", username)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng: " + username);
        }
    }

    @Transactional
    public User createUser(UserDTO dto) {
        Number userCount = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE username = :username")
                .setParameter("username", dto.getUsername())
                .getSingleResult();
        if (userCount.intValue() > 0) throw new DuplicateResourceException("Username '" + dto.getUsername() + "' đã tồn tại!");
        
        Number emailCount = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE email = :email")
                .setParameter("email", dto.getEmail())
                .getSingleResult();
        if (emailCount.intValue() > 0) throw new DuplicateResourceException("Email '" + dto.getEmail() + "' đã tồn tại!");

        String roleStr = dto.getRole() != null ? dto.getRole().toUpperCase() : "USER";

        String insertSql = "INSERT INTO users (username, email, password, full_name, phone, address, avatar, role, deleted, status) " +
                           "VALUES (:username, :email, :password, :fullName, :phone, :address, :avatar, :role, false, true)";
                           
        entityManager.createNativeQuery(insertSql)
                .setParameter("username", dto.getUsername())
                .setParameter("email", dto.getEmail())
                .setParameter("password", passwordEncoder.encode(dto.getPassword()))
                .setParameter("fullName", dto.getFullName())
                .setParameter("phone", dto.getPhone())
                .setParameter("address", dto.getAddress())
                .setParameter("avatar", dto.getAvatar())
                .setParameter("role", roleStr)
                .executeUpdate();

        return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE username = :username", User.class)
                .setParameter("username", dto.getUsername())
                .getSingleResult();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public User updateUser(Long id, UserDTO dto) {
        List<User> existingUsers = entityManager.createNativeQuery("SELECT * FROM users WHERE id = :id AND deleted = false", User.class)
                .setParameter("id", id)
                .getResultList();
                
        if (existingUsers.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng với ID " + id);
        }
        User user = existingUsers.get(0);

        if (!user.getUsername().equals(dto.getUsername())) {
            Number uCount = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE username = :username").setParameter("username", dto.getUsername()).getSingleResult();
            if (uCount.intValue() > 0) throw new DuplicateResourceException("UserName '" + dto.getUsername() + "' đã tồn tại!");
        }
        if (!user.getEmail().equals(dto.getEmail())) {
            Number eCount = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE email = :email").setParameter("email", dto.getEmail()).getSingleResult();
            if (eCount.intValue() > 0) throw new DuplicateResourceException("Email '" + dto.getEmail() + "' đã tồn tại!");
        }

        String roleStr = dto.getRole() != null ? dto.getRole().toUpperCase() : (user.getRole() != null ? user.getRole().name() : "USER");

        String updateSql = "UPDATE users SET username = :username, email = :email, full_name = :fullName, " +
                           "phone = :phone, address = :address, avatar = :avatar, role = :role WHERE id = :id";
                           
        entityManager.createNativeQuery(updateSql)
                .setParameter("username", dto.getUsername())
                .setParameter("email", dto.getEmail())
                .setParameter("fullName", dto.getFullName())
                .setParameter("phone", dto.getPhone())
                .setParameter("address", dto.getAddress())
                .setParameter("avatar", dto.getAvatar())
                .setParameter("role", roleStr)
                .setParameter("id", id)
                .executeUpdate();

        return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE id = :id", User.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    @Transactional
    public void deleteUser(Long id) {
        String checkSql = "SELECT COUNT(*) FROM users WHERE id = :id AND deleted = false";
        Number count = (Number) entityManager.createNativeQuery(checkSql)
                .setParameter("id", id)
                .getSingleResult();
        
        if (count.intValue() == 0) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng với ID " + id);
        }

        String updateSql = "UPDATE users SET deleted = true WHERE id = :id";
        entityManager.createNativeQuery(updateSql)
                .setParameter("id", id)
                .executeUpdate();
    }
}
package com.example.vgashop.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.dto.AuthResponse;
import com.example.vgashop.dto.GoogleLoginRequest;
import com.example.vgashop.dto.RegisterRequest;
import com.example.vgashop.dto.UserDTO;
import com.example.vgashop.entity.Role;
import com.example.vgashop.entity.User;
import com.example.vgashop.exception.DuplicateResourceException;
import com.example.vgashop.exception.ResourceNotFoundException;
import com.example.vgashop.security.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AuthService {

    private final JwtUtil              jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    public AuthService(JwtUtil jwtUtil) {
        this.jwtUtil         = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    private boolean existsByEmail(String email) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private boolean existsByUsername(String username) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE username = :username")
                .setParameter("username", username)
                .getSingleResult();
        return count.intValue() > 0;
    }

    // ĐOẠN CODE BỊ SỬA ĐỂ TẠO LỖI SQLi
    private User findByUsername(String username) {
        try {
            // Cộng chuỗi trực tiếp biến 'username' vào câu lệnh SQL
            String sql = "SELECT * FROM users WHERE username = '" + username + "'";
            
            return (User) entityManager.createNativeQuery(sql, User.class)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

        // HÀM CỐ TÌNH GÂY LỖI BYPASS LOGIN CHO LAB OWASP
    private User findByUsernameAndPasswordInsecure(String username, String password) {
        try {
            // Lỗ hổng ghép chuỗi cả username và password
            String sql = "SELECT * FROM users WHERE username = '" + username + "' AND password = '" + password + "'";
            return (User) entityManager.createNativeQuery(sql, User.class)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    
    private User findByEmail(String email) {
        try {
            return (User) entityManager.createNativeQuery("SELECT * FROM users WHERE email = :email", User.class)
                    .setParameter("email", email)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (existsByEmail(req.getEmail()))
            throw new DuplicateResourceException("Email is already in use");
        if (existsByUsername(req.getUsername()))
            throw new DuplicateResourceException("Username is already taken");

        String insertSql = "INSERT INTO users (username, email, password, full_name, role, status, deleted) " +
                           "VALUES (:username, :email, :password, :fullName, :role, true, false)";

        entityManager.createNativeQuery(insertSql)
                .setParameter("username", req.getUsername())
                .setParameter("email", req.getEmail())
                .setParameter("password", passwordEncoder.encode(req.getPassword()))
                .setParameter("fullName", req.getFullName())
                .setParameter("role", Role.USER.name())
                .executeUpdate();

        User saved = findByUsername(req.getUsername());
        
        return new AuthResponse(jwtUtil.generateToken(saved.getUsername(), saved.getRole()),
                saved.getUsername(), saved.getEmail(), saved.getRole().name(), saved.getId(),
                "Registration successful");
    }

    @Transactional
    public AuthResponse register(UserDTO dto) {
        if (existsByUsername(dto.getUsername()))
            throw new DuplicateResourceException("Username is already taken");
        if (existsByEmail(dto.getEmail()))
            throw new DuplicateResourceException("Email is already in use");

        String roleStr = dto.getRole() != null ? dto.getRole().toUpperCase() : Role.USER.name();

        String insertSql = "INSERT INTO users (username, email, password, full_name, phone, address, role, status, deleted) " +
                           "VALUES (:username, :email, :password, :fullName, :phone, :address, :role, true, false)";

        entityManager.createNativeQuery(insertSql)
                .setParameter("username", dto.getUsername())
                .setParameter("email", dto.getEmail())
                .setParameter("password", passwordEncoder.encode(dto.getPassword()))
                .setParameter("fullName", dto.getFullName())
                .setParameter("phone", dto.getPhone())
                .setParameter("address", dto.getAddress())
                .setParameter("role", roleStr)
                .executeUpdate();

        User saved = findByUsername(dto.getUsername());

        return new AuthResponse(jwtUtil.generateToken(saved.getUsername(), saved.getRole()),
                saved.getUsername(), saved.getEmail(), saved.getRole().name(), saved.getId(),
                "Registration successful");
    }

    public AuthResponse login(String username, String password) {
         User user = findByUsernameAndPasswordInsecure(username, password);
        
        if (user == null) {
            throw new ResourceNotFoundException("Invalid username or password");
        }

        if (Boolean.TRUE.equals(user.isDeleted()))
            throw new RuntimeException("Account does not exist or has been removed");

        if (Boolean.FALSE.equals(user.getStatus()))
            throw new RuntimeException("Account is disabled. Please contact support.");

        if (!passwordEncoder.matches(password, user.getPassword()))
            throw new RuntimeException("Invalid username or password");

        return new AuthResponse(jwtUtil.generateToken(user.getUsername(), user.getRole()),
                user.getUsername(), user.getEmail(), user.getRole().name(), user.getId(),
                "Login successful");
    }

    @Transactional
    public AuthResponse googleLogin(GoogleLoginRequest req) {
        User user = findByEmail(req.getEmail());

        if (user == null) {
            String prefix   = req.getEmail().split("@")[0];
            String username = prefix;
            int    counter  = 1;
            while (existsByUsername(username)) username = prefix + counter++;

            String insertSql = "INSERT INTO users (username, email, password, full_name, role, status, deleted) " +
                               "VALUES (:username, :email, :password, :fullName, :role, true, false)";

            entityManager.createNativeQuery(insertSql)
                    .setParameter("username", username)
                    .setParameter("email", req.getEmail())
                    .setParameter("password", passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                    .setParameter("fullName", req.getName())
                    .setParameter("role", Role.USER.name())
                    .executeUpdate();
                    
            user = findByUsername(username);
        } else {
            if (Boolean.FALSE.equals(user.getStatus()))
                throw new RuntimeException("Account is disabled");
        }

        return new AuthResponse(jwtUtil.generateToken(user.getUsername(), user.getRole()),
                user.getUsername(), user.getEmail(), user.getRole().name(), user.getId(),
                "Login successful");
    }
}
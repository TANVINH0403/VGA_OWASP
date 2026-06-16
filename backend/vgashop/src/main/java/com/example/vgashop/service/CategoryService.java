package com.example.vgashop.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.entity.Category;
import com.example.vgashop.exception.DuplicateResourceException;
import com.example.vgashop.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class CategoryService {

    @Autowired
    private EntityManager entityManager;

    public CategoryService() {}

    private boolean existsByName(String name) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM categories WHERE LOWER(name) = LOWER(:name)")
                .setParameter("name", name).getSingleResult();
        return count.intValue() > 0;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Category> getAllCategories(Pageable pageable) {
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM categories WHERE deleted = false").getSingleResult();
        
        String fetchSql = "SELECT * FROM categories WHERE deleted = false LIMIT :limit OFFSET :offset";
        List<Category> content = entityManager.createNativeQuery(fetchSql, Category.class)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();

        return new PageImpl<>(content, pageable, total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Category> getActiveCategories(Pageable pageable) {
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM categories WHERE active = true AND deleted = false").getSingleResult();
        
        String fetchSql = "SELECT * FROM categories WHERE active = true AND deleted = false LIMIT :limit OFFSET :offset";
        List<Category> content = entityManager.createNativeQuery(fetchSql, Category.class)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();

        return new PageImpl<>(content, pageable, total.longValue());
    }

    @Transactional(readOnly = true)
    public Category getCategoryById(Long id) {
        try {
            return (Category) entityManager.createNativeQuery("SELECT * FROM categories WHERE id = :id AND deleted = false", Category.class)
                    .setParameter("id", id).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy danh mục với ID " + id);
        }
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Category> searchCategory(String keyWord, Pageable pageable) {
        if (keyWord == null || keyWord.trim().isEmpty()) {
            return getAllCategories(pageable);
        }
        
        String searchParam = "%" + keyWord.trim() + "%";
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM categories WHERE name LIKE :keyword AND deleted = false")
                .setParameter("keyword", searchParam).getSingleResult();

        String fetchSql = "SELECT * FROM categories WHERE name LIKE :keyword AND deleted = false LIMIT :limit OFFSET :offset";
        List<Category> content = entityManager.createNativeQuery(fetchSql, Category.class)
                .setParameter("keyword", searchParam)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();

        return new PageImpl<>(content, pageable, total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Category> filterCategories(String keyWord, Boolean active, int page, int size, String sortBy, String direction) {
        keyWord = (keyWord == null) ? "" : keyWord.trim();

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM categories WHERE deleted = false");
        StringBuilder fetchSql = new StringBuilder("SELECT * FROM categories WHERE deleted = false");

        if (!keyWord.isEmpty()) {
            countSql.append(" AND LOWER(name) LIKE LOWER(:keyword)");
            fetchSql.append(" AND LOWER(name) LIKE LOWER(:keyword)");
        }
        if (active != null) {
            countSql.append(" AND active = :active");
            fetchSql.append(" AND active = :active");
        }

        fetchSql.append(" ORDER BY display_order ASC, ").append(safeSort).append(" ").append(safeDir).append(" LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString());
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), Category.class);

        if (!keyWord.isEmpty()) {
            countQuery.setParameter("keyword", "%" + keyWord + "%");
            fetchQuery.setParameter("keyword", "%" + keyWord + "%");
        }
        if (active != null) {
            countQuery.setParameter("active", active);
            fetchQuery.setParameter("active", active);
        }

        fetchQuery.setParameter("limit", size);
        fetchQuery.setParameter("offset", page * size);

        Number total = (Number) countQuery.getSingleResult();
        List<Category> content = fetchQuery.getResultList();

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(sort)), total.longValue());
    }

    @Transactional
    public Category createCategory(Category category) {
        if (existsByName(category.getName())) {
            throw new DuplicateResourceException("Tên danh mục '" + category.getName() + "' đã tồn tại!");
        }

        entityManager.createNativeQuery("INSERT INTO categories (name, description, active, display_order, deleted) VALUES (:name, :desc, :active, 0, false)")
                .setParameter("name", category.getName())
                .setParameter("desc", category.getDescription())
                .setParameter("active", category.getActive() != null ? category.getActive() : true)
                .executeUpdate();

        return (Category) entityManager.createNativeQuery("SELECT * FROM categories WHERE name = :name", Category.class)
                .setParameter("name", category.getName()).getSingleResult();
    }

    @Transactional
    public Category updateCategory(Long id, Category newCategory) {
        Category category = getCategoryById(id);

        if (!category.getName().equalsIgnoreCase(newCategory.getName()) && existsByName(newCategory.getName())) {
            throw new DuplicateResourceException("Tên danh mục '" + newCategory.getName() + "' đã tồn tại!");
        }

        entityManager.createNativeQuery("UPDATE categories SET name = :name, description = :desc, active = :active WHERE id = :id")
                .setParameter("name", newCategory.getName())
                .setParameter("desc", newCategory.getDescription())
                .setParameter("active", newCategory.getActive())
                .setParameter("id", id)
                .executeUpdate();

        return getCategoryById(id);
    }

    @Transactional
    public void deleteCategory(Long id) {
        getCategoryById(id);
        entityManager.createNativeQuery("UPDATE categories SET deleted = true WHERE id = :id")
                .setParameter("id", id).executeUpdate();
    }
}

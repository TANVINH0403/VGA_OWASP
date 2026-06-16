package com.example.vgashop.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.entity.Brand;
import com.example.vgashop.exception.DuplicateResourceException;
import com.example.vgashop.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class BrandService {

    @Autowired
    private EntityManager entityManager;

    public BrandService() {}

    private boolean existsByName(String name) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM brands WHERE LOWER(name) = LOWER(:name)")
                .setParameter("name", name).getSingleResult();
        return count.intValue() > 0;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Brand> getAllBrands(int page, int size, String sortBy, String direction) {
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM brands").getSingleResult();

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        String fetchSql = "SELECT * FROM brands ORDER BY display_order ASC, " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset";
        List<Brand> content = entityManager.createNativeQuery(fetchSql, Brand.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size)
                .getResultList();

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(sort)), total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Brand> getAllNoPage() {
        return entityManager.createNativeQuery("SELECT * FROM brands ORDER BY display_order ASC, name ASC", Brand.class).getResultList();
    }

    @Transactional(readOnly = true)
    public Brand getBrandId(Long id) {
        try {
            return (Brand) entityManager.createNativeQuery("SELECT * FROM brands WHERE id = :id AND deleted = false", Brand.class)
                    .setParameter("id", id).getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy thương hiệu với ID " + id);
        }
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Brand> searchBrand(String keyWord, int page, int size) {
        if (keyWord == null || keyWord.trim().isEmpty()) {
            return getAllBrands(page, size, "name", "asc");
        }
        
        String searchParam = "%" + keyWord.trim() + "%";
        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM brands WHERE name LIKE :keyword")
                .setParameter("keyword", searchParam).getSingleResult();

        String fetchSql = "SELECT * FROM brands WHERE name LIKE :keyword ORDER BY name ASC LIMIT :limit OFFSET :offset";
        List<Brand> content = entityManager.createNativeQuery(fetchSql, Brand.class)
                .setParameter("keyword", searchParam)
                .setParameter("limit", size)
                .setParameter("offset", page * size)
                .getResultList();

        return new PageImpl<>(content, PageRequest.of(page, size, Sort.by("name").ascending()), total.longValue());
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Page<Brand> filterBrands(String keyWord, Boolean status, int page, int size, String sortBy, String direction) {
        keyWord = (keyWord == null) ? "" : keyWord.trim();

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM brands WHERE 1=1");
        StringBuilder fetchSql = new StringBuilder("SELECT * FROM brands WHERE 1=1");

        if (!keyWord.isEmpty()) {
            countSql.append(" AND LOWER(name) LIKE LOWER(:keyword)");
            fetchSql.append(" AND LOWER(name) LIKE LOWER(:keyword)");
        }
        if (status != null) {
            countSql.append(" AND status = :status");
            fetchSql.append(" AND status = :status");
        }

        fetchSql.append(" ORDER BY display_order ASC, ").append(safeSort).append(" ").append(safeDir).append(" LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString());
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), Brand.class);

        if (!keyWord.isEmpty()) {
            countQuery.setParameter("keyword", "%" + keyWord + "%");
            fetchQuery.setParameter("keyword", "%" + keyWord + "%");
        }
        if (status != null) {
            countQuery.setParameter("status", status);
            fetchQuery.setParameter("status", status);
        }

        fetchQuery.setParameter("limit", size);
        fetchQuery.setParameter("offset", page * size);

        Number total = (Number) countQuery.getSingleResult();
        List<Brand> content = fetchQuery.getResultList();

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(sort)), total.longValue());
    }

    @Transactional
    public Brand createBrand(Brand brand) {
        if (existsByName(brand.getName())) {
            throw new DuplicateResourceException("Thương hiệu '" + brand.getName() + "' đã tồn tại!");
        }

        String slug = brand.getSlug();
        if (slug == null || slug.trim().isEmpty()) slug = generateSlug(brand.getName());

        entityManager.createNativeQuery("INSERT INTO brands (name, description, logo, status, slug, display_order, deleted) VALUES (:name, :desc, :logo, :status, :slug, 0, false)")
                .setParameter("name", brand.getName())
                .setParameter("desc", brand.getDescription())
                .setParameter("logo", brand.getLogo())
                .setParameter("status", brand.getStatus() != null ? brand.getStatus() : true)
                .setParameter("slug", slug)
                .executeUpdate();

        return (Brand) entityManager.createNativeQuery("SELECT * FROM brands WHERE name = :name", Brand.class)
                .setParameter("name", brand.getName()).getSingleResult();
    }

    @Transactional
    public Brand updateBrand(Long id, Brand newBrand) {
        Brand brand = getBrandId(id);

        if (!brand.getName().equalsIgnoreCase(newBrand.getName()) && existsByName(newBrand.getName())) {
            throw new DuplicateResourceException("Tên thương hiệu '" + newBrand.getName() + "' đã tồn tại!");
        }

        String slug = newBrand.getSlug();
        if (!brand.getName().equalsIgnoreCase(newBrand.getName()) && (slug == null || slug.trim().isEmpty())) {
            slug = generateSlug(newBrand.getName());
        } else if (slug == null || slug.trim().isEmpty()) {
            slug = brand.getSlug();
        }

        entityManager.createNativeQuery("UPDATE brands SET name = :name, description = :desc, logo = :logo, status = :status, slug = :slug WHERE id = :id")
                .setParameter("name", newBrand.getName())
                .setParameter("desc", newBrand.getDescription())
                .setParameter("logo", newBrand.getLogo())
                .setParameter("status", newBrand.getStatus())
                .setParameter("slug", slug)
                .setParameter("id", id)
                .executeUpdate();

        return getBrandId(id);
    }

    @Transactional
    public void deleteBrand(Long id) {
        getBrandId(id); // Check existence
        entityManager.createNativeQuery("UPDATE brands SET deleted = true WHERE id = :id")
                .setParameter("id", id).executeUpdate();
    }

    private String generateSlug(String name) {
        if (name == null || name.trim().isEmpty()) return "";
        return name.toLowerCase().trim().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", "");
    }
}

package com.example.vgashop.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import com.example.vgashop.dto.ProductDTO;
import com.example.vgashop.entity.Product;
import com.example.vgashop.exception.DuplicateResourceException;
import com.example.vgashop.exception.ResourceNotFoundException;
import com.example.vgashop.dto.ProductImageDTO;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class ProductService {

    private final CategoryService categoryService;
    private final BrandService brandService;

    @Autowired
    private EntityManager entityManager;

    public ProductService(BrandService brandService, CategoryService categoryService) {
        this.brandService = brandService;
        this.categoryService = categoryService;
    }

    private boolean existsByName(String name) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM products WHERE LOWER(name) = LOWER(:name)")
                .setParameter("name", name)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private boolean existsBySku(String sku) {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM products WHERE LOWER(sku) = LOWER(:sku)")
                .setParameter("sku", sku)
                .getSingleResult();
        return count.intValue() > 0;
    }

    @SuppressWarnings("unchecked")
    public Page<Product> getAllProducts(int page, int size, String sortBy, String direction) {
        String countSql = "SELECT COUNT(*) FROM products WHERE deleted = false";
        Number total = (Number) entityManager.createNativeQuery(countSql).getSingleResult();

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        String fetchSql = "SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE deleted = false ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset";
        
        List<Product> content = entityManager.createNativeQuery(fetchSql, Product.class)
                .setParameter("limit", size)
                .setParameter("offset", page * size)
                .getResultList();

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder").and(sort)), total.longValue());
    }

    public Product getProductById(Long id) {
        try {
            return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE id = :id AND deleted = false", Product.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm với ID " + id);
        }
    }

    @SuppressWarnings("unchecked")
    public Page<Product> searchProducts(String keyWord, Pageable pageable) {
        String searchParam = (keyWord == null || keyWord.trim().isEmpty()) ? "%" : "%" + keyWord.trim() + "%";

        String countSql = "SELECT COUNT(*) FROM products WHERE name LIKE :keyword";
        Number total = (Number) entityManager.createNativeQuery(countSql).setParameter("keyword", searchParam).getSingleResult();

        String fetchSql = "SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE name LIKE :keyword LIMIT :limit OFFSET :offset";
        List<Product> content = entityManager.createNativeQuery(fetchSql, Product.class)
                .setParameter("keyword", searchParam)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();

        return new PageImpl<>(content, pageable, total.longValue());
    }

    @SuppressWarnings("unchecked")
    public Page<Product> filterByBrand(String brand) {
        String countSql = "SELECT COUNT(p.*) FROM products p JOIN brands b ON p.brand_id = b.id WHERE b.name = :brand";
        Number total = (Number) entityManager.createNativeQuery(countSql).setParameter("brand", brand).getSingleResult();

        String fetchSql = "SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p JOIN brands b ON p.brand_id = b.id WHERE b.name = :brand";
        List<Product> content = entityManager.createNativeQuery(fetchSql, Product.class)
                .setParameter("brand", brand)
                .getResultList();

        return new PageImpl<>(content, Pageable.unpaged(), total.longValue());
    }

    @SuppressWarnings("unchecked")
    public Page<Product> searchAndFilter(String keyWord, String brand, Pageable pageable) {
        String searchParam = (keyWord == null || keyWord.trim().isEmpty()) ? "%" : "%" + keyWord.trim() + "%";
        
        String countSql = "SELECT COUNT(p.*) FROM products p JOIN brands b ON p.brand_id = b.id WHERE p.name LIKE :keyword AND b.name = :brand";
        Number total = (Number) entityManager.createNativeQuery(countSql)
                .setParameter("keyword", searchParam)
                .setParameter("brand", brand)
                .getSingleResult();

        String fetchSql = "SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p JOIN brands b ON p.brand_id = b.id WHERE p.name LIKE :keyword AND b.name = :brand LIMIT :limit OFFSET :offset";
        List<Product> content = entityManager.createNativeQuery(fetchSql, Product.class)
                .setParameter("keyword", searchParam)
                .setParameter("brand", brand)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();

        return new PageImpl<>(content, pageable, total.longValue());
    }

    @SuppressWarnings("unchecked")
    public Page<Product> filterProducts(
        String keyWord, List<Long> brandId, Double minPrice, Double maxPrice,
        int page, int size, String sortBy, String direction
    ) {
        keyWord = (keyWord == null) ? "" : keyWord;
        minPrice = (minPrice == null) ? 0.0 : minPrice;
        maxPrice = (maxPrice == null) ? Double.MAX_VALUE : maxPrice;
        String searchParam = "%" + keyWord.trim() + "%";

        String safeSort = sortBy.matches("^[a-zA-Z0-9_]+$") ? sortBy.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase() : "id";
        String safeDir = direction.equalsIgnoreCase("desc") ? "DESC" : "ASC";
        
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM products WHERE name LIKE :keyword AND price BETWEEN :minPrice AND :maxPrice");
        StringBuilder fetchSql = new StringBuilder("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE name LIKE :keyword AND price BETWEEN :minPrice AND :maxPrice");
        
        if (brandId != null && !brandId.isEmpty()) {
            countSql.append(" AND brand_id IN (:brandIds)");
            fetchSql.append(" AND brand_id IN (:brandIds)");
        }
        
        fetchSql.append(" ORDER BY ").append(safeSort).append(" ").append(safeDir).append(" LIMIT :limit OFFSET :offset");

        var countQuery = entityManager.createNativeQuery(countSql.toString())
                .setParameter("keyword", searchParam)
                .setParameter("minPrice", minPrice)
                .setParameter("maxPrice", maxPrice);
                
        var fetchQuery = entityManager.createNativeQuery(fetchSql.toString(), Product.class)
                .setParameter("keyword", searchParam)
                .setParameter("minPrice", minPrice)
                .setParameter("maxPrice", maxPrice)
                .setParameter("limit", size)
                .setParameter("offset", page * size);

        if (brandId != null && !brandId.isEmpty()) {
            countQuery.setParameter("brandIds", brandId);
            fetchQuery.setParameter("brandIds", brandId);
        }

        Number total = (Number) countQuery.getSingleResult();
        List<Product> content = fetchQuery.getResultList();

        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return new PageImpl<>(content, PageRequest.of(page, size, sort), total.longValue());
    }

    @Transactional
    public Product creatProduct(Product product) {
        if (existsByName(product.getName())) {
            throw new DuplicateResourceException("Sản phẩm với tên '" + product.getName() + "' đã tồn tại!");
        }

        if (product.getSku() != null && !product.getSku().trim().isEmpty()) {
            if (existsBySku(product.getSku().trim())) {
                throw new DuplicateResourceException("Sku '" + product.getSku() + "' đã tồn tại!");
            }
        }

        if (product.getPrice() == null || product.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Giá sản phẩm phải lớn hơn 0");
        }
        if (product.getStock() == null || product.getStock() < 0) {
            throw new IllegalArgumentException("Số lượng tồn kho không thể âm!");
        }
        if (product.getName() == null || product.getName().isEmpty()) {
            throw new IllegalArgumentException("Tên sản phẩm không để trống!");
        }

        String insertSql = "INSERT INTO products (name, price, stock, description, img_url, brand_id, sku, deleted) " +
                           "VALUES (:name, :price, :stock, :description, :imgUrl, :brandId, :sku, false)";
                           
        entityManager.createNativeQuery(insertSql)
                .setParameter("name", product.getName())
                .setParameter("price", product.getPrice())
                .setParameter("stock", product.getStock())
                .setParameter("description", product.getDescription())
                .setParameter("imgUrl", product.getImgUrl())
                .setParameter("brandId", product.getBrand() != null ? product.getBrand().getId() : null)
                .setParameter("sku", product.getSku())
                .executeUpdate();

        return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE name = :name", Product.class)
                .setParameter("name", product.getName())
                .getSingleResult();
    }

    @Transactional
    public Product updateProduct(Long id, Product newProduct) {
        Product existing = getProductById(id);

        String updateSql = "UPDATE products SET name = :name, price = :price, old_price = :oldPrice, stock = :stock, " +
                           "description = :description, img_url = :imgUrl, brand_id = :brandId, category_id = :categoryId WHERE id = :id";
                           
        entityManager.createNativeQuery(updateSql)
                .setParameter("name", newProduct.getName())
                .setParameter("price", newProduct.getPrice())
                .setParameter("oldPrice", newProduct.getOldPrice())
                .setParameter("stock", newProduct.getStock())
                .setParameter("description", newProduct.getDescription())
                .setParameter("imgUrl", newProduct.getImgUrl())
                .setParameter("brandId", newProduct.getBrand() != null ? newProduct.getBrand().getId() : null)
                .setParameter("categoryId", newProduct.getCategory() != null ? newProduct.getCategory().getId() : null)
                .setParameter("id", id)
                .executeUpdate();

        return getProductById(id);
    }

    @Transactional
    public void deleteProduct(Long id) {
        getProductById(id); // Check existence
        entityManager.createNativeQuery("UPDATE products SET deleted = true WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    @Transactional
    public Product createProductWithImage(ProductImageDTO dto) {
        if (existsByName(dto.getName())) {
            throw new DuplicateResourceException("Sản phẩm với tên '" + dto.getName() + "' đã tồn tại!");
        }

        if (dto.getSku() != null && !dto.getSku().trim().isEmpty()) {
            if (existsBySku(dto.getSku().trim())) {
                throw new DuplicateResourceException("Sku '" + dto.getSku() + "' đã tồn tại!");
            }
        }

        String imgUrl = uploadImageFile(dto.getImageFile());

        String insertSql = "INSERT INTO products (name, price, old_price, stock, description, img_url, sku, " +
                           "gpu_model, vram, memory_type, cooling_type, power_connectors, recommended_psu, dimension, brand_id, category_id, deleted) " +
                           "VALUES (:name, :price, :oldPrice, :stock, :description, :imgUrl, :sku, " +
                           ":gpuModel, :vram, :memoryType, :coolingType, :powerConnectors, :recommendedPsu, :dimension, :brandId, :categoryId, false)";

        entityManager.createNativeQuery(insertSql)
                .setParameter("name", dto.getName())
                .setParameter("price", dto.getPrice())
                .setParameter("oldPrice", dto.getOldPrice())
                .setParameter("stock", dto.getStock())
                .setParameter("description", dto.getDescription())
                .setParameter("imgUrl", imgUrl)
                .setParameter("sku", dto.getSku())
                .setParameter("gpuModel", dto.getGpuModel())
                .setParameter("vram", dto.getVram())
                .setParameter("memoryType", dto.getMemoryType())
                .setParameter("coolingType", dto.getCoolingType())
                .setParameter("powerConnectors", dto.getPowerConnectors())
                .setParameter("recommendedPsu", dto.getRecommendedPsu())
                .setParameter("dimension", dto.getDimension())
                .setParameter("brandId", dto.getBrandId())
                .setParameter("categoryId", dto.getCategoryId())
                .executeUpdate();

        return (Product) entityManager.createNativeQuery("SELECT p.*, (SELECT COUNT(r.id) FROM reviews r WHERE r.product_id = p.id) as \"reviewCount\", (SELECT COALESCE(AVG(r.rating), 0) FROM reviews r WHERE r.product_id = p.id) as \"averageRating\" FROM products p WHERE name = :name", Product.class)
                .setParameter("name", dto.getName())
                .getSingleResult();
    }

    @Transactional
    public Product updateProductWithImage(Long id, ProductImageDTO dto) {
        Product product = getProductById(id);

        if (!product.getName().equalsIgnoreCase(dto.getName()) && existsByName(dto.getName())) {
            throw new DuplicateResourceException("Sản phẩm với tên '" + dto.getName() + "' đã tồn tại!");
        }

        if (dto.getSku() != null && !dto.getSku().trim().isEmpty()) {
            if (!dto.getSku().trim().equalsIgnoreCase(product.getSku()) && existsBySku(dto.getSku().trim())) {
                throw new DuplicateResourceException("Sku '" + dto.getSku() + "' đã tồn tại!");
            }
        }

        String imgUrl = product.getImgUrl();
        if (dto.getImageFile() != null && !dto.getImageFile().isEmpty()) {
            imgUrl = uploadImageFile(dto.getImageFile());
        }

        String updateSql = "UPDATE products SET name = :name, price = :price, old_price = :oldPrice, stock = :stock, " +
                           "description = :description, sku = :sku, gpu_model = :gpuModel, vram = :vram, " +
                           "memory_type = :memoryType, cooling_type = :coolingType, power_connectors = :powerConnectors, " +
                           "recommended_psu = :recommendedPsu, dimension = :dimension, img_url = :imgUrl, " +
                           "brand_id = :brandId, category_id = :categoryId WHERE id = :id";

        entityManager.createNativeQuery(updateSql)
                .setParameter("name", dto.getName())
                .setParameter("price", dto.getPrice())
                .setParameter("oldPrice", dto.getOldPrice())
                .setParameter("stock", dto.getStock())
                .setParameter("description", dto.getDescription())
                .setParameter("sku", dto.getSku() != null ? dto.getSku() : product.getSku())
                .setParameter("gpuModel", dto.getGpuModel())
                .setParameter("vram", dto.getVram())
                .setParameter("memoryType", dto.getMemoryType())
                .setParameter("coolingType", dto.getCoolingType())
                .setParameter("powerConnectors", dto.getPowerConnectors())
                .setParameter("recommendedPsu", dto.getRecommendedPsu())
                .setParameter("dimension", dto.getDimension())
                .setParameter("imgUrl", imgUrl)
                .setParameter("brandId", dto.getBrandId())
                .setParameter("categoryId", dto.getCategoryId())
                .setParameter("id", id)
                .executeUpdate();

        return getProductById(id);
    }

    private String uploadImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;

        try {
            String uplaodDir = "uploads/products/";
            java.nio.file.Path uploadPath = java.nio.file.Paths.get(uplaodDir);

            if (!java.nio.file.Files.exists(uploadPath)) {
                java.nio.file.Files.createDirectories(uploadPath);
            }

            String originalFileName = file.getOriginalFilename();
            String fileName = System.currentTimeMillis() + "_" + originalFileName;

            java.nio.file.Path filePath = uploadPath.resolve(fileName);
            file.transferTo(filePath.toAbsolutePath().toFile());

            return "/uploads/products/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Không thể upload file ảnh: " + e.getMessage(), e);
        }
    }
}
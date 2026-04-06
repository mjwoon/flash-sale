package com.flashsale.backend.repository;

import com.flashsale.backend.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT p FROM Product p WHERE p.isDeleted = false AND (:cursor IS NULL OR p.id < :cursor) ORDER BY p.id DESC")
    List<Product> findActiveProductsWithCursor(@Param("cursor") Long cursor, Pageable pageable);

    Optional<Product> findByIdAndIsDeletedFalse(Long id);
}

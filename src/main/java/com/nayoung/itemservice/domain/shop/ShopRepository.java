package com.nayoung.itemservice.domain.shop;

import com.nayoung.itemservice.domain.shop.location.LocationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    List<Shop> findAllByLocationCode(LocationCode locationCode);
    Optional<Shop> findByName(String name);
    Optional<Shop> findByLocationCodeAndName(LocationCode locationCode, String name);
    Long countByName(String name);
}

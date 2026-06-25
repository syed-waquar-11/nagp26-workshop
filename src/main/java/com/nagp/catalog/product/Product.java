package com.nagp.catalog.product;

import java.math.BigDecimal;

public record Product(
        long id,
        String name,
        String category,
        BigDecimal price,
        int stockQuantity
) {
}

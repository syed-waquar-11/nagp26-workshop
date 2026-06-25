package com.nagp.catalog.product;

/**
 * Thrown when a product lookup by ID finds no matching record.
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long id) {
        super("Product not found with id: " + id);
    }
}


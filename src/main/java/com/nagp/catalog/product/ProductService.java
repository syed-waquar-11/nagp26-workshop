package com.nagp.catalog.product;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Product> findAllProducts() {
        return productRepository.findAll();
    }

    /**
     * Fetch a single product by ID.
     *
     * @param id the product identifier
     * @return the matching product
     * @throws ProductNotFoundException if no product exists with the given ID
     */
    public Product findProductById(long id) {
        return productRepository.findById(id);
    }
}

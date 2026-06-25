package com.nagp.catalog.product;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public Map<String, Object> products() {
        List<Product> products = productService.findAllProducts();
        return Map.of(
                "timestamp", Instant.now(),
                "count", products.size(),
                "records", products);
    }

    /**
     * Fetch a single product by its ID.
     *
     * @param id the product identifier (must be a positive number)
     * @return the matching product
     */
    @GetMapping("/products/{id}")
    public Map<String, Object> product(@PathVariable long id) {
        if (id < 1) {
            throw new IllegalArgumentException("Product id must be a positive number");
        }
        Product product = productService.findProductById(id);
        return Map.of(
                "timestamp", Instant.now(),
                "record", product);
    }
}

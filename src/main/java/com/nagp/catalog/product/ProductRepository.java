package com.nagp.catalog.product;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private final JdbcClient jdbcClient;

    public ProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Product> findAll() {
        return jdbcClient.sql("""
                        select id, name, category, price, stock_quantity
                        from products
                        order by id
                        """)
                .query((rs, rowNum) -> new Product(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getBigDecimal("price"),
                        rs.getInt("stock_quantity")))
                .list();
    }

    /**
     * Fetch a single product by its ID.
     *
     * @param id the product identifier
     * @return the matching product
     * @throws ProductNotFoundException if no product exists with the given ID
     */
    public Product findById(long id) {
        try {
            return jdbcClient.sql("""
                            select id, name, category, price, stock_quantity
                            from products
                            where id = :id
                            """)
                    .param("id", id)
                    .query((rs, rowNum) -> new Product(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getBigDecimal("price"),
                            rs.getInt("stock_quantity")))
                    .single();
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ProductNotFoundException(id);
        }
    }
}

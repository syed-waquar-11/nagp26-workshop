package com.nagp.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "debug=false",
        "logging.level.org.springframework=INFO",
        "spring.datasource.url=jdbc:h2:mem:catalog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "spring.sql.init.data-locations=classpath:data.sql"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductControllerTest {

    @Autowired
    private ProductController productController;

    @Test
    void returnsAllProductsFromDatabase() {
        Map<String, Object> response = productController.products();

        assertThat(response.get("count")).isEqualTo(7);
        assertThat((Iterable<?>) response.get("records"))
                .extracting("name")
                .contains("Wireless Mouse", "Laptop Stand");
    }

    @Test
    void returnsSingleProductById() {
        Map<String, Object> response = productController.product(1);

        assertThat(response).containsKey("record");
        Product product = (Product) response.get("record");
        assertThat(product.id()).isEqualTo(1);
        assertThat(product.name()).isEqualTo("Wireless Mouse");
        assertThat(product.category()).isEqualTo("Accessories");
    }

    @Test
    void throwsNotFoundForMissingProduct() {
        assertThatThrownBy(() -> productController.product(999))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void throwsBadRequestForInvalidId() {
        assertThatThrownBy(() -> productController.product(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive number");
    }
}

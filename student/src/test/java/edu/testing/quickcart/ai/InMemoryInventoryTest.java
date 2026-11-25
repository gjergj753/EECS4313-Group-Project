// InMemoryInventoryTest.java
package edu.testing.quickcart.ai;

import edu.testing.quickcart.infra.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryInventoryTest {

    private InMemoryInventory inventory;

    @BeforeEach
    void setUp() {
        Map<String, Integer> initialStock = new HashMap<>();
        initialStock.put("P001", 10);
        initialStock.put("P002", 5);

        inventory = new InMemoryInventory(initialStock);
    }

    @Test
    void available_shouldReturnInitialStockForExistingProduct() {
        assertEquals(10, inventory.available("P001"));
        assertEquals(5, inventory.available("P002"));
    }

    @Test
    void available_shouldReturnZeroForUnknownProduct() {
        assertEquals(0, inventory.available("UNKNOWN"));
    }

    @Test
    void reserve_shouldDecreaseStockByRequestedQuantity() {
        inventory.reserve("P001", 3);

        assertEquals(7, inventory.available("P001"));
    }

    @Test
    void reserve_shouldAllowReservingExactlyAvailableQuantity() {
        inventory.reserve("P001", 10);

        assertEquals(0, inventory.available("P001"));
    }

    @Test
    void reserve_shouldThrowExceptionWhenQuantityExceedsAvailableStock() {
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.reserve("P001", 11)
        );

        assertEquals(10, inventory.available("P001"));
    }

    @Test
    void reserve_shouldThrowExceptionWhenQuantityIsZero() {
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.reserve("P001", 0)
        );

        assertEquals(10, inventory.available("P001"));
    }

    @Test
    void reserve_shouldThrowExceptionWhenQuantityIsNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.reserve("P001", -1)
        );

        assertEquals(10, inventory.available("P001"));
    }

    @Test
    void reserve_shouldThrowExceptionWhenProductDoesNotExist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.reserve("UNKNOWN", 1)
        );

        assertEquals(0, inventory.available("UNKNOWN"));
    }

    @Test
    void reserve_shouldNotChangeStockWhenReservationFails() {
        assertThrows(
                IllegalArgumentException.class,
                () -> inventory.reserve("P001", 15)
        );

        assertEquals(10, inventory.available("P001"));
    }

    @Test
    void reserve_shouldSupportMultipleSuccessfulReservations() {
        inventory.reserve("P001", 3);
        inventory.reserve("P001", 2);

        assertEquals(5, inventory.available("P001"));
    }

    @Test
    void reserve_shouldMaintainIndependentStockForDifferentProducts() {
        inventory.reserve("P001", 4);

        assertEquals(6, inventory.available("P001"));
        assertEquals(5, inventory.available("P002"));
    }

    @Test
    void constructor_shouldCopyInitialStockWithoutChangingOriginalMap() {
        Map<String, Integer> initialStock = new HashMap<>();
        initialStock.put("P003", 20);

        InMemoryInventory testInventory = new InMemoryInventory(initialStock);

        testInventory.reserve("P003", 5);

        assertEquals(15, testInventory.available("P003"));
        assertEquals(20, initialStock.get("P003"));
    }
}
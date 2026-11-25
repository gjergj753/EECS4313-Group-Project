// OrderServiceTest.java
package edu.testing.quickcart.ai;

import edu.testing.quickcart.model.CartItem;
import edu.testing.quickcart.model.OrderQuote;
import edu.testing.quickcart.model.Product;
import edu.testing.quickcart.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private PricingService pricing;

    @Mock
    private InventoryService inventory;

    @Mock
    private PaymentGateway payment;

    @Mock
    private CartItem item;

    @Mock
    private Product product;

    @Mock
    private OrderQuote quote;

    private OrderService orderService;

    private final String customerId = "C001";
    private final String productId = "P001";
    private final BigDecimal total = new BigDecimal("108.99");
    private final LocalDate orderDate = LocalDate.of(2026, 11, 24);

    @BeforeEach
    void setUp() {
        orderService = new OrderService(pricing, inventory, payment);

        when(item.product()).thenReturn(product);
        when(product.id()).thenReturn(productId);
        when(item.quantity()).thenReturn(3);

        when(pricing.quote(anyList(), any(), any(), anyString(), any()))
                .thenReturn(quote);

        when(quote.total()).thenReturn(total);
    }

    @Tag("defect")
    @Test
    void placeOrder_shouldAcceptOrderWhenInventoryIsExactlyAvailable() {
        when(inventory.available(productId)).thenReturn(3);
        when(payment.authorize(customerId, total)).thenReturn(true);

        OrderResult result = orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        assertEquals(OrderResult.Status.ACCEPTED, result.status());
        assertSame(quote, result.quote());

        verify(payment).authorize(customerId, total);
        verify(inventory).reserve(productId, 3);
    }

    @Test
    void placeOrder_shouldAcceptOrderWhenInventoryExceedsQuantity() {
        when(inventory.available(productId)).thenReturn(10);
        when(payment.authorize(customerId, total)).thenReturn(true);

        OrderResult result = orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        assertEquals(OrderResult.Status.ACCEPTED, result.status());

        verify(payment).authorize(customerId, total);
        verify(inventory).reserve(productId, 3);
    }

    @Test
    void placeOrder_shouldReturnOutOfStockWhenInventoryIsInsufficient() {
        lenient().when(quote.total()).thenReturn(total);
        when(inventory.available(productId)).thenReturn(2);

        OrderResult result = orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        assertEquals(OrderResult.Status.OUT_OF_STOCK, result.status());
        assertEquals("Insufficient stock", result.message());

        verify(payment, never()).authorize(anyString(), any());
        verify(inventory, never()).reserve(anyString(), anyInt());
    }

    @Test
    void placeOrder_shouldNotReserveAnyItemsWhenOneItemIsOutOfStock() {
        CartItem secondItem = mock(CartItem.class);
        Product secondProduct = mock(Product.class);

        lenient().when(quote.total()).thenReturn(total);
        when(secondItem.product()).thenReturn(secondProduct);
        when(secondProduct.id()).thenReturn("P002");
        when(secondItem.quantity()).thenReturn(5);

        when(inventory.available(productId)).thenReturn(10);
        when(inventory.available("P002")).thenReturn(4);

        OrderResult result = orderService.placeOrder(
                customerId,
                List.of(item, secondItem),
                null,
                null,
                "ON",
                orderDate
        );

        assertEquals(OrderResult.Status.OUT_OF_STOCK, result.status());

        verify(inventory, never()).reserve(anyString(), anyInt());
        verify(payment, never()).authorize(anyString(), any());
    }

    @Tag("defect")
    @Test
    void placeOrder_shouldReturnPaymentDeclinedWhenAuthorizationFails() {
        when(inventory.available(productId)).thenReturn(10);
        when(payment.authorize(customerId, total)).thenReturn(false);

        OrderResult result = orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        assertEquals(OrderResult.Status.PAYMENT_DECLINED, result.status());
        assertEquals("Payment declined", result.message());

        verify(payment).authorize(customerId, total);
        verify(inventory, never()).reserve(anyString(), anyInt());
    }

    @Tag("defect")
    @Test
    void placeOrder_shouldReserveInventoryOnlyAfterPaymentSucceeds() {
        when(inventory.available(productId)).thenReturn(10);
        when(payment.authorize(customerId, total)).thenReturn(true);

        orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        InOrder inOrder = inOrder(payment, inventory);

        inOrder.verify(payment).authorize(customerId, total);
        inOrder.verify(inventory).reserve(productId, 3);
    }

    @Test
    void placeOrder_shouldAuthorizePaymentUsingQuotedTotal() {
        BigDecimal expectedTotal = new BigDecimal("250.75");

        when(quote.total()).thenReturn(expectedTotal);
        when(inventory.available(productId)).thenReturn(10);
        when(payment.authorize(customerId, expectedTotal)).thenReturn(true);

        orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        verify(payment).authorize(customerId, expectedTotal);
    }

    @Test
    void placeOrder_shouldCallPricingServiceBeforeCheckingInventory() {
        when(inventory.available(productId)).thenReturn(10);
        when(payment.authorize(customerId, total)).thenReturn(true);

        orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        InOrder inOrder = inOrder(pricing, inventory, payment);

        inOrder.verify(pricing).quote(
                List.of(item), null, null, "ON", orderDate
        );
        inOrder.verify(inventory).available(productId);
        inOrder.verify(payment).authorize(customerId, total);
    }

    @Tag("defect")
    @Test
    void placeOrder_shouldNotReserveInventoryWhenPaymentIsDeclined() {
        when(inventory.available(productId)).thenReturn(3);
        when(payment.authorize(customerId, total)).thenReturn(false);

        OrderResult result = orderService.placeOrder(
                customerId, List.of(item), null, null, "ON", orderDate
        );

        assertEquals(OrderResult.Status.PAYMENT_DECLINED, result.status());

        verify(inventory).available(productId);
        verify(inventory, never()).reserve(anyString(), anyInt());
    }

    @Tag("defect")
    @Test
    void placeOrder_shouldReserveAllItemsAfterSuccessfulPayment() {
        CartItem secondItem = mock(CartItem.class);
        Product secondProduct = mock(Product.class);

        when(secondItem.product()).thenReturn(secondProduct);
        when(secondProduct.id()).thenReturn("P002");
        when(secondItem.quantity()).thenReturn(2);

        when(inventory.available(productId)).thenReturn(10);
        when(inventory.available("P002")).thenReturn(5);
        when(payment.authorize(customerId, total)).thenReturn(true);

        OrderResult result = orderService.placeOrder(
                customerId,
                List.of(item, secondItem),
                null,
                null,
                "ON",
                orderDate
        );

        assertEquals(OrderResult.Status.ACCEPTED, result.status());

        InOrder inOrder = inOrder(payment, inventory);

        inOrder.verify(payment).authorize(customerId, total);
        inOrder.verify(inventory).reserve(productId, 3);
        inOrder.verify(inventory).reserve("P002", 2);
    }
}

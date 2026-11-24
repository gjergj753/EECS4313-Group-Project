package edu.testing.quickcart.service;

import edu.testing.quickcart.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PricingService.quote, derived from PROJECT_SPECIFICATION.md (Pricing 1-8, Input constraints).
 * Every expected value was calculated by hand from the spec, not taken from the code.
 *
 * Do not change their expected values to ma * Tests marked "EXPECTED TO FAIL" are defect evidence: they encode the spec and the code disagrees.ke them pass.
 */
class PricingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final LocalDate COUPON_START = LocalDate.of(2026, 11, 10);
    private static final LocalDate COUPON_END = LocalDate.of(2026, 11, 20);
    private static final Coupon SAVE10 = new Coupon("SAVE10", new BigDecimal("10.00"), COUPON_START, COUPON_END);

    private PricingService pricing;

    @BeforeEach
    void setUp() {
        pricing = new PricingService();
    }

    // ---------- helpers ----------

    private static List<CartItem> cart(String unitPrice, int quantity) {
        return List.of(new CartItem(new Product("P1", "Item", new BigDecimal(unitPrice)), quantity));
    }

    private OrderQuote quote(List<CartItem> items, CustomerTier tier, Coupon coupon, String province, LocalDate date) {
        return pricing.quote(items, tier, coupon, province, date);
    }

    /** Base choice: STANDARD, no coupon, AB (lowest rate), today. */
    private OrderQuote baseQuote(List<CartItem> items) {
        return quote(items, CustomerTier.STANDARD, null, "AB", TODAY);
    }

    /** Money compared by value (compareTo), so 95.0 and 95.00 are equal. */
    private static void assertMoney(String expected, BigDecimal actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> what + ": expected " + expected + " but was " + actual);
    }

    /** Strong oracle: all four quote fields, plus the spec's total rule (Pricing 8). */
    private static void assertQuote(OrderQuote q, String merchandise, String shipping, String tax, String total) {
        assertAll(
                () -> assertMoney(merchandise, q.merchandise(), "merchandise"),
                () -> assertMoney(shipping, q.shipping(), "shipping"),
                () -> assertMoney(tax, q.tax(), "tax"),
                () -> assertMoney(total, q.total(), "total"),
                () -> assertMoney(q.merchandise().add(q.shipping()).add(q.tax()).toPlainString(), q.total(),
                        "total = merchandise + shipping + tax")
        );
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 2 - bulk discount (R1)")
    class BulkDiscount {

        // Boundary: 9 (below), 10 (on), 11 (above). Unit price 10.00.
        // EXPECTED TO FAIL at quantity 10: spec says "10 or more", code uses > 10.
        @ParameterizedTest(name = "quantity {0} at $10.00 -> merchandise {1}")
        @Tag("defect")
        @CsvSource({
                "1,  10.00",    // nominal, no discount
                "9,  90.00",    // just below the threshold
                "10, 95.00",    // ON the threshold: 100.00 x 0.95  <-- R1 defect
                "11, 104.50"    // just above: 110.00 x 0.95
        })
        void bulkDiscountFromTenUnits(int quantity, String expectedMerchandise) {
            OrderQuote q = baseQuote(cart("10.00", quantity));
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        @Test
        @DisplayName("Bulk discount applies per line, not to the whole cart")
        void bulkDiscountIsPerLine() {
            List<CartItem> items = List.of(
                    new CartItem(new Product("P1", "Bulk", new BigDecimal("10.00")), 11),  // 110.00 x 0.95 = 104.50
                    new CartItem(new Product("P2", "Single", new BigDecimal("5.00")), 1)); // 5.00, no discount
            assertMoney("109.50", baseQuote(items).merchandise(), "merchandise");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 3 - GOLD discount")
    class GoldDiscount {

        @ParameterizedTest(name = "{0} on $80.00 -> merchandise {1}")
        @CsvSource({
                "STANDARD, 80.00",
                "GOLD,     72.00"   // 80.00 x 0.90
        })
        void goldGetsTenPercentOff(CustomerTier tier, String expectedMerchandise) {
            OrderQuote q = quote(cart("80.00", 1), tier, null, "AB", TODAY);
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        @Test
        @DisplayName("GOLD discount is applied after the bulk discount")
        void goldAppliesAfterBulk() {
            // 11 x 10.00 = 110.00 -> bulk 104.50 -> GOLD 94.05
            OrderQuote q = quote(cart("10.00", 11), CustomerTier.GOLD, null, "AB", TODAY);
            assertMoney("94.05", q.merchandise(), "merchandise");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 4 - coupon minimum and discount order (R3)")
    class CouponMinimum {

        // Boundary around $50.00 with an active $10 coupon (order date inside the range).
        @ParameterizedTest(name = "merchandise {0} -> after coupon {1}")
        @CsvSource({
                "49.99, 49.99",   // below minimum: coupon not applied
                "50.00, 40.00",   // on the minimum: applied
                "50.01, 40.01"    // above: applied
        })
        void couponNeedsAtLeastFiftyDollars(String price, String expectedMerchandise) {
            OrderQuote q = quote(cart(price, 1), CustomerTier.STANDARD, SAVE10, "AB", TODAY);
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        @Test
        @DisplayName("GOLD can push merchandise under $50, so the coupon no longer applies")
        void goldDiscountCanDisqualifyCoupon() {
            // 55.00 x 0.90 = 49.50 < 50.00 -> no coupon
            OrderQuote q = quote(cart("55.00", 1), CustomerTier.GOLD, SAVE10, "AB", TODAY);
            assertMoney("49.50", q.merchandise(), "merchandise");
        }

        @Test
        @DisplayName("Order of discounts: bulk, then GOLD, then coupon")
        void discountOrderIsBulkGoldCoupon() {
            // 11 x 6.00 = 66.00 -> bulk 62.70 -> GOLD 56.43 -> coupon 46.43
            OrderQuote q = quote(cart("6.00", 11), CustomerTier.GOLD, SAVE10, "AB", TODAY);
            assertMoney("46.43", q.merchandise(), "merchandise");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 4 - coupon validity dates (R2)")
    class CouponDates {

        // Coupon valid 2026-11-10 .. 2026-11-20 INCLUSIVE. Merchandise 80.00, coupon $10.
        // EXPECTED TO FAIL on the start and end dates: code excludes both ends.
        @ParameterizedTest(name = "order on {0} -> merchandise {1}")
        @Tag("defect")
        @CsvSource({
                "2026-11-09, 80.00",   // day before start: not active
                "2026-11-10, 70.00",   // ON start date      <-- R2 defect
                "2026-11-11, 70.00",   // start + 1
                "2026-11-19, 70.00",   // end - 1
                "2026-11-20, 70.00",   // ON end date        <-- R2 defect
                "2026-11-21, 80.00"    // day after end: not active
        })
        void couponValidityIncludesBothEnds(LocalDate orderDate, String expectedMerchandise) {
            OrderQuote q = quote(cart("80.00", 1), CustomerTier.STANDARD, SAVE10, "AB", orderDate);
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        // Coupon valid 2026-11-10 .. 2026-11-20. Outside those dates it must not apply.
        // Passes on the current code (no tag). Kills the isActive() -> return true mutant.
        @ParameterizedTest(name = "order on {0} (outside dates) -> merchandise {1}")
        @CsvSource({
                "2026-11-09, 80.00",   // day before start
                "2026-11-21, 80.00"    // day after end
        })
        void couponOutsideDatesGivesNoDiscount(LocalDate orderDate, String expectedMerchandise) {
            OrderQuote q = quote(cart("80.00", 1), CustomerTier.STANDARD, SAVE10, "AB", orderDate);
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        // Single-day coupon (start = end). EXPECTED TO FAIL on the day itself (same root cause as R2).
        @ParameterizedTest(name = "single-day coupon, order on {0} -> merchandise {1}")
        @Tag("defect")
        @CsvSource({
                "2026-11-14, 80.00",
                "2026-11-15, 70.00",   // the only valid day  <-- R2 defect
                "2026-11-16, 80.00"
        })
        void singleDayCouponIsValidOnItsDay(LocalDate orderDate, String expectedMerchandise) {
            Coupon oneDay = new Coupon("TODAY", new BigDecimal("10.00"), TODAY, TODAY);
            OrderQuote q = quote(cart("80.00", 1), CustomerTier.STANDARD, oneDay, "AB", orderDate);
            assertMoney(expectedMerchandise, q.merchandise(), "merchandise");
        }

        @Test
        @DisplayName("No coupon means no discount")
        void noCoupon() {
            assertMoney("80.00", baseQuote(cart("80.00", 1)).merchandise(), "merchandise");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 5 - merchandise never negative (R4)")
    class NeverNegative {

        // Merchandise 60.00 (above the $50 minimum). Coupon smaller / equal / larger.
        @ParameterizedTest(name = "coupon {0} on $60.00 -> merchandise {1}")
        @CsvSource({
                "59.99, 0.01",
                "60.00, 0.00",
                "60.01, 0.00"   // would be -0.01, clamped to 0
        })
        void couponCannotMakeMerchandiseNegative(String amountOff, String expectedMerchandise) {
            Coupon big = new Coupon("BIG", new BigDecimal(amountOff), COUPON_START, COUPON_END);
            OrderQuote q = quote(cart("60.00", 1), CustomerTier.STANDARD, big, "AB", TODAY);
            assertAll(
                    () -> assertMoney(expectedMerchandise, q.merchandise(), "merchandise"),
                    () -> assertTrue(q.tax().signum() >= 0, "tax never negative"),
                    () -> assertTrue(q.total().signum() >= 0, "total never negative"));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 6 - shipping (R5)")
    class Shipping {

        // EXPECTED TO FAIL at 100.00: spec says free at "$100.00 or more", code uses > 100.
        @ParameterizedTest(name = "merchandise {0} -> shipping {1}")
        @Tag("defect")
        @CsvSource({
                "99.99,  8.99",
                "100.00, 0.00",   // ON the threshold  <-- R5 defect
                "100.01, 0.00"
        })
        void freeShippingFromOneHundredDollars(String price, String expectedShipping) {
            assertMoney(expectedShipping, baseQuote(cart(price, 1)).shipping(), "shipping");
        }

        @Test
        @DisplayName("Assumption A1: threshold compares unrounded merchandise (99.995 < 100.00 -> shipping charged)")
        void thresholdUsesUnroundedMerchandise() {
            // Spec does not say merchandise is rounded before the comparison (assumption A1).
            assertMoney("8.99", baseQuote(cart("99.995", 1)).shipping(), "shipping");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 7-8 - tax and total (R6)")
    class Tax {

        // EXPECTED TO FAIL: spec taxes merchandise + shipping. 50.00 + 8.99 = 58.99 x 0.13 = 7.6687 -> 7.67.
        // Code taxes merchandise only (6.50).
        @Test
        @Tag("defect")
        @DisplayName("Tax is charged on merchandise plus shipping (R6)")
        void taxIncludesShipping() {
            OrderQuote q = quote(cart("50.00", 1), CustomerTier.STANDARD, null, "ON", TODAY);
            assertQuote(q, "50.00", "8.99", "7.67", "66.66");
        }

        @Test
        @DisplayName("With free shipping the tax base is merchandise only")
        void taxWithFreeShipping() {
            OrderQuote q = quote(cart("200.00", 1), CustomerTier.STANDARD, null, "ON", TODAY);
            assertQuote(q, "200.00", "0.00", "26.00", "226.00");
        }

        // Each supported province, with free shipping so only the rate varies.
        @ParameterizedTest(name = "{0}: tax {1}, total {2}")
        @CsvSource({
                "ON, 26.00, 226.00",   // 13%
                "BC, 24.00, 224.00",   // 12%
                "AB, 10.00, 210.00"    //  5%
        })
        void eachProvinceUsesItsRate(String province, String expectedTax, String expectedTotal) {
            OrderQuote q = quote(cart("200.00", 1), CustomerTier.STANDARD, null, province, TODAY);
            assertQuote(q, "200.00", "0.00", expectedTax, expectedTotal);
        }

        // HALF_UP rounding on the third decimal (AB 5%, free shipping).
        @ParameterizedTest(name = "merchandise {0} -> raw tax {1} -> rounded {2}")
        @CsvSource({
                "100.08, 5.004, 5.00",   // third decimal < 5: round down
                "100.10, 5.005, 5.01",   // third decimal = 5: HALF_UP rounds up
                "100.12, 5.006, 5.01"    // third decimal > 5: round up
        })
        void taxIsRoundedHalfUp(String price, String rawTax, String expectedTax) {
            assertMoney(expectedTax, baseQuote(cart(price, 1)).tax(), "tax");
        }

        @ParameterizedTest(name = "province ''{0}'' is rejected")
        @ValueSource(strings = {"QC", "", "XX"})
        @DisplayName("Unsupported provinces are rejected")
        void unsupportedProvinceIsRejected(String province) {
            assertThrows(IllegalArgumentException.class,
                    () -> quote(cart("10.00", 1), CustomerTier.STANDARD, null, province, TODAY));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Pricing 1 and input constraints - cart (R13)")
    class Cart {

        @Test
        @DisplayName("Line subtotal is unit price x quantity, summed over lines")
        void multipleLinesAreSummed() {
            List<CartItem> items = List.of(
                    new CartItem(new Product("P1", "A", new BigDecimal("10.00")), 2),   // 20.00
                    new CartItem(new Product("P2", "B", new BigDecimal("5.00")), 3));   // 15.00
            assertMoney("35.00", baseQuote(items).merchandise(), "merchandise");
        }

        @Test
        @DisplayName("Empty cart is rejected")
        void emptyCartIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> baseQuote(List.of()));
        }

        @Test
        @DisplayName("Null arguments are rejected")
        void nullArgumentsAreRejected() {
            List<CartItem> items = cart("10.00", 1);
            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> quote(null, CustomerTier.STANDARD, null, "ON", TODAY)),
                    () -> assertThrows(NullPointerException.class,
                            () -> quote(items, null, null, "ON", TODAY)),
                    () -> assertThrows(NullPointerException.class,
                            () -> quote(items, CustomerTier.STANDARD, null, null, TODAY)),
                    () -> assertThrows(NullPointerException.class,
                            () -> quote(items, CustomerTier.STANDARD, null, "ON", null)));
        }
    }
}

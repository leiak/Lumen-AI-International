package com.lumen.order.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO 的 jakarta bean validation 约束回归测试 (Iteration 4.6 Task 3).
 *
 * <p><b>为什么这道防线必须有</b>: DTO 上的 {@code @Size} / {@code @NotBlank} / {@code @PositiveOrZero}
 * 是「超长字符串在 controller 口被挡成 400」的唯一保障 —— 少了它, 超长值会一路走到 MySQL 报 1406 → 500。
 * 而 {@code @Valid} 级联漏标则会让嵌套 record 的约束**完全不生效** (jakarta 不会自动级联),
 * 这类假绿只能靠本测试兜住。
 *
 * <p><b>不用 {@code @SpringBootTest}</b> —— 直接用 Hibernate Validator 8.0.1 的
 * {@link Validation#buildDefaultValidatorFactory()} 起一个纯内存校验器, 不拉 Spring 上下文。
 */
@DisplayName("委托单 DTO 的 jakarta 约束")
class OrderDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    // ------------------------------------------------------------ 工具方法

    /** 造一个「全部合法」的货物明细行。 */
    private CargoRequest cargo() {
        return new CargoRequest("钢材", "STEEL", "7208", "CARTON", 10d, "CTN",
                1000d, 900d, 12.5d, 100d, 80d, 50d, true,
                5000d, "CNY", "ACME", "A1", false, null, null, null);
    }

    /** 造一个「全部合法」的服务项行。 */
    private ServiceItemRequest serviceItem() {
        return new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                1d, 3000d, 3000d, "CNY", null, 2500d, false, null);
    }

    /** 造一个「全部合法」的建单请求 (含一货一服务项)。 */
    private CreateOrderRequest request() {
        return new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D",
                "上海洋山港", "洛杉矶港",
                null, null, null,
                1, "40HQ",
                "MSKU1234567", "五金配件",
                false, null,
                "CNY", "测试委托单",
                List.of(cargo()), List.of(serviceItem()));
    }

    /** 断言某条约束确实报错了 —— 按属性路径定位, 避免「随便哪个约束报错就算过」。 */
    private void assertViolation(CreateOrderRequest req, String propertyPath) {
        assertThat(validator.validate(req))
                .as("属性路径 %s 上应有一条违规", propertyPath)
                .anyMatch(v -> v.getPropertyPath().toString().startsWith(propertyPath));
    }

    // ------------------------------------------------------------ 嵌套级联

    @Test
    @DisplayName("级联: cargo 里的 cargoNameCn 超 128 → 校验失败 (@Valid 生效)")
    void nestedCargoNameTooLong_fails() {
        CargoRequest tooLong = new CargoRequest("钢".repeat(129), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(tooLong), List.of(serviceItem()));

        assertViolation(req, "cargo[0].cargoNameCn");
    }

    @Test
    @DisplayName("级联: serviceItems 里的 feeType 空串 → 校验失败 (@NotBlank 生效)")
    void nestedBlankFeeType_fails() {
        ServiceItemRequest blank = new ServiceItemRequest("", "海运费", "FREIGHT", "PER_CONTAINER",
                1d, 3000d, 3000d, "CNY", null, 2500d, false, null);
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(cargo()), List.of(blank));

        assertViolation(req, "serviceItems[0].feeType");
    }

    @Test
    @DisplayName("级联: serviceItems 里的 feeType 超 32 → 校验失败 (@Size 生效)")
    void nestedFeeTypeTooLong_fails() {
        ServiceItemRequest tooLong = new ServiceItemRequest("F".repeat(33), "海运费", "FREIGHT", "PER_CONTAINER",
                1d, 3000d, 3000d, "CNY", null, 2500d, false, null);
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(cargo()), List.of(tooLong));

        assertViolation(req, "serviceItems[0].feeType");
    }

    // ------------------------------------------------------------ CreateOrderRequest 自身

    @Test
    @DisplayName("businessType 空串 → 校验失败")
    void blankBusinessType_fails() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(cargo()), List.of(serviceItem()));

        assertViolation(req, "businessType");
    }

    @Test
    @DisplayName("businessType 超 32 → 校验失败")
    void businessTypeTooLong_fails() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "B".repeat(33), "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(cargo()), List.of(serviceItem()));

        assertViolation(req, "businessType");
    }

    @Test
    @DisplayName("settlementCurrency 超 3 (列是 CHAR(3)) → 校验失败")
    void settlementCurrencyTooLong_fails() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNYX", null,
                List.of(cargo()), List.of(serviceItem()));

        assertViolation(req, "settlementCurrency");
    }

    @Test
    @DisplayName("customerId 为 null → 校验失败")
    void nullCustomerId_fails() {
        CreateOrderRequest req = new CreateOrderRequest(
                null, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, null, null, "CNY", null,
                List.of(cargo()), List.of(serviceItem()));

        assertViolation(req, "customerId");
    }

    @Test
    @DisplayName("全部合法的请求 → 无违规")
    void validRequest_passes() {
        assertThat(validator.validate(request())).isEmpty();
    }

    // ------------------------------------------------------------ CargoRequest

    @Test
    @DisplayName("pkgQty = -1 → 校验失败 (负数)")
    void negativePkgQty_fails() {
        CargoRequest req = new CargoRequest("钢材", null, null, null, -1d, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("pkgQty"));
    }

    @Test
    @DisplayName("pkgQty = 0 → 通过 (件数未知, 散货拼箱 LCL 常见; 与 DDL NOT NULL DEFAULT 0 一致)")
    void zeroPkgQty_passes() {
        CargoRequest req = new CargoRequest("钢材", null, null, null, 0d, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("pkgQty = null → 通过 (不传 = 件数未知)")
    void nullPkgQty_passes() {
        CargoRequest req = new CargoRequest("钢材", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("cargoNameCn 超 128 → 校验失败 (列是 VARCHAR(128))")
    void cargoNameTooLong_fails() {
        CargoRequest req = new CargoRequest("钢".repeat(129), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("cargoNameCn"));
    }

    // ------------------------------------------------------------ ServiceItemRequest

    @Test
    @DisplayName("服务项 qty / unitPrice / amount 为负 → 校验失败")
    void negativeServiceItemNumbers_fail() {
        ServiceItemRequest req = new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                -1d, -2d, -3d, "CNY", null, -4d, false, null);

        Set<String> paths = validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(paths).contains("qty", "unitPrice", "amount", "costAmount");
    }

    @Test
    @DisplayName("服务项 calcBasis 空串 → 校验失败 (列是 NOT NULL)")
    void blankCalcBasis_fails() {
        ServiceItemRequest req = new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "",
                1d, 3000d, 3000d, "CNY", null, 2500d, false, null);

        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("calcBasis"));
    }

    @Test
    @DisplayName("服务项 costAmount = null → 通过 (可空列)")
    void nullCostAmount_passes() {
        ServiceItemRequest req = new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                1d, 3000d, 3000d, "CNY", null, null, false, null);

        assertThat(validator.validate(req)).isEmpty();
    }
}

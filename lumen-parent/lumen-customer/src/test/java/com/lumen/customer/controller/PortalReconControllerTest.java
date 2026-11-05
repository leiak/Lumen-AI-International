package com.lumen.customer.controller;

import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4.5C-2 — {@code POST /dispute} 的<b>形状校验</b>单测。
 *
 * <p>这些校验是纯函数: 不碰任何 bean, 不读数据库。之所以能脱开 Spring 上下文直接测,
 * 是因为校验被<b>刻意排在取身份之前</b> (见 {@code PortalReconController#dispute}
 * 的 javadoc) —— 非法入参在碰到 {@code PortalIdentity} 之前就抛了。
 *
 * <p>所以这里直接 {@code new PortalReconController(null)}: {@code @RequiredArgsConstructor}
 * 允许传 null, 而 null 的 service 永远走不到 (走到就说明校验漏了, 那才是本测试
 * 要抓的 bug —— NPE 而不是 CustomerException)。
 *
 * <p><b>不测"合法 body 会走到 service"</b>: 那需要 Spring 上下文或 mock, 属于
 * Task 6 IT 的范围。本测试只锁"非法入参 → 正确的门户错误码"。
 */
@DisplayName("PortalReconController — dispute 形状校验")
class PortalReconControllerTest {

    /**
     * 无 Spring 上下文时 {@code PortalIdentity.requireTenantId()} 抛
     * {@code CustomerException(401_001)}。这就是"形状校验已放行"的<b>标志</b>:
     * 请求一路走到了取身份那一步, 才可能抛 401_001。
     */
    private static final int IDENTITY_ERROR_CODE = 401_001;

    /** service 传 null 是故意的: 合法入参会走到它, 非法入参必须在此之前就抛。 */
    private final PortalReconController controller = new PortalReconController(null);

    /**
     * 长度达标的异议理由 (≥ 10 字)。
     *
     * <p>金额相关的用例必须用它 —— 理由长度闸门排在金额校验<b>之前</b>,
     * 拿一个 4 字的理由去测金额, 测到的其实是理由闸门。
     */
    private static final String REASON_OK = "9 月 3 日那票没送到";

    /**
     * 清掉静态身份上下文。
     *
     * <p>它们是 {@code ThreadLocal}, 而 JUnit 同一线程可能跑过别的用例 (如
     * {@code PortalIdentityTest}) —— 残留值会让"合法 body"的用例行为不可预测。
     * 清干净之后, 取身份必然失败于 401_001, 断言才稳定。
     */
    @BeforeEach
    void clearIdentityContext() {
        TenantContext.clear();
        CustomerContext.clear();
    }

    private static void assertCode(int expectedCode, CustomerException ex) {
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }

    /**
     * 取出异常码。形参用 {@code Throwable} 而不是 {@code CustomerException} ——
     * {@code satisfies} 的消费者拿到的是捕获类型, 直接声明成 {@code CustomerException}
     * 会让编译器在重载里挑不出唯一解。
     */
    private static int codeOf(Throwable t) {
        assertThat(t).isInstanceOf(CustomerException.class);
        return ((CustomerException) t).getCode();
    }

    @Test
    @DisplayName("body 为 null → 400_005")
    void nullBody() {
        assertThatThrownBy(() -> controller.dispute(null))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_005, (CustomerException) e));
    }

    @Test
    @DisplayName("reconId 为 null → 400_005 (不是 404_002 —— 我们压根没查过任何对账单)")
    void nullReconId() {
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(null, null, "金额不对")))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_005, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 为 null → 400_003")
    void nullReason() {
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, null)))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_003, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 为空白 → 400_003")
    void blankReason() {
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, "   \t  ")))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_003, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 501 字 → 400_005 (dispute_reason 是 VARCHAR(500), 超了会 Data too long → 500)")
    void reasonTooLong() {
        String tooLong = "理".repeat(501);
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, tooLong)))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_005, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 恰好 500 字 → 不该被长度校验拦下 (否则会误伤合法请求)")
    void reasonExactlyMaxLength() {
        // 500 字合法 → 形状校验放行 → 请求走到取身份 → 401_001。
        String exactlyMax = "理".repeat(500);
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, exactlyMax)))
                .satisfies(e -> assertThat(codeOf(e))
                        .as("形状校验应放行, 失败点应是取身份 (401_001) 而非形状错误")
                        .isEqualTo(IDENTITY_ERROR_CODE));
    }

    @Test
    @DisplayName("claimedAmount 超出 DECIMAL(18,4) → 400_004")
    void claimedAmountOutOfRange() {
        // 15 位整数 —— DECIMAL(18,4) 只有 14 位整数位
        BigDecimal tooBig = new BigDecimal("999999999999999.9999");
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, tooBig, REASON_OK)))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_004, (CustomerException) e));
    }

    @Test
    @DisplayName("claimedAmount 小数位超过 4 → 400_004")
    void claimedAmountTooManyDecimals() {
        assertThatThrownBy(() -> controller.dispute(
                new DisputeRequest(1L, new BigDecimal("1.00001"), REASON_OK)))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_004, (CustomerException) e));
    }

    /**
     * 0 是"既不认同也不主张" —— 与"只质疑不主张金额"(claimedAmount 留 null) 语义重复,
     * 收进来只会给财务多一条没意义的审批单。所以 0 必须被拒 (400_004)。
     *
     * <p>⚠️ 负数必须放行 (见 {@link #negativeClaimIsLegal}): 商家主张的是<b>差额</b>,
     * 财务算出的差额是负的就该是负的。
     */
    @Test
    @DisplayName("claimedAmount 为 0 → 400_004 (只质疑不主张就别填金额)")
    void zeroClaimRejected() {
        assertThatThrownBy(() -> controller.dispute(
                new DisputeRequest(1L, new BigDecimal("0.0000"), REASON_OK)))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_004, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 9 字 (差 1 字) → 400_003")
    void reasonTooShort() {
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, "理".repeat(9))))
                .isInstanceOf(CustomerException.class)
                .satisfies(e -> assertCode(400_003, (CustomerException) e));
    }

    @Test
    @DisplayName("reason 恰好 10 字 → 不该被长度下限拦下 (否则会误伤合法请求)")
    void reasonExactlyMinLength() {
        String exactlyMin = "理".repeat(10);
        assertThatThrownBy(() -> controller.dispute(new DisputeRequest(1L, null, exactlyMin)))
                .satisfies(e -> assertThat(codeOf(e))
                        .as("形状校验应放行, 失败点应是取身份 (401_001) 而非形状错误")
                        .isEqualTo(IDENTITY_ERROR_CODE));
    }

    @Test
    @DisplayName("负数主张是合法设计 → 不能被范围校验拦下")
    void negativeClaimIsLegal() {
        // Task 3 的既有测试断言 disputeAmount == "-12.00", 负差额是合法主张。
        // 这里的负数在 DECIMAL(18,4) 范围内, 所以形状校验应放行。
        assertThatThrownBy(() -> controller.dispute(
                new DisputeRequest(1L, new BigDecimal("-12.00"), "9 月 3 日那票没送到")))
                .satisfies(e -> assertThat(codeOf(e))
                        .as("形状校验应放行, 失败点应是取身份 (401_001) 而非形状错误")
                        .isEqualTo(IDENTITY_ERROR_CODE));
    }
}

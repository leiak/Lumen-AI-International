package com.lumen.finance.rates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * exchangerate.host 远端拉取实现 (Iteration 4.3.2, PR2-T2.2).
 *
 * <p>API 文档: <a href="https://exchangerate.host">https://exchangerate.host</a>.
 * 响应格式:
 * <pre>
 * {
 *   "success": true,
 *   "base": "USD",
 *   "rates": { "CNY": 7.1234, ... }
 * }
 * </pre>
 *
 * <p>异常路径全部归一到 {@link FinanceErrorCode#RATE_SYNC_FAILED}:
 * <ul>
 *   <li>{@code success=false} (例如 invalid base) → FinanceException</li>
 *   <li>HTTP / 网络异常 (RestClientException) → FinanceException</li>
 *   <li>响应解析失败 (非 JSON) → FinanceException</li>
 * </ul>
 *
 * <p><b>RestTemplate 来源:</b> 当前 lumen-finance / lumen-bootstrap 尚无 {@code @Bean RestTemplate}
 * 全局配置, 本类在生产构造路径里直接 {@code new RestTemplate()}.
 * TODO (后续任务): 在 lumen-finance/config 或 lumen-bootstrap 增加
 * {@code RestTemplateConfig @Configuration}, 把连接池 / 超时 / 拦截器统一管理,
 * 然后改成 {@code @RequiredArgsConstructor} 注入.</p>
 */
@Component
@Slf4j
public class ExchangeRateHostProvider implements RateProvider {

    private static final String BASE_URL = "https://api.exchangerate.host/latest";
    private static final String SOURCE_TAG = "EXCHANGERATE_HOST";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生产用构造 (Spring DI). 见类注释 — 后续会替换为注入.
     */
    public ExchangeRateHostProvider() {
        this(new RestTemplate());
    }

    /**
     * 测试用构造 — 允许传入 mock 的 {@link RestTemplate}.
     */
    public ExchangeRateHostProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public RateQuote fetch(String from, String to, LocalDate asOf) {
        String url = BASE_URL + "?base=" + from + "&symbols=" + to;
        try {
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) {
                throw new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED,
                    "exchangerate.host returned null response for base=" + from + " symbols=" + to);
            }
            JsonNode root = objectMapper.readTree(response);
            if (!root.path("success").asBoolean(true)) {
                throw new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED,
                    "exchangerate.host returned success=false: " + response);
            }
            JsonNode rateNode = root.path("rates").path(to);
            if (rateNode.isMissingNode() || rateNode.isNull()) {
                throw new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED,
                    "exchangerate.host response missing rate for " + to + ": " + response);
            }
            BigDecimal rate = new BigDecimal(rateNode.asText());
            return new RateQuote(rate, asOf, SOURCE_TAG);
        } catch (FinanceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch rate from exchangerate.host (from={}, to={}): {}",
                from, to, e.getMessage());
            throw new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED,
                "Failed to fetch rate: " + e.getMessage());
        }
    }
}

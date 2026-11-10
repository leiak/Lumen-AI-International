package com.lumen.order;

/**
 * 空类, 标记 lumen-order 模块被 spring scan (照 com.lumen.customer.CustomerModuleMarker).
 *
 * <p>lumen-bootstrap 的启动类是 {@code @SpringBootApplication(scanBasePackages = "com.lumen")},
 * 只要模块在 classpath 上, com.lumen.order.** 下的 @Component / @RestController 就会被扫到.
 */
public final class OrderModuleMarker {
    private OrderModuleMarker() {}
}

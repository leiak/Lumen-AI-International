package com.lumen.common.entity;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lumen.common.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        TenantLineInnerInterceptor tenant = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tid = TenantContext.get();
                return tid == null ? new LongValue(0) : new LongValue(tid);
            }
            @Override
            public String getTenantIdColumn() { return "tenant_id"; }
            @Override
            public boolean ignoreTable(String tableName) {
                // 系统表不过滤
                if (tableName.startsWith("sys_") && (tableName.endsWith("_dict")
                        || tableName.endsWith("_dict_item")
                        || tableName.endsWith("_number_rule")
                        || tableName.endsWith("_number_sequence")
                        || tableName.endsWith("_state_machine")
                        || tableName.endsWith("_state_transition"))) {
                    return true;
                }
                // t_state_transition 持有"全局"状态机定义（V9/V16/V20 都以 tenant_id=0 灌入），
                // 任何租户上下文下都应该能看到全部转移规则；故显式豁免 MP 多租户拦截器。
                // 否则 TenantContext=非 0 时会被 WHERE tenant_id=? 过滤掉，导致状态机
                // assertTransition 在 PR4 集成测试中误判为"未定义转移"。
                if (tableName.equals("t_state_transition")) {
                    return true;
                }
                // notification_send_log 为 standalone 日志表：跨租户可查（运维/审计场景），
                // 故显式豁免 MP 多租户拦截器；tenant_id 仅用于分片/统计冗余。
                // 注意：notification_template 继承 BaseEntity，仍受租户过滤。
                if (tableName.equals("notification_send_log")) {
                    return true;
                }
                // t_event_outbox 由 OutboxDispatcher 跨租户轮询分发，
                // 不应受租户拦截器约束；tenant_id 仅用于审计/统计冗余。
                // t_approval_record 跨租户可见（运营平台统一审批工作台需要跨租户查询）；
                // tenant_id 字段仅用于审计/统计冗余。
                // t_billing_rule_item 不持有 tenant_id 列（tenant 通过 rule_id 间接确定），
                // 故必须显式豁免；否则 INSERT / UPDATE 会被拦截器注入不存在的 tenant_id 列而报错。
                return tableName.equals("t_event_outbox")
                        || tableName.equals("t_approval_record")
                        || tableName.equals("t_billing_rule_item");
            }
        });
        interceptor.addInnerInterceptor(tenant);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}

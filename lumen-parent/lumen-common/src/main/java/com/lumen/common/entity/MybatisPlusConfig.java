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
                // notification_send_log 为 standalone 日志表：跨租户可查（运维/审计场景），
                // 故显式豁免 MP 多租户拦截器；tenant_id 仅用于分片/统计冗余。
                // 注意：notification_template 继承 BaseEntity，仍受租户过滤。
                return tableName.equals("notification_send_log");
            }
        });
        interceptor.addInnerInterceptor(tenant);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}

package com.lumen.numbering.controller;

import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.numbering.entity.SysNumberRule;
import com.lumen.numbering.mapper.SysNumberRuleMapper;
import com.lumen.numbering.service.NumberGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/number-rules")
@RequiredArgsConstructor
public class NumberRuleController {
    private final NumberGenerator generator;
    private final SysNumberRuleMapper ruleMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('number-rule:list')")
    public R<List<SysNumberRule>> list() {
        return R.ok(ruleMapper.selectList(null));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('number-rule:create')")
    @Audit(action = "create", resource = "number-rule")
    public R<SysNumberRule> create(@RequestBody SysNumberRule rule) {
        ruleMapper.insert(rule);
        return R.ok(rule);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('number-rule:update')")
    @Audit(action = "update", resource = "number-rule", recordResponse = false)
    public R<SysNumberRule> update(@PathVariable Long id, @RequestBody SysNumberRule rule) {
        rule.setId(id);
        ruleMapper.updateById(rule);
        return R.ok(rule);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('number-rule:delete')")
    @Audit(action = "delete", resource = "number-rule")
    public R<Void> delete(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        return R.ok(null);
    }

    @PostMapping("/{code}/preview")
    @PreAuthorize("hasAuthority('number-rule:preview')")
    public R<String> preview(@PathVariable String code) {
        return R.ok(generator.generate(code));
    }

    /**
     * 重置规则序号。
     *
     * <p>请求体：{@code { "value": 0, "period": "20260920" }}，两者均可省略。
     * {@code value} 默认 0；同时写入 {@code sys_number_rule.current_value} 字段。
     * {@code period} 默认按规则 dateFormat 取今日。两路计数器（Redis +
     * DB）都会被清空，下次生成从 1 开始。
     *
     * <p>权限复用 {@code number-rule:update}——种子脚本未单独定义 reset 权限。
     */
    @PostMapping("/{code}/reset")
    @PreAuthorize("hasAuthority('number-rule:update')")
    @Audit(action = "reset", resource = "number-rule")
    public R<Void> reset(
            @PathVariable String code,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        Long nextValue = null;
        String period = null;
        if (body != null) {
            Object v = body.get("value");
            if (v instanceof Number n) {
                nextValue = n.longValue();
            } else if (v != null) {
                // 容忍字符串/其他类型——前端一般发数字
                try {
                    nextValue = Long.parseLong(v.toString());
                } catch (NumberFormatException ignore) {
                    // 忽略非数字，保留 null
                }
            }
            Object p = body.get("period");
            if (p != null) {
                String s = p.toString().trim();
                if (!s.isEmpty()) period = s;
            }
        }
        generator.reset(code, period, nextValue);
        return R.ok(null);
    }
}

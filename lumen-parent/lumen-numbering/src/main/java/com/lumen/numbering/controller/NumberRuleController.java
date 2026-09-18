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
}

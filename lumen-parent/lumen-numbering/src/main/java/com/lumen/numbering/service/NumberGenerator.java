package com.lumen.numbering.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lumen.common.error.BizException;
import com.lumen.numbering.entity.SysNumberRule;
import com.lumen.numbering.entity.SysNumberSequence;
import com.lumen.numbering.error.NumberingErrorCode;
import com.lumen.numbering.mapper.SysNumberRuleMapper;
import com.lumen.numbering.mapper.SysNumberSequenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class NumberGenerator {

    private final SysNumberRuleMapper ruleMapper;
    private final SysNumberSequenceMapper seqMapper;
    private final StringRedisTemplate redis;

    private String redisKey(String ruleCode, String period) {
        return "seq:" + ruleCode + ":" + period;
    }

    @Transactional
    public String generate(String ruleCode) {
        SysNumberRule rule = ruleMapper.selectOne(
                new QueryWrapper<SysNumberRule>().eq("code", ruleCode).last("LIMIT 1"));
        if (rule == null) throw BizException.of(NumberingErrorCode.RULE_NOT_FOUND);

        String period = LocalDate.now().format(DateTimeFormatter.ofPattern(
                rule.getDateFormat() == null ? "yyyyMMdd" : rule.getDateFormat()));
        Long next;
        try {
            Long v = redis.opsForValue().increment(redisKey(ruleCode, period));
            if (v != null && v == 1L) {
                // 首次：DB 兜底
                upsertSequence(ruleCode, period, v);
            }
            next = v;
        } catch (Exception e) {
            log.warn("Redis 不可用，降级到 DB 兜底: ruleCode={}, period={}", ruleCode, period, e);
            SysNumberSequence seq = seqMapper.selectOne(
                    new QueryWrapper<SysNumberSequence>()
                            .eq("rule_code", ruleCode)
                            .eq("period", period)
                            .last("LIMIT 1"));
            if (seq == null) {
                SysNumberSequence n = new SysNumberSequence();
                n.setRuleCode(ruleCode);
                n.setPeriod(period);
                n.setCurrentValue(1L);
                n.setVersion(0);
                try {
                    seqMapper.insert(n);
                    next = 1L;
                } catch (Exception dup) {
                    // 另一线程已插入，重读
                    seq = seqMapper.selectOne(
                            new QueryWrapper<SysNumberSequence>()
                                    .eq("rule_code", ruleCode)
                                    .eq("period", period)
                                    .last("LIMIT 1"));
                    if (seq == null) throw BizException.of(NumberingErrorCode.SEQ_EXHAUSTED);
                    seq.setCurrentValue(seq.getCurrentValue() + 1);
                    int upd = seqMapper.update(seq, new UpdateWrapper<SysNumberSequence>()
                            .eq("rule_code", ruleCode)
                            .eq("period", period)
                            .eq("version", seq.getVersion()));
                    if (upd == 0) throw BizException.of(NumberingErrorCode.SEQ_EXHAUSTED);
                    next = seq.getCurrentValue();
                }
            } else {
                seq.setCurrentValue(seq.getCurrentValue() + 1);
                int upd = seqMapper.update(seq, new UpdateWrapper<SysNumberSequence>()
                        .eq("rule_code", ruleCode)
                        .eq("period", period)
                        .eq("version", seq.getVersion()));
                if (upd == 0) throw BizException.of(NumberingErrorCode.SEQ_EXHAUSTED);
                next = seq.getCurrentValue();
            }
        }
        String seq = String.format("%0" + rule.getSeqLength() + "d", next);
        StringBuilder sb = new StringBuilder();
        if (rule.getPrefix() != null) sb.append(rule.getPrefix()).append('-');
        sb.append(period).append('-').append(seq);
        return sb.toString();
    }

    private void upsertSequence(String ruleCode, String period, Long v) {
        SysNumberSequence n = new SysNumberSequence();
        n.setRuleCode(ruleCode);
        n.setPeriod(period);
        n.setCurrentValue(v);
        n.setVersion(0);
        seqMapper.insert(n);
    }
}

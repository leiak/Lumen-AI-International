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

    /**
     * 重置指定规则在某周期内的序号计数器。
     *
     * <p>同时清理两路计数器：Redis 中的 {@code seq:ruleCode:period} 键值以及
     * 数据库 {@code sys_number_sequence} 中的对应行。两者都被删除后，下次
     * {@link #generate(String)} 调用会以 {@code 1} 开始重新计数。如果传入了
     * {@code nextValue}，会同步把规则行的 {@code currentValue} 字段更新为该值
     * 供列表展示（不影响实际计数起点，下一次仍从 1 开始）。
     *
     * @param ruleCode  规则编码
     * @param period    周期字符串（如 20260920）；为 {@code null} 时按规则
     *                  的 dateFormat 取今日
     * @param nextValue 可选的下一个序号起点（通常传 0）；为 {@code null} 表示
     *                  不动 {@code currentValue} 字段
     */
    @Transactional
    public void reset(String ruleCode, String period, Long nextValue) {
        SysNumberRule rule = ruleMapper.selectOne(
                new QueryWrapper<SysNumberRule>().eq("code", ruleCode).last("LIMIT 1"));
        if (rule == null) throw BizException.of(NumberingErrorCode.RULE_NOT_FOUND);

        String resolvedPeriod = period;
        if (resolvedPeriod == null || resolvedPeriod.isBlank()) {
            resolvedPeriod = LocalDate.now().format(DateTimeFormatter.ofPattern(
                    rule.getDateFormat() == null ? "yyyyMMdd" : rule.getDateFormat()));
        }

        // Redis 键值：删除（若 Redis 不可用则忽略，让下次 generate 走 DB 兜底）
        try {
            redis.delete(redisKey(ruleCode, resolvedPeriod));
        } catch (Exception e) {
            log.warn("重置时清理 Redis 失败，仅清理 DB: ruleCode={}, period={}", ruleCode, resolvedPeriod, e);
        }

        // DB 兜底表：删除该周期的计数行，下次 generate 会再以 v=1 触发首次插入
        seqMapper.delete(new QueryWrapper<SysNumberSequence>()
                .eq("rule_code", ruleCode)
                .eq("period", resolvedPeriod));

        // 规则展示字段：仅当显式给出 nextValue 时更新
        if (nextValue != null) {
            SysNumberRule upd = new SysNumberRule();
            upd.setCurrentValue(nextValue);
            ruleMapper.update(upd,
                    new UpdateWrapper<SysNumberRule>().eq("id", rule.getId()));
        }
    }
}

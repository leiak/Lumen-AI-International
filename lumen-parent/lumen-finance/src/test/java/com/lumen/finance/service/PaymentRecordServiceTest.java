package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.service.impl.PaymentRecordServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRecordServiceTest {

    @Mock private com.lumen.finance.mapper.BillingPaymentRecordMapper mapper;
    @InjectMocks private PaymentRecordServiceImpl service;

    /**
     * MP 的 lambda 列名缓存由 Spring 启动时的 TableInfoHelper 填充; 纯 Mockito
     * 单测没有 Spring 上下文, 缓存是空的 —— 任何 eq(Entity::getX, v) 在生成
     * SQL 片段时都会抛 "can not find lambda cache for this entity"。
     * 没有这一步, 连 getSqlSegment() 都跑不到, 测试会在断言之前就炸。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BillingPaymentRecord.class);
    }

    @Test
    void create_happy_persistsRecord() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-10");
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000.00"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));
        req.setSourceRef("BANK20261015001");
        req.setRemark("test");

        when(mapper.insert(any(BillingPaymentRecord.class))).thenReturn(1);

        BillingPaymentRecord result = service.create(1L, 999L, req);

        assertNotNull(result);
        assertEquals("MANUAL", result.getSource());
        assertEquals(999L, result.getCreatedBy());
        verify(mapper).insert(any(BillingPaymentRecord.class));
    }

    @Test
    void create_invalidPeriod_throwsBadRequest() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-13");  // invalid month
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.create(1L, 999L, req));
        // Should be BAD_REQUEST (not PAYMENT_RECORD_DUPLICATE) for malformed period
        assertEquals(FinanceErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void create_duplicateKey_throwsPaymentRecordDuplicate() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-10");
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));

        when(mapper.insert(any(BillingPaymentRecord.class)))
            .thenThrow(new DuplicateKeyException("uk_payment"));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.create(1L, 999L, req));
        assertEquals(FinanceErrorCode.PAYMENT_RECORD_DUPLICATE, ex.getErrorCode());
    }

    @Test
    void importCsv_happyCountsSuccessAndSkipsDuplicate() {
        String csv = "direction,counterparty_id,counterparty_type,currency,amount,payment_date,source_ref,remark\n"
                   + "RECEIVABLE,100,CUSTOMER,CNY,500.00,2026-10-15,BANK001,ok\n"
                   + "RECEIVABLE,101,CUSTOMER,CNY,600.00,2026-10-15,BANK002,ok\n";
        MultipartFile file = new MockMultipartFile("f", "test.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));

        when(mapper.insert(any(BillingPaymentRecord.class)))
            .thenReturn(1)
            .thenThrow(new DuplicateKeyException("uk_payment"));

        ImportResult result = service.importCsv(1L, "2026-10", 999L, file);

        assertEquals(2, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkippedCount());
    }

    // ---------- 4.5C-2.1 — 缺口 #8 ----------

    /**
     * 忠实地模拟 MyBatis-Plus 的 {@code selectPage}: <b>就地填充</b>传进去的 page
     * 并<b>原样返回它</b> (反编译 mybatis-plus-core-3.5.7 确认的行为)。
     *
     * <p>直接 {@code thenReturn(new Page<>())} 会让 service 读到与请求无关的默认值
     * (新 Page 的 size=10 / total=0), 于是"分页参数被传到哪了"这类断言根本测不到
     * —— 测的是一个跟实现无关的常量。
     */
    @SuppressWarnings("unchecked")
    private static <T> Answer<Page<T>> fillPage(List<T> records, long total) {
        return inv -> {
            Page<T> p = (Page<T>) inv.getArgument(0);
            p.setRecords(records);
            p.setTotal(total);
            return p;
        };
    }

    /**
     * 从渲染出的 SQL 片段里取出"某个列上绑定的值"。
     *
     * <p>做法: 正则抓出 {@code 列名 = #{...paramNameValuePairs.KEY}} 这样的片段, 再拿
     * KEY 回参数表取值。这样断言的是"<b>这个列上绑的到底是哪个值</b>", 而不是
     * "这个值在不在参数表里" —— 后者分不清值与列的对应关系: 实现把
     * {@code eq(getId, tenantId)} / {@code eq(getTenantId, id)} 写反了, 参数表里
     * 依然是同样两个值, 照样绿。
     *
     * <p>刻意<b>不</b>硬编码 MP 的键名 ({@code MPGENVAL1} / {@code MPGENVAL2} …):
     * 键名带序号后缀, 跨版本可能变, 硬编码会把测试绑死在 MP 的内部实现上。
     * 这里从 SQL 片段里现取键, 因此对 MP 的键命名与参数表的遍历顺序都不敏感 ——
     * MP 的参数表是 HashMap, 迭代顺序 ≠ 插入顺序 (实测 params={MPGENVAL2=7, MPGENVAL1=999})。
     */
    private static Object boundValue(LambdaQueryWrapper<?> w, String column) {
        String sql = w.getSqlSegment();
        Matcher m = Pattern.compile("\\b" + Pattern.quote(column)
                + "\\s*=\\s*#\\{[^{}]*paramNameValuePairs\\.(\\w+)}").matcher(sql);
        assertThat(m.find()).as("SQL 片段里找不到列 %s 的绑定: %s", column, sql).isTrue();
        return w.getParamNameValuePairs().get(m.group(1));
    }

    /**
     * SQL 片段里带参数占位符的条件列, 按出现顺序返回。
     *
     * <p>用来锁住谓词的构造顺序 (id → tenant_id): MP 按 {@code eq} 的调用顺序依次
     * 分配 {@code MPGENVAL1} / {@code MPGENVAL2} …, 所以列的先后就是调用顺序的
     * 直接投影。
     */
    private static List<String> boundColumns(LambdaQueryWrapper<?> w) {
        Matcher m = Pattern.compile("\\b(\\w+)\\s*=\\s*#\\{[^{}]*paramNameValuePairs\\.\\w+}")
                .matcher(w.getSqlSegment());
        List<String> cols = new ArrayList<>();
        while (m.find()) {
            cols.add(m.group(1));
        }
        return cols;
    }

    @Test
    @DisplayName("get: 查不到 → PAYMENT_RECORD_NOT_FOUND (404_007), 不是通用 NOT_FOUND (404_005)")
    void get_notFound_throwsPaymentRecordNotFound() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        FinanceException ex = assertThrows(FinanceException.class, () -> service.get(1L, 999L));

        assertEquals(FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("get: 命中 → 原样返回那一行, 不做多余过滤")
    void get_hit_returnsRowAsIs() {
        BillingPaymentRecord row = new BillingPaymentRecord();
        row.setId(7L);
        row.setTenantId(999L);
        row.setPeriod("2026-10");
        row.setDirection("RECEIVABLE");
        row.setAmount(new BigDecimal("123.45"));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        BillingPaymentRecord got = service.get(999L, 7L);

        assertNotNull(got);
        // 原样透传 —— 没有二次加工 (过滤已经在 SQL 里做完了)
        assertSame(row, got);
        assertEquals(7L, got.getId());
        assertEquals(999L, got.getTenantId());
        assertEquals(new BigDecimal("123.45"), got.getAmount());
        assertEquals("2026-10", got.getPeriod());
    }

    @Test
    @DisplayName("get: 查询条件含 id + tenant_id + deleted = 0")
    void get_filtersByIdTenantAndDeleted() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(FinanceException.class, () -> service.get(7L, 999L));

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(cap.capture());
        LambdaQueryWrapper<BillingPaymentRecord> w = cap.getValue();
        // 先物化 SQL 片段 —— MP 的参数 map 是懒生成的, 不先调 getSqlSegment()
        // 就读 getParamNameValuePairs() 拿到的是空 map, 断言会变成恒真。
        //
        // id 用带词边界的正则而不是 contains("id"): 渲染结果是
        // "(id = ? AND tenant_id = ? AND deleted = 0)", 而 "id" 是 "tenant_id" 的
        // 子串 —— contains("id") 恒真, contains("id = ") 同样会被 "tenant_id = " 满足。
        // 下划线是单词字符, "\b" 不会在 "tenant_id" 内部命中, 所以这个正则只认独立的 id 列。
        assertThat(w.getSqlSegment())
                .containsPattern("\\bid\\s*=")
                .contains("tenant_id")
                .contains("deleted = 0");
        // 谓词的构造顺序: id → tenant_id (MP 按 eq 调用顺序分配 MPGENVAL 序号)
        assertThat(boundColumns(w)).containsExactly("id", "tenant_id");
        // 值与列的绑定关系 —— 光"两个值都在参数表里"分不清谁绑给谁: 实现把
        // eq(getId, tenantId) / eq(getTenantId, id) 写反了, 参数表里依然是同样两个值。
        // 本用例的入参是 get(tenantId=7L, id=999L), 所以 id 列上绑 999L、
        // tenant_id 列上绑 7L。
        assertThat(boundValue(w, "id")).isEqualTo(999L);
        assertThat(boundValue(w, "tenant_id")).isEqualTo(7L);
        // 参数值也要对 —— 光有列名不说明过滤的是哪条记录、哪个租户
        assertThat(w.getParamNameValuePairs().values()).contains(999L, 7L);
    }

    @Test
    @DisplayName("list: 查询条件含 tenant_id 与 deleted = 0")
    void list_filtersByTenantAndDeleted() {
        when(mapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.list(7L, null, null, 1, 20);

        ArgumentCaptor<Page<BillingPaymentRecord>> pageCap = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(pageCap.capture(), cap.capture());
        LambdaQueryWrapper<BillingPaymentRecord> w = cap.getValue();
        assertThat(w.getSqlSegment()).contains("tenant_id").contains("deleted = 0");
        assertThat(w.getParamNameValuePairs().values()).contains(7L);
        // 分页参数原样传到了 mapper —— 桩是 fillPage (原样回传 service 建的那个 Page),
        // 所以这里断言的是真实请求参数, 不是桩的默认值
        assertThat(pageCap.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCap.getValue().getSize()).isEqualTo(20L);
    }

    @Test
    @DisplayName("list: period / direction 为 null 时不生成对应条件 (null-eq 陷阱)")
    void list_withoutPeriodAndDirection_doesNotFilterOnThem() {
        when(mapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.list(7L, null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        // 负向断言: MP 的 eq(field, null) 会生成 "field = null" 而不是跳过, 所以这两个
        // 分支必须靠 if 守卫 —— 守卫被后人改坏时要能静默报警, 而不是悄悄查全表。
        assertThat(sql).doesNotContain("period").doesNotContain("direction");
    }

    @Test
    @DisplayName("list: period / direction 非 null 时生成对应条件, 且参数值正确")
    void list_withPeriodAndDirection_filtersOnThem() {
        when(mapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.list(7L, "2026-10", "RECEIVABLE", 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), cap.capture());
        LambdaQueryWrapper<BillingPaymentRecord> w = cap.getValue();
        assertThat(w.getSqlSegment()).contains("period").contains("direction");
        assertThat(w.getParamNameValuePairs().values()).contains("2026-10", "RECEIVABLE");
    }
}
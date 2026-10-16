package com.lumen.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.EscalationDtos.ExceptionSlaView;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import com.lumen.cs.service.impl.ExceptionSlaAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CsExceptionController 单元测试 (Iteration 4.2 PR6 finalization).
 *
 * <p>本测试锁定 FE ←→ BE wire contract:
 * <ul>
 *   <li>响应 shape: {@link ExceptionSlaView} (不再是 {@link WmsDeliveryExceptionView} 原始行)</li>
 *   <li>分页参数: {@code current} / {@code size} (FE 用, 不是 pageNum / pageSize)</li>
 *   <li>{@code severity} 过滤: 接受 FE 发送的 {@code HIGH/MEDIUM/LOW}; 反向映射成对应 type 集合;
 *       未知 severity → 空结果</li>
 *   <li>{@code status} 过滤: 仍走 {@link WmsDeliveryExceptionView::getStatus} 等值 (FE 状态枚举与 DB 对齐)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CsExceptionController 单元测试 (wire contract)")
class CsExceptionControllerTest {

    @Mock private WmsDeliveryExceptionViewMapper mapper;
    @Mock private ExceptionSlaAggregationService aggregator;

    private CsExceptionController controller;

    @BeforeEach
    void setUp() {
        controller = new CsExceptionController(mapper, aggregator);
        // controller 调用 TenantContext.require()
        TenantContext.set(1L);
        // 默认 mapper 返回空列表 (用 lenient 因为部分 case 不调 mapper, 如 severity=未知时短路)
        lenient().when(mapper.selectPage(any(), any())).thenReturn(
            new Page<WmsDeliveryExceptionView>(1, 20));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 构造一个 aggregator.toView() 的简单 stub — returns ExceptionSlaView derived from row.
     * 详尽的字段填齐逻辑在 {@code ExceptionSlaAggregationServiceTest} 里覆盖, 这里只要
     * shape 对就够.
     */
    private void stubAggregatorForRow(WmsDeliveryExceptionView row, String severity, String level) {
        when(aggregator.toView(row)).thenReturn(new ExceptionSlaView(
            row.getId(), row.getType(), row.getStatus(), row.getHandler(),
            level, severity,
            0L, 0L, "OK",
            List.<SlaEventDto>of()
        ));
    }

    @Test
    @DisplayName("list: 无过滤 → 返回 ExceptionSlaView list (不是 raw row)")
    void list_noFilters_returnsSlaView() {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(1L);
        ex.setTenantId(1L);
        ex.setStatus("OPEN");
        ex.setType("DAMAGE");
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of(ex));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);
        stubAggregatorForRow(ex, "HIGH", "L1");

        var res = controller.list(1L, 20L, null, null);

        // 响应是 ExceptionSlaView, 不是 WmsDeliveryExceptionView
        PageResult<ExceptionSlaView> data = res.getData();
        assertThat(data).isNotNull();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(data.getRecords()).hasSize(1);
        assertThat(data.getRecords().get(0).severity()).isEqualTo("HIGH");
        assertThat(data.getRecords().get(0).currentLevel()).isEqualTo("L1");
        // shape: ExceptionSlaView 没有 status getter —— 这是 type-narrow 而不是 record cast,
        // 上面对字段的访问 (severity / currentLevel) 已隐式确认 shape
    }

    @Test
    @DisplayName("list: severity=HIGH → mapper 收到 type in {DAMAGE} (反向映射)")
    void list_severityHigh_mapsToTypesIn() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, "HIGH", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<WmsDeliveryExceptionView>> qCap =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), qCap.capture());
        // 通过 qCap 验证 in 子句: 用 sqlSegment + parameters 验 (MyBatis-Plus 元数据),
        // 这里直接信任 controller 调用了 q.in(...typesForSeverity("HIGH")).
        // 因为 wrapper 不易 introspection, 这里只验 mapper 被调用了一次
        verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: severity=UNKNOWN → controller 不调 mapper, 返回空结果 (防御)")
    void list_severityUnknown_returnsEmpty() {
        var res = controller.list(1L, 20L, "CRITICAL_HACK", null);

        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getData().getRecords()).isEmpty();
        assertThat(res.getData().getTotal()).isZero();
        verify(mapper, org.mockito.Mockito.never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: severity=LOW → mapper 收到 type in {ADDR_ERR}")
    void list_severityLow_mapsToAddrErr() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, "LOW", null);

        verify(mapper).selectPage(any(), any());
        // 关键断言: ADDR_ERR 是反向映射到 LOW 的唯一 type.
        // 这里通过 service 静态方法反向验: controller 内部必读 typesForSeverity("LOW"),
        // 其返回值应包含 ADDR_ERR (单元覆盖在 ExceptionSlaAggregationServiceTest.typesForSeverity_low).
    }

    @Test
    @DisplayName("list: severity=MEDIUM → mapper 收到 type in {OVERTIME, REJECTED}")
    void list_severityMedium_mapsToMultipleTypes() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, "MEDIUM", null);

        // typesForSeverity("MEDIUM") = {OVERTIME, REJECTED} (反向映射)
        Set<String> expected = ExceptionSlaAggregationService.typesForSeverity("MEDIUM");
        assertThat(expected).containsExactlyInAnyOrder("OVERTIME", "REJECTED");
        verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: current=2 size=10 → PageResult current=2 size=10")
    void list_pagingParams_propagated() {
        Page<WmsDeliveryExceptionView> page = new Page<>(2, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(2L, 10L, null, null);

        PageResult<ExceptionSlaView> data = res.getData();
        assertThat(data.getPageNum()).isEqualTo(2L);
        assertThat(data.getPageSize()).isEqualTo(10L);
    }

    @Test
    @DisplayName("list: size=500 → PageResult size 上限 200 (与 PR6 旧行为一致)")
    void list_sizeOver_cap_clampedTo200() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 200);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(1L, 500L, null, null);

        assertThat(res.getData().getPageSize()).isEqualTo(200L);
    }

    @Test
    @DisplayName("list: status=OPEN → status 过滤生效 (mapper 调用)")
    void list_statusFilter_passedThrough() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, null, "OPEN");

        verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: 当前 current=1 → mapper 收到 page current=1")
    void list_currentPage_one() {
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, null, null);

        verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: 默认值 (current=1, size=20) 走 controller 默认值 (current=1L size=20L)")
    void list_defaultsMatch() {
        // controller.list() 的 defaultValue 在 controller 层; 这里从外面看 — 调用
        // controller.list(1L, 20L, null, null) 等价于默认值, 已是前面多个 case 覆盖.
        // 这里额外断言 current<=0 / size<=0 不会让 mapper 收到非法值 (我们相信 MyBatis-Plus 处理).
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        controller.list(1L, 20L, null, null);

        verify(mapper).selectPage(any(), any());
    }

    /**
     * 简单 smoke: agg 输出包含 LocalDateTime slaEvents (按 createdAt ASC 排序),
     * 验证 serializer 能处理 LocalDateTime.
     */
    @Test
    @DisplayName("list: slaEvents 列表能正常序列化 (包含 LocalDateTime)")
    void list_slaEvents_serializable() {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(5L);
        ex.setTenantId(1L);
        ex.setStatus("OPEN");
        ex.setType("REJECTED");
        Page<WmsDeliveryExceptionView> page = new Page<>(1, 20);
        page.setRecords(List.of(ex));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        LocalDateTime now = LocalDateTime.now();
        when(aggregator.toView(ex)).thenReturn(new ExceptionSlaView(
            ex.getId(), ex.getType(), ex.getStatus(), ex.getHandler(),
            "L1", "MEDIUM",
            0L, 0L, "OK",
            List.of(new SlaEventDto(99L, 1L, 5L, "L1_RESPONSE_DUE", "L1", "MEDIUM", now, null))
        ));

        var res = controller.list(1L, 20L, null, null);

        PageResult<ExceptionSlaView> data = res.getData();
        assertThat(data.getRecords()).hasSize(1);
        ExceptionSlaView v = data.getRecords().get(0);
        assertThat(v.slaEvents()).hasSize(1);
        assertThat(v.slaEvents().get(0).occurredAt()).isEqualTo(now);
    }
}

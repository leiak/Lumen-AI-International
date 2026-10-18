package com.lumen.dispatch.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.dto.TrackQuery;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.service.impl.DeliveryOrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryOrderService 客户门户 2 overload 单元测试 (Iteration 4.5A.3).
 *
 * <p>覆盖 4.5A.2 stub → 4.5A.3 真实实现后的核心分支:
 * <ul>
 *   <li>pageForCustomer: 转 Page + tenantId/customerId 透传 + TrackQuery 透传</li>
 *   <li>getDetailForCustomer: 不存在 → 404 / 越权 → 404 / 自己的 DO → 返回详情</li>
 * </ul>
 *
 * <p>采用 {@code @MockitoSettings(strictness = LENIENT)} 以容忍 stub 间的
 * "未消费" 警告; 关键调用用 {@code verify(...)} 收紧断言.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryOrderService 客户门户 overload 单元测试")
class DeliveryOrderServiceCustomerOverloadTest {

    @Mock private WmsDeliveryOrderMapper orderMapper;

    @InjectMocks private DeliveryOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================
    // pageForCustomer
    // ================================================================

    @Test
    @DisplayName("pageForCustomer: 把 tenantId/customerId/TrackQuery 透传给 mapper, 返回 Map 行列表")
    void pageForCustomer_callsMapperWithTenantAndCustomerId() {
        // 给 mapper 一个空 Page, 验证 service 不抛且字段透传
        Page<WmsDeliveryOrder> emptyPage = new Page<>(1, 20, 0);
        when(orderMapper.pageForCustomer(any(Page.class), anyLong(), anyLong(), any(TrackQuery.class)))
                .thenReturn(emptyPage);

        TrackQuery q = new TrackQuery(null, null, 1, 20);
        IPage<Map<String, Object>> result = service.pageForCustomer(1L, 42L, q);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getCurrent()).isEqualTo(1L);
        assertThat(result.getSize()).isEqualTo(20L);

        // 验证 mapper.pageForCustomer 被调用, 参数透传
        ArgumentCaptor<Page<WmsDeliveryOrder>> pageCap = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Long> tenantCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> custCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TrackQuery> qCap = ArgumentCaptor.forClass(TrackQuery.class);
        verify(orderMapper, times(1)).pageForCustomer(
                pageCap.capture(), tenantCap.capture(), custCap.capture(), qCap.capture());
        assertThat(tenantCap.getValue()).isEqualTo(1L);
        assertThat(custCap.getValue()).isEqualTo(42L);
        assertThat(qCap.getValue()).isSameAs(q);
    }

    @Test
    @DisplayName("pageForCustomer: mapper 行 → Map 的字段映射 (doNo/status/weight/volume 等)")
    void pageForCustomer_mapsEntityFieldsToMap() {
        WmsDeliveryOrder d = new WmsDeliveryOrder();
        d.setId(100L);
        d.setNo("DO-001");
        d.setStatus("DISPATCHED");
        d.setWarehouseId(7L);
        d.setTotalWeightKg(new java.math.BigDecimal("12.500"));
        d.setTotalVolumeM3(new java.math.BigDecimal("0.800"));
        d.setStopCount(2);

        Page<WmsDeliveryOrder> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(d));
        when(orderMapper.pageForCustomer(any(Page.class), eq(1L), eq(42L), any(TrackQuery.class)))
                .thenReturn(page);

        IPage<Map<String, Object>> result = service.pageForCustomer(1L, 42L,
                new TrackQuery(null, null, 1, 20));

        assertThat(result.getRecords()).hasSize(1);
        Map<String, Object> row = result.getRecords().get(0);
        assertThat(row.get("doId")).isEqualTo(100L);
        assertThat(row.get("doNo")).isEqualTo("DO-001");
        assertThat(row.get("status")).isEqualTo("DISPATCHED");
        assertThat(row.get("warehouseId")).isEqualTo(7L);
        // 字段名对齐 PortalTrackController.toTrackListItem: weight/volume
        assertThat(row.get("weight").toString()).isEqualTo("12.500");
        assertThat(row.get("volume").toString()).isEqualTo("0.800");
        assertThat(row.get("stopCount")).isEqualTo(2);
        // 地址/收件人在 wms_delivery_stop; 4.5A.3 显式 null
        assertThat(row.get("originAddress")).isNull();
        assertThat(row.get("destAddress")).isNull();
    }

    // ================================================================
    // getDetailForCustomer
    // ================================================================

    @Test
    @DisplayName("getDetailForCustomer: 别人的 DO (customerId 不匹配) → 抛 DeliveryOrderNotFoundException (404)")
    void getDetailForCustomer_otherCustomerDo_throws404() {
        WmsDeliveryOrder d = new WmsDeliveryOrder();
        d.setId(100L);
        d.setCustomerId(99L);  // 不是请求方 42L
        d.setNo("DO-OTHER");
        when(orderMapper.selectById(100L)).thenReturn(d);

        assertThatThrownBy(() -> service.getDetailForCustomer(1L, 42L, 100L))
                .isInstanceOf(DeliveryOrderNotFoundException.class);

        // 跨客户访问也走 NotFound 路径 (无 leak "this exists but you can't see" 语义)
        // 只读操作, 不应触发任何 update; mapper 上不允许的写动作已经在
        // DeliveryOrderService 内部阻断, 本测试只需断言抛 NotFound 即可.
    }

    @Test
    @DisplayName("getDetailForCustomer: 自己的 DO → 返回详情 Map (含 timeline 空列表)")
    void getDetailForCustomer_ownDo_returnsDetail() {
        WmsDeliveryOrder d = new WmsDeliveryOrder();
        d.setId(100L);
        d.setTenantId(1L);    // 与 TenantContext.set(1L) 一致
        d.setCustomerId(42L); // 与请求方一致
        d.setNo("DO-001");
        d.setStatus("SIGNED");
        d.setWarehouseId(7L);
        when(orderMapper.selectById(100L)).thenReturn(d);

        Map<String, Object> resp = service.getDetailForCustomer(1L, 42L, 100L);

        assertThat(resp).isNotNull();
        assertThat(resp.get("doId")).isEqualTo(100L);
        assertThat(resp.get("doNo")).isEqualTo("DO-001");
        assertThat(resp.get("status")).isEqualTo("SIGNED");
        assertThat(resp.get("warehouseId")).isEqualTo(7L);
        // timeline 4.5A.3 不拼, 留给后续 PR
        assertThat(resp.get("timeline")).isInstanceOf(List.class);
        assertThat((List<?>) resp.get("timeline")).isEmpty();
    }

    @Test
    @DisplayName("getDetailForCustomer: selectById 返回 null → 抛 DeliveryOrderNotFoundException")
    void getDetailForCustomer_doNotFound_throws404() {
        when(orderMapper.selectById(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.getDetailForCustomer(1L, 42L, 100L))
                .isInstanceOf(DeliveryOrderNotFoundException.class);
    }

    @Test
    @DisplayName("跨租户访问同 customer 仍应 404 (defense-in-depth)")
    void getDetailForCustomer_otherTenantDo_throws404() {
        WmsDeliveryOrder d = new WmsDeliveryOrder();
        d.setId(100L);
        d.setTenantId(2L);    // 跨租户
        d.setCustomerId(42L); // 但同 customer
        when(orderMapper.selectById(100L)).thenReturn(d);

        assertThatThrownBy(() -> service.getDetailForCustomer(1L, 42L, 100L))
                .isInstanceOf(DeliveryOrderNotFoundException.class);
    }
}

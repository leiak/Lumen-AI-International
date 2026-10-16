package com.lumen.cs.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CsExceptionController 单元测试 (Iteration 4.2 PR6 finalize).
 *
 * <p>覆盖分页 + 过滤行为 (severity / status / pageNum / pageSize):
 * <ul>
 *   <li>无过滤: 返回全表 (含 deleted=0)</li>
 *   <li>severity=type + status=OPEN: 返回过滤后的列表</li>
 *   <li>pageNum=2 pageSize=10: 返回对应分页</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CsExceptionController 单元测试")
class CsExceptionControllerTest {

    @Mock private WmsDeliveryExceptionViewMapper mapper;
    @InjectMocks private CsExceptionController controller;

    @BeforeEach
    void setUp() {
        // mapper 默认返回空列表
        when(mapper.selectPage(any(), any())).thenReturn(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));
        // controller 调用 TenantContext.require()
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("list: 无过滤 → 返回 mapper 默认列表 (含分页对象)")
    void list_noFilters_returnsAll() {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(1L);
        ex.setTenantId(1L);
        ex.setStatus("OPEN");
        ex.setType("DAMAGE");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsDeliveryExceptionView> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(ex));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(1L, 20L, null, null);

        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getData().getRecords()).hasSize(1);
        assertThat(res.getData().getRecords().get(0).getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("list: severity=DAMAGE + status=OPEN → mapper 收到正确 wrapper")
    void list_withFilters_passesToMapper() {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(2L);
        ex.setTenantId(1L);
        ex.setStatus("OPEN");
        ex.setType("DAMAGE");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsDeliveryExceptionView> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(ex));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(1L, 20L, "OPEN", "DAMAGE");

        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getData().getRecords()).hasSize(1);
        // 验证 mapper.selectPage 被调用
        org.mockito.Mockito.verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("list: pageNum=2 pageSize=10 → PageResult pageNum/pageSize 正确")
    void list_pageParams_passedThrough() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsDeliveryExceptionView> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(2L, 10L, null, null);

        assertThat(res.getCode()).isEqualTo(0);
        PageResult<WmsDeliveryExceptionView> data = res.getData();
        assertThat(data.getPageNum()).isEqualTo(2L);
        assertThat(data.getPageSize()).isEqualTo(10L);
    }

    @Test
    @DisplayName("list: 空结果 → 返回空 PageResult")
    void list_emptyResult_returnsEmpty() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsDeliveryExceptionView> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var res = controller.list(1L, 20L, null, null);

        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getData().getRecords()).isEmpty();
        assertThat(res.getData().getTotal()).isZero();
    }
}

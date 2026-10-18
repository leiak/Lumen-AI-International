package com.lumen.wms.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.entity.Pod;
import com.lumen.wms.mapper.PodMapper;
import com.lumen.wms.service.impl.PodServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PodService.getForCustomer 客户门户 overload 单元测试 (Iteration 4.5A.3 T3.2).
 *
 * <p>纯 Mockito (无 Spring 上下文 / TestContainer). 与 {@code OutboundServiceStateMachineTest}
 * 同模式. 覆盖:
 * <ul>
 *     <li>有 POD: 返回 PHOTO 类型, 包含 imageUrl / signerName / signedAt 等字段</li>
 *   <li>无 POD (未签收): 返回 NONE 类型, 字段为空串</li>
 *   <li>签名类型 POD: SIGNATURE podType, 含 signatureBase64, imageUrl 可空</li>
 * </ul>
 *
 * <p>TenantContext: 模块共用 {@link TenantContext} (lumen-common), 本测试 setUp 注入
 * tenant=1L 以保证线程隔离; tearDown clear 避免污染其它用例. 验证
 * PodServiceImpl 不依赖 TenantContext (薄门面: ownership 校验在 controller 上层完成).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PodService.getForCustomer 客户门户 overload 单元测试")
class PodServiceCustomerOverloadTest {

    @Mock
    private PodMapper podMapper;

    @InjectMocks
    private PodServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("有 POD (PHOTO): 返回完整 PHOTO 字段")
    void getForCustomer_existingPod_returnsFullResponse() {
        Pod p = new Pod();
        p.setId(1L);
        p.setTenantId(1L);
        p.setDoId(100L);
        p.setPodType("PHOTO");
        p.setImageUrl("/uploads/pod/100.jpg");
        p.setSignerName("张三");
        p.setSignedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        when(podMapper.selectByDoId(100L)).thenReturn(p);

        Map<String, Object> resp = service.getForCustomer(1L, 42L, 100L);

        assertThat(resp).isNotNull();
        assertThat(resp.get("doId")).isEqualTo(100L);
        assertThat(resp.get("podType")).isEqualTo("PHOTO");
        assertThat(resp.get("imageUrl")).isEqualTo("/uploads/pod/100.jpg");
        assertThat(resp.get("signerName")).isEqualTo("张三");
        assertThat(resp.get("signedAt")).isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
        // base64 在 PHOTO 场景下为 null (字段未设置, LinkedHashMap.put 会保留 null)
        assertThat(resp.get("signatureBase64")).isNull();
    }

    @Test
    @DisplayName("无 POD (未签收): 返回 NONE 桩, 字段均空串")
    void getForCustomer_noPod_returnsNoneStub() {
        when(podMapper.selectByDoId(200L)).thenReturn(null);

        Map<String, Object> resp = service.getForCustomer(1L, 42L, 200L);

        assertThat(resp).isNotNull();
        assertThat(resp.get("doId")).isEqualTo(200L);
        assertThat(resp.get("podType")).isEqualTo("NONE");
        assertThat(resp.get("imageUrl")).isEqualTo("");
        assertThat(resp.get("signatureBase64")).isEqualTo("");
        assertThat(resp.get("signerName")).isEqualTo("");
        assertThat(resp.get("signedAt")).isEqualTo("");
    }

    @Test
    @DisplayName("签名类型 POD (SIGNATURE): 含 signatureBase64, imageUrl 可空")
    void getForCustomer_signatureTypePod_includesBase64() {
        Pod p = new Pod();
        p.setId(2L);
        p.setTenantId(1L);
        p.setDoId(101L);
        p.setPodType("SIGNATURE");
        p.setSignatureBase64("iVBORw0KGgoAAAANSUhEUgAA");
        when(podMapper.selectByDoId(101L)).thenReturn(p);

        Map<String, Object> resp = service.getForCustomer(1L, 42L, 101L);

        assertThat(resp).isNotNull();
        assertThat(resp.get("doId")).isEqualTo(101L);
        assertThat(resp.get("podType")).isEqualTo("SIGNATURE");
        assertThat(resp.get("signatureBase64")).isEqualTo("iVBORw0KGgoAAAANSUhEUgAA");
        // SIGNATURE 场景下 imageUrl 为 null (字段未设置)
        assertThat(resp.get("imageUrl")).isNull();
    }
}

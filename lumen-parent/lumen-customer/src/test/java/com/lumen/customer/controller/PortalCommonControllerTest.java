package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalCustomerChoice;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.mapper.WmsCustomerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 4.5A.6 — 单元测试 for {@link PortalCommonController} 三端点 (profile / profile-update / choices).
 *
 * <p>覆盖 Bug 1/2/3 fix 的真实数据路径, 不依赖 Spring context (纯 Mockito). 单租户假设
 * 由 {@link TenantContext#set(Long)} 在 {@code @BeforeEach} 里设到 1L 触发 MyBatis Plus 多
 * 租户拦截器正常工作; {@link CustomerContext} 同样设 (10L, 20L).
 *
 * <p>约定: PortalTrackControllerTest 同款 {@code MockitoAnnotations.openMocks(this)} + 手动
 * {@code CustomerContext.clear()} @AfterEach. {@link MockitoAnnotations} 不强制 strict stubbing,
 * 所以"未用 stub" 不报错.
 */
class PortalCommonControllerTest {

    private static final long USER_ID = 10L;
    private static final long ACTIVE_CUSTOMER_ID = 20L;

    @Mock WmsCustomerMapper customerMapper;
    @Mock SysUserRoleMapper userRoleMapper;
    @InjectMocks PortalCommonController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.set(1L);
        CustomerContext.set(USER_ID, ACTIVE_CUSTOMER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CustomerContext.clear();
    }

    // ===========================================================
    //  Bug 1 — GET /profile
    // ===========================================================

    @Test
    void getProfile_existingCustomer_returnsAllFields() {
        WmsCustomer c = new WmsCustomer();
        c.setId(ACTIVE_CUSTOMER_ID);
        c.setCode("C001-456");
        c.setName("Acme Inc");
        c.setContact("Alice");
        c.setPhone("13800000000");
        c.setAddress("上海市浦东新区");
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(c);

        R<Map<String, Object>> resp = controller.profile();
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(ACTIVE_CUSTOMER_ID, data.get("customerId"));
        assertEquals("C001-456", data.get("code"));
        assertEquals("Acme Inc", data.get("name"));
        assertEquals("Alice", data.get("contact"));
        assertEquals("13800000000", data.get("phone"));
        assertEquals("上海市浦东新区", data.get("address"));
        // Bug 1 fix 验证: 老 stub 的 "note" 字段不应再出现
        assertNull(data.get("note"));
        verify(customerMapper).selectById(ACTIVE_CUSTOMER_ID);
    }

    @Test
    void getProfile_nullableFieldsCoercedToEmptyString() {
        WmsCustomer c = new WmsCustomer();
        c.setId(ACTIVE_CUSTOMER_ID);
        c.setCode("C002");
        c.setName("Beta");
        // contact/phone/address 全部 null — 4.5A.6 spec 要求 null → "" 防止 FE form 报错
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(c);

        R<Map<String, Object>> resp = controller.profile();
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals("", data.get("contact"));
        assertEquals("", data.get("phone"));
        assertEquals("", data.get("address"));
    }

    @Test
    void getProfile_customerNotFound_throwsCustomerException() {
        // active customerId 在 token 中却 DB 找不到 — fail-fast 而非 500 (error code 404_001)
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(null);

        CustomerException ex = assertThrows(CustomerException.class, () -> controller.profile());
        assertEquals(404_001, ex.getCode());
    }

    // ===========================================================
    //  Bug 2 — PUT /profile
    // ===========================================================

    @Test
    void updateProfile_phoneAddress_updatesWmsCustomer() {
        WmsCustomer c = new WmsCustomer();
        c.setId(ACTIVE_CUSTOMER_ID);
        c.setCode("C001-456");
        c.setName("Acme");
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(c);
        // 注: 不 stub updateById 的返回值 — 它有 (T) / (Collection) 两个重载, 在 Java 里
        // any(WmsCustomer.class) 不能消歧. Mockito 默认返 int=0, 我们只 verify 捕获参数, 不校验返回值.

        Map<String, String> body = Map.of(
            "phone", "13900000000",
            "address", "徐汇区漕河泾路 1 号"
        );
        R<Void> resp = controller.updateProfile(body);
        assertTrue(resp.isSuccess());

        // 验证 mapper.updateById 收到了被修改的 entity (含 phone + address)
        org.mockito.ArgumentCaptor<WmsCustomer> cap = org.mockito.ArgumentCaptor.forClass(WmsCustomer.class);
        verify(customerMapper).updateById(cap.capture());
        WmsCustomer saved = cap.getValue();
        assertEquals(ACTIVE_CUSTOMER_ID, saved.getId());
        assertEquals("13900000000", saved.getPhone());
        assertEquals("徐汇区漕河泾路 1 号", saved.getAddress());
    }

    @Test
    void updateProfile_phoneOnlyNullAddress_updatesAndClearsAddress() {
        // FE 显式置空 address: body 里 address = null (JSON null → Java null) — 应该清空 DB
        WmsCustomer c = new WmsCustomer();
        c.setId(ACTIVE_CUSTOMER_ID);
        c.setAddress("老地址");
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(c);

        Map<String, String> body = new java.util.HashMap<>();
        body.put("phone", "13700000000");
        body.put("address", null);  // 显式 null = 清空
        R<Void> resp = controller.updateProfile(body);
        assertTrue(resp.isSuccess());

        org.mockito.ArgumentCaptor<WmsCustomer> cap = org.mockito.ArgumentCaptor.forClass(WmsCustomer.class);
        verify(customerMapper).updateById(cap.capture());
        assertEquals("13700000000", cap.getValue().getPhone());
        assertNull(cap.getValue().getAddress(), "address=null 应被清空");
    }

    @Test
    void updateProfile_emptyBody_returns200NoOp() {
        // 空 body: 不应触发任何 DB 写
        R<Void> resp = controller.updateProfile(Map.of());
        assertTrue(resp.isSuccess());
        verify(customerMapper, never()).updateById(ArgumentMatchers.<WmsCustomer>any());
    }

    @Test
    void updateProfile_nullBody_returns200NoOp() {
        // body 完全缺失 (FE 未传 JSON body): no-op
        R<Void> resp = controller.updateProfile(null);
        assertTrue(resp.isSuccess());
        verify(customerMapper, never()).updateById(ArgumentMatchers.<WmsCustomer>any());
    }

    @Test
    void updateProfile_passwordOnly_doesNotPersistButReturns200() {
        // 4.5A.6 决策: password 改密留 4.5B. 提交非空 password 时不报错, 但也不改 DB.
        R<Void> resp = controller.updateProfile(Map.of("password", "newP@ss"));
        assertTrue(resp.isSuccess(), "password 字段不阻塞 200 响应");
        verify(customerMapper, never()).updateById(ArgumentMatchers.<WmsCustomer>any());
        verify(customerMapper, never()).selectById(anyLong());
    }

    @Test
    void updateProfile_customerNotFound_throwsCustomerException() {
        when(customerMapper.selectById(ACTIVE_CUSTOMER_ID)).thenReturn(null);
        Map<String, String> body = Map.of("phone", "13800000000");
        assertThrows(CustomerException.class, () -> controller.updateProfile(body));
    }

    // ===========================================================
    //  Bug 3 — GET /choices
    // ===========================================================

    @Test
    void choices_singleCustomer_returnsOneEntryMarkedActive() {
        when(userRoleMapper.listCustomerIdsForUser(USER_ID)).thenReturn(List.of(ACTIVE_CUSTOMER_ID));
        WmsCustomer c = new WmsCustomer();
        c.setId(ACTIVE_CUSTOMER_ID);
        c.setCode("C001");
        c.setName("Acme");
        when(customerMapper.selectBatchIds(List.of(ACTIVE_CUSTOMER_ID))).thenReturn(List.of(c));

        R<List<PortalCustomerChoice>> resp = controller.choices();
        assertTrue(resp.isSuccess());
        List<PortalCustomerChoice> choices = resp.getData();
        assertEquals(1, choices.size());
        assertEquals(ACTIVE_CUSTOMER_ID, choices.get(0).id());
        assertEquals("C001", choices.get(0).code());
        assertEquals("Acme", choices.get(0).name());
        assertTrue(choices.get(0).isPrimary(), "active customer 必须标 isPrimary=true");
    }

    @Test
    void choices_multipleCustomers_returnsAllOrderedByIsPrimary() {
        // SQL ORDER BY is_primary DESC, id ASC: 期望 [20 (active/primary), 21, 22]
        when(userRoleMapper.listCustomerIdsForUser(USER_ID)).thenReturn(List.of(20L, 21L, 22L));
        WmsCustomer c20 = new WmsCustomer(); c20.setId(20L); c20.setCode("C020"); c20.setName("Alpha");
        WmsCustomer c21 = new WmsCustomer(); c21.setId(21L); c21.setCode("C021"); c21.setName("Beta");
        WmsCustomer c22 = new WmsCustomer(); c22.setId(22L); c22.setCode("C022"); c22.setName("Gamma");
        when(customerMapper.selectBatchIds(List.of(20L, 21L, 22L))).thenReturn(List.of(c20, c21, c22));

        R<List<PortalCustomerChoice>> resp = controller.choices();
        assertTrue(resp.isSuccess());
        List<PortalCustomerChoice> choices = resp.getData();
        assertEquals(3, choices.size());

        // 顺序保持 SQL 给出顺序
        assertEquals(20L, choices.get(0).id());
        assertEquals(21L, choices.get(1).id());
        assertEquals(22L, choices.get(2).id());

        // 只有 active 的标 isPrimary=true
        assertTrue(choices.get(0).isPrimary());
        assertFalse(choices.get(1).isPrimary());
        assertFalse(choices.get(2).isPrimary());

        // 真实 customer code/name 而非 stub 的 "ACTIVE" / "当前客户"
        assertEquals("Alpha", choices.get(0).name());
        assertEquals("C022", choices.get(2).code());
    }

    @Test
    void choices_userNotLinkedToAnyCustomer_returnsEmptyList() {
        when(userRoleMapper.listCustomerIdsForUser(USER_ID)).thenReturn(List.of());

        R<List<PortalCustomerChoice>> resp = controller.choices();
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getData());
        assertTrue(resp.getData().isEmpty());
        verify(customerMapper, never()).selectBatchIds(any());
    }

    @Test
    void choices_nullUserId_returnsEmptyList() {
        // filter 没 set userId (极端 path) — 不应 NPE
        CustomerContext.clear();
        CustomerContext.set(null, ACTIVE_CUSTOMER_ID);  // 只 set customerId, 没 userId

        R<List<PortalCustomerChoice>> resp = controller.choices();
        assertTrue(resp.isSuccess());
        assertTrue(resp.getData().isEmpty());
        verify(userRoleMapper, never()).listCustomerIdsForUser(anyLong());
    }

    @Test
    void choices_skipsIdsNotFoundInCustomerTable() {
        // SQL 给出 3 个 id, 但 DB 只有 2 行 (极端: link 表有孤儿 id, 实际不该发生)
        when(userRoleMapper.listCustomerIdsForUser(USER_ID)).thenReturn(List.of(20L, 21L, 99L));
        WmsCustomer c20 = new WmsCustomer(); c20.setId(20L); c20.setCode("C020"); c20.setName("Alpha");
        WmsCustomer c21 = new WmsCustomer(); c21.setId(21L); c21.setCode("C021"); c21.setName("Beta");
        // 99 缺 — controller 应该跳过 (filter null), 不抛 NPE
        when(customerMapper.selectBatchIds(List.of(20L, 21L, 99L))).thenReturn(List.of(c20, c21));

        R<List<PortalCustomerChoice>> resp = controller.choices();
        assertTrue(resp.isSuccess());
        assertEquals(2, resp.getData().size(),
            "orphan id 应被过滤掉, 防止 CustomerSwitcher 渲染空行");
    }
}

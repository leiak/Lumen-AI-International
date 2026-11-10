package com.lumen.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.numbering.service.NumberGenerator;
import com.lumen.order.OrderConstants;
import com.lumen.order.dto.CargoRequest;
import com.lumen.order.dto.CargoView;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.dto.ServiceItemRequest;
import com.lumen.order.dto.ServiceItemView;
import com.lumen.order.entity.Order;
import com.lumen.order.entity.OrderCargo;
import com.lumen.order.entity.OrderServiceItem;
import com.lumen.order.error.OrderErrorCode;
import com.lumen.order.exception.OrderException;
import com.lumen.order.mapper.OrderCargoMapper;
import com.lumen.order.mapper.OrderMapper;
import com.lumen.order.mapper.OrderServiceItemMapper;
import com.lumen.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 货运委托单业务实现 (Iteration 4.6).
 *
 * <p><b>租户</b>: {@code tenant_id} 由 {@code MyMetaObjectHandler} 在插入时按
 * {@code TenantContext} 自动填; 查询侧由 {@code MybatisPlusConfig} 的
 * {@code TenantLineInnerInterceptor} 自动加 {@code AND tenant_id = ?}。本类**不手动**设 tenantId。
 *
 * <p><b>软删</b>: 三个实体的 {@code deleted} 都带 {@code @TableLogic}, MP 自动补 {@code deleted = 0}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderCargoMapper cargoMapper;
    private final OrderServiceItemMapper serviceItemMapper;
    private final NumberGenerator numberGenerator;

    /** 分页 size 上限 —— 与 CsExceptionController 一致。 */
    private static final long MAX_PAGE_SIZE = 200L;

    // ------------------------------------------------------------ 管理端

    @Override
    @Transactional
    public OrderView create(CreateOrderRequest request) {
        return createInternal(request, null, OrderConstants.STATUS_DRAFT);
    }

    @Override
    @Transactional
    public OrderDetailView update(Long id, CreateOrderRequest request) {
        Order order = requireOrder(id);
        if (!OrderConstants.EDITABLE_STATES.contains(order.getOrderStatus())) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_EDITABLE,
                    "当前状态 " + order.getOrderStatus() + " 不可编辑");
        }
        List<CargoRequest> cargo = requireCargo(request);
        List<ServiceItemRequest> items = requireServiceItems(request);

        applyHeader(order, request, null);
        applyUpdateOverrides(order, request);
        orderMapper.updateById(order);

        replaceChildren(order, cargo, items);
        return get(id);
    }

    @Override
    public PageResult<OrderView> page(long current, long size, String status, Long customerId,
                                       String orderNo, String businessType) {
        long effectiveSize = Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE);
        long pageNum = current <= 0 ? 1 : current;

        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<Order>()
                .orderByDesc(Order::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(Order::getOrderStatus, status);
        }
        if (customerId != null) {
            q.eq(Order::getCustomerId, customerId);
        }
        if (businessType != null && !businessType.isBlank()) {
            q.eq(Order::getBusinessType, businessType);
        }
        if (orderNo != null && !orderNo.isBlank()) {
            q.like(Order::getOrderNo, orderNo);
        }

        Page<Order> result = orderMapper.selectPage(new Page<>(pageNum, effectiveSize), q);
        List<OrderView> views = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, result.getTotal(), pageNum, effectiveSize);
    }

    @Override
    public OrderDetailView get(Long id) {
        Order order = requireOrder(id);
        return toDetail(order);
    }

    // ------------------------------------------------------------ 门户端

    @Override
    public PageResult<OrderView> pageByCustomer(Long customerId, long current, long size, String status) {
        return page(current, size, status, customerId, null, null);
    }

    @Override
    public OrderDetailView getOwned(Long id, Long customerId) {
        Order order = orderMapper.selectById(id);
        // 归属不符一律按「不存在」处理 —— 不能泄露「存在但不属于你」
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return toDetail(order);
    }

    /**
     * 客户自助建单。
     *
     * <p><b>customerId 一律以本方法的入参为准</b> —— 门户端的 DTO 里根本没有 customerId 字段,
     * 客户端无法指定归属; 这里再强制覆盖一次, 保证「客户只能给自己建单」。
     */
    @Override
    @Transactional
    public OrderView createForCustomer(Long customerId, CreateOrderRequest request) {
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        return createInternal(request, customerId, OrderConstants.STATUS_DRAFT);
    }

    // ------------------------------------------------------------ 内部

    /**
     * @param customerIdOverride 非空时**覆盖** {@code request.customerId()} (门户端路径);
     *                           为空时用 {@code request.customerId()} (管理端路径)。
     */
    private OrderView createInternal(CreateOrderRequest request, Long customerIdOverride, String status) {
        List<CargoRequest> cargo = requireCargo(request);
        List<ServiceItemRequest> items = requireServiceItems(request);

        Order order = new Order();
        applyHeader(order, request, customerIdOverride);
        applyCreateDefaults(order, request);
        order.setOrderNo(numberGenerator.generate(OrderConstants.NUMBER_RULE_CODE));
        order.setOrderStatus(status);
        orderMapper.insert(order);

        replaceChildren(order, cargo, items);
        return toView(order);
    }

    private List<CargoRequest> requireCargo(CreateOrderRequest request) {
        if (request.cargo() == null || request.cargo().isEmpty()) {
            throw new OrderException(OrderErrorCode.CARGO_EMPTY);
        }
        return request.cargo();
    }

    private List<ServiceItemRequest> requireServiceItems(CreateOrderRequest request) {
        if (request.serviceItems() == null || request.serviceItems().isEmpty()) {
            throw new OrderException(OrderErrorCode.SERVICE_ITEM_EMPTY);
        }
        return request.serviceItems();
    }

    /**
     * 头部字段的部分覆盖 —— 建单与编辑走同一套 setter 口径。
     *
     * <p><b>编辑是部分覆盖, 不是全量替换</b> —— MP 默认 {@code updateStrategy = NOT_NULL},
     * 实体上为 null 的字段不进 SET 子句, 客户端漏传的字段**保留库里的旧值** (而不是被清空)。
     *
     * <p><b>{@code settlementCurrency} / {@code isOversized} 不在这里</b> —— 这两列的「缺省」语义
     * 两条路径不同 (建单落 DDL 默认值, 编辑保留库中原值), 由 {@link #applyCreateDefaults} /
     * {@link #applyUpdateOverrides} 各自决定。
     */
    private void applyHeader(Order order, CreateOrderRequest request, Long customerIdOverride) {
        order.setCustomerId(customerIdOverride != null ? customerIdOverride : request.customerId());
        order.setBusinessType(request.businessType());
        order.setTradeDirection(request.tradeDirection());
        order.setIncoterms(request.incoterms());
        order.setServiceType(request.serviceType());
        order.setOriginAddress(request.originAddress());
        order.setDestAddress(request.destAddress());
        order.setCargoReadyDate(request.cargoReadyDate());
        order.setEtd(request.etd());
        order.setEta(request.eta());
        order.setContainerQty(request.containerQty());
        order.setContainerTypeCode(request.containerTypeCode());
        order.setMarksAndNos(request.marksAndNos());
        order.setCargoDescription(request.cargoDescription());
        order.setTemperatureRange(request.temperatureRange());
        order.setRemark(request.remark());
    }

    /**
     * 建单路径的缺省值 —— 对齐 DDL 的 {@code settlement_currency CHAR(3) NOT NULL DEFAULT 'CNY'}
     * 与 {@code is_oversized TINYINT NOT NULL DEFAULT 0}。
     *
     * <p>请求里的 {@code settlementCurrency} 为 null/blank → 落 {@code CNY};
     * {@code isOversized} 为 null → 落 {@code 0}。
     */
    private static void applyCreateDefaults(Order order, CreateOrderRequest request) {
        order.setSettlementCurrency(
                request.settlementCurrency() == null || request.settlementCurrency().isBlank()
                        ? "CNY" : request.settlementCurrency());
        order.setIsOversized(Boolean.TRUE.equals(request.isOversized()) ? 1 : 0);
    }

    /**
     * 编辑路径的覆盖 —— **null 一律保留库里原值**, 与 MP 的 NOT_NULL 更新策略同口径。
     *
     * <p>{@code settlementCurrency} 为 null **或 blank** → 不设 (库里是 USD 就还是 USD);
     * {@code isOversized} 为 null → 不设; 非 null 则正常写入 —— 所以「取消超限标记」传
     * {@code false} → 落 {@code 0}, 清除动作仍然可用。
     *
     * <p><b>为什么不能一刀切成默认值</b> —— 一个不带这两字段的部分更新请求会把库里的
     * USD / {@code is_oversized=1} 冲掉, 用户没碰过的字段被静默改写。
     */
    private static void applyUpdateOverrides(Order order, CreateOrderRequest request) {
        if (request.settlementCurrency() != null && !request.settlementCurrency().isBlank()) {
            order.setSettlementCurrency(request.settlementCurrency());
        }
        if (request.isOversized() != null) {
            order.setIsOversized(request.isOversized() ? 1 : 0);
        }
    }

    /**
     * 用明细整体替换子表, 然后把聚合值回写主表。
     *
     * <p>先软删旧的再插新的 —— 简单且不会留下孤儿行; 委托单明细量小 (几十行),
     * 整表替换的写放大可以接受。
     *
     * <p>聚合值同时落在两处: (1) 一条**只含 4 个聚合列 + MP 自动维护的 {@code updated_at}** 的 UPDATE 语句
     * (SET 子句实际 5 列), 不碰主表其它业务列 —— {@code updated_at} 必然进 SET:
     * {@code BaseEntity.updatedAt} 标的是 {@code fill = INSERT_UPDATE}, 而 MP 的
     * {@code TableFieldInfo.getSqlSet} 对 fill 字段**不做 null 判断**;
     * (2) 传进来的 {@code order} 实例 —— {@code create()} 直接拿它拼 {@code OrderView} 返回,
     * 聚合值必须在这份实例上可见, 否则建单接口返回的汇总恒为空。
     *
     * <p>WHERE 子句由 MP 拼: {@code id = ?} + 逻辑删除条件 {@code deleted = 0} +
     * 租户条件 {@code tenant_id = ?} (多租户拦截器只加 WHERE, 不加 SET)。
     */
    private void replaceChildren(Order order, List<CargoRequest> cargo, List<ServiceItemRequest> items) {
        Long orderId = order.getId();
        cargoMapper.delete(new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, orderId));
        serviceItemMapper.delete(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, orderId));

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        boolean anyDangerous = false;

        for (CargoRequest r : cargo) {
            OrderCargo row = new OrderCargo();
            row.setOrderId(orderId);
            row.setCargoNameCn(r.cargoNameCn());
            row.setCargoNameEn(r.cargoNameEn());
            row.setHsCode(r.hsCode());
            row.setPkgType(r.pkgType());
            row.setPkgQty(nz(r.pkgQty(), 3));
            row.setUnit(r.unit());
            row.setWeightKg(nz(r.weightKg(), 3));
            row.setNetWeightKg(opt(r.netWeightKg(), 3));
            row.setVolumeCbm(nz(r.volumeCbm(), 3));
            row.setLengthCm(opt(r.lengthCm(), 3));
            row.setWidthCm(opt(r.widthCm(), 3));
            row.setHeightCm(opt(r.heightCm(), 3));
            row.setIsStackable(r.isStackable() == null ? 1 : (r.isStackable() ? 1 : 0));
            row.setCargoValue(opt(r.cargoValue(), 2));
            row.setCurrencyCode(r.currencyCode());
            row.setBrandName(r.brandName());
            row.setModel(r.model());
            row.setIsDangerous(Boolean.TRUE.equals(r.isDangerous()) ? 1 : 0);
            row.setUnNo(r.unNo());
            row.setDangerClass(r.dangerClass());
            row.setRemark(r.remark());
            cargoMapper.insert(row);

            totalQty = totalQty.add(nz(r.pkgQty(), 3));
            totalWeight = totalWeight.add(nz(r.weightKg(), 3));
            totalVolume = totalVolume.add(nz(r.volumeCbm(), 3));
            anyDangerous = anyDangerous || Boolean.TRUE.equals(r.isDangerous());
        }

        for (ServiceItemRequest r : items) {
            OrderServiceItem row = new OrderServiceItem();
            row.setOrderId(orderId);
            row.setFeeType(r.feeType());
            row.setFeeName(r.feeName());
            row.setFeeCategory(r.feeCategory());
            row.setCalcBasis(r.calcBasis());
            row.setQty(nz(r.qty(), 3));
            row.setUnitPrice(nz(r.unitPrice(), 4));
            row.setAmount(nz(r.amount(), 2));
            row.setCurrencyCode(r.currencyCode() == null || r.currencyCode().isBlank()
                    ? "CNY" : r.currencyCode());
            row.setSupplierId(r.supplierId());
            row.setCostAmount(opt(r.costAmount(), 2));
            row.setIsMandatory(Boolean.TRUE.equals(r.isMandatory()) ? 1 : 0);
            row.setSource(OrderConstants.SRC_MANUAL);
            row.setRemark(r.remark());
            serviceItemMapper.insert(row);
        }

        Order agg = new Order();
        agg.setId(orderId);
        agg.setTotalQty(totalQty);
        agg.setTotalWeightKg(totalWeight);
        agg.setTotalVolumeCbm(totalVolume);
        agg.setIsDangerous(anyDangerous ? 1 : 0);
        orderMapper.updateById(agg);

        order.setTotalQty(totalQty);
        order.setTotalWeightKg(totalWeight);
        order.setTotalVolumeCbm(totalVolume);
        order.setIsDangerous(anyDangerous ? 1 : 0);
    }

    private Order requireOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderView toView(Order o) {
        return new OrderView(
                o.getId(), o.getOrderNo(), o.getCustomerId(), o.getBusinessType(),
                o.getTradeDirection(), o.getIncoterms(), o.getServiceType(),
                o.getOriginAddress(), o.getDestAddress(),
                o.getTotalQty(), o.getTotalWeightKg(), o.getTotalVolumeCbm(),
                o.getOrderStatus(), o.getSettlementCurrency(),
                o.getEtd(), o.getEta(), o.getCreatedAt());
    }

    private OrderDetailView toDetail(Order o) {
        List<OrderCargo> cargoRows = cargoMapper.selectList(
                new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, o.getId()));
        List<OrderServiceItem> itemRows = serviceItemMapper.selectList(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, o.getId()));

        List<CargoView> cargoViews = new ArrayList<>();
        for (OrderCargo c : cargoRows) {
            cargoViews.add(new CargoView(c.getId(), c.getCargoNameCn(), c.getCargoNameEn(),
                    c.getHsCode(), c.getPkgType(), c.getPkgQty(), c.getUnit(), c.getWeightKg(),
                    c.getNetWeightKg(), c.getVolumeCbm(), c.getLengthCm(), c.getWidthCm(),
                    c.getHeightCm(), c.getIsStackable(), c.getCargoValue(), c.getCurrencyCode(),
                    c.getBrandName(), c.getModel(), c.getIsDangerous(), c.getUnNo(),
                    c.getDangerClass(), c.getRemark()));
        }
        List<ServiceItemView> itemViews = new ArrayList<>();
        for (OrderServiceItem s : itemRows) {
            itemViews.add(new ServiceItemView(s.getId(), s.getFeeType(), s.getFeeName(),
                    s.getFeeCategory(), s.getCalcBasis(), s.getQty(), s.getUnitPrice(),
                    s.getAmount(), s.getCurrencyCode(), s.getSupplierId(), s.getCostAmount(),
                    s.getIsMandatory(), s.getSource(), s.getRemark()));
        }

        return new OrderDetailView(
                o.getId(), o.getOrderNo(), o.getCustomerId(), o.getBusinessType(),
                o.getTradeDirection(), o.getIncoterms(), o.getServiceType(),
                o.getOriginAddress(), o.getDestAddress(),
                o.getTotalQty(), o.getTotalWeightKg(), o.getTotalVolumeCbm(),
                o.getOrderStatus(), o.getSettlementCurrency(),
                o.getEtd(), o.getEta(), o.getCreatedAt(),
                o.getCargoReadyDate(), o.getContainerQty(), o.getContainerTypeCode(),
                o.getMarksAndNos(), o.getCargoDescription(), o.getIsDangerous(),
                o.getIsOversized(), o.getTemperatureRange(), o.getRemark(),
                o.getCancelReason(), o.getCloseReason(), o.getVoidReason(),
                o.getSubmittedAt(), o.getConfirmedAt(), o.getDeliveredAt(),
                o.getSettledAt(), o.getClosedAt(), o.getCreatedBy(), o.getUpdatedBy(),
                cargoViews, itemViews, List.of());
    }

    /**
     * NOT NULL 的数量/金额列 —— null 落 0, 非 null 截到列的 scale。
     *
     * <p><b>必须截位</b> —— MySQL 对超出 DECIMAL scale 的小数是**静默舍入** (不报错),
     * 不显式 setScale 的话 12.12345 会悄悄变成 12.123。
     */
    private static BigDecimal nz(Double v, int scale) {
        return v == null ? BigDecimal.ZERO.setScale(scale)
                : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 可空的金额/尺寸列 —— null 保持 null (不把「没填」变成 0), 非 null 截到列的 scale。
     */
    private static BigDecimal opt(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}

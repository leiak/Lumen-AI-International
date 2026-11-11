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
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderCargoMapper cargoMapper;
    private final OrderServiceItemMapper serviceItemMapper;
    private final NumberGenerator numberGenerator;

    /** 分页 size 上限 —— 超过 200 一律截到 200。 */
    private static final long MAX_PAGE_SIZE = 200L;
    /** size <= 0 时的兜底值。 */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    // ------------------------------------------------------------ 数值列的 DECIMAL 口径
    //
    // 列宽来自 V42 迁移 (自己又核了一遍 t_order / t_order_cargo / t_order_service_item 三张表):
    //   DECIMAL(18,3) → pkg_qty / weight_kg / net_weight_kg / volume_cbm /
    //                   length_cm / width_cm / height_cm /
    //                   t_order_service_item.qty /
    //                   t_order.total_qty / total_weight_kg / total_volume_cbm
    //   DECIMAL(18,2) → cargo_value / t_order_service_item.amount / cost_amount
    //   DECIMAL(18,4) → t_order_service_item.unit_price

    /** {@code DECIMAL(18,3)} 的小数位数。 */
    private static final int SCALE_3 = 3;
    /** {@code DECIMAL(18,2)} 的小数位数。 */
    private static final int SCALE_2 = 2;
    /** {@code DECIMAL(18,4)} 的小数位数。 */
    private static final int SCALE_4 = 4;

    /** {@code DECIMAL(18,2)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_2 = new BigDecimal("9999999999999999.99");
    /** {@code DECIMAL(18,3)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_3 = new BigDecimal("999999999999999.999");
    /** {@code DECIMAL(18,4)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_4 = new BigDecimal("99999999999999.9999");

    // ------------------------------------------------------------ 管理端

    @Override
    @Transactional
    public OrderView create(CreateOrderRequest request) {
        return createInternal(request, null, OrderConstants.STATUS_DRAFT);
    }

    /**
     * 编辑委托单 (仅 DRAFT / PENDING_CONFIRM 可编辑)。
     *
     * <p><b>编辑不改归属</b> —— {@code customerId} 只在建单时定; 编辑路径一律不动它,
     * 客户端在 body 里带 {@code customerId} 一律无效。要把单子换到别的客户名下, 请另开一张单。
     *
     * <p><b>编辑是部分覆盖, 不是全量替换</b> —— MP 默认 {@code updateStrategy = NOT_NULL},
     * 实体上为 null 的字段不进 SET 子句, 客户端漏传的字段**保留库里的旧值**。
     * <p><b>但这只对「可空的标量字段」成立</b> —— 头部 4 个枚举
     * (businessType / tradeDirection / incoterms / serviceType) 与 cargo / serviceItems
     * 两个集合是**必填**的: 枚举为空或非法 → {@link OrderErrorCode#ENUM_VALUE_INVALID} (400_106),
     * 集合为空 → {@link OrderErrorCode#CARGO_EMPTY} (400_101) /
     * {@link OrderErrorCode#SERVICE_ITEM_EMPTY} (400_102)。
     * 换句话说 PUT **不能**只带要改的那几个字段 —— 这几个是必带的。
     *
     * <p><b>已知限制: 可空列无法被显式清空</b> —— 同一条 NOT_NULL 策略的另一面:
     * 客户端传 {@code null} 表示「不改」, 没有办法表达「把这个可空字段置空」。
     * 需要清空的字段 (如 {@code remark} / {@code cargoDescription}) 当前只能靠
     * 其它手段 (直接改库或下一切片的「显式清空」入参) 处理。
     *
     * <p><b>乐观锁</b> —— 头部 UPDATE 命中 0 行说明期间被别人改过, 抛
     * {@link OrderErrorCode#ORDER_CONFLICT}; 冲突判定**在明细软删/重插之前**,
     * 所以冲突时一行明细都不动。
     *
     * <p><b>乐观锁的边界 (别把它当成防住了 lost update)</b> —— 实体上的 version 来自
     * 请求开始时的 {@code selectById}, 客户端**不能**传 version。所以乐观锁只挡
     * 「本次请求执行窗口内」的并发; 「用户 A 打开表单 → 用户 B 改过 → A 提交」这种
     * **陈旧表单覆盖仍然会静默发生**。要真 CAS, version 得进 DTO 由客户端回传并比对 ——
     * 本轮没做, 这里写明边界。
     *
     * <p><b>两条 UPDATE 的 version 是接力的</b> —— 头部 UPDATE 走完, MP 的
     * {@code OptimisticLockerInnerInterceptor} 会把新版本 (v+1) <b>写回实体</b>;
     * 紧接着的聚合回写那条 {@code agg} 因此带的是 v+1, 它自己再顶到 v+2。
     * 两步用的是同一个 {@code order} 实例, 所以这个接力成立 (v → v+1 → v+2)。
     *
     * <p><b>{@code updated_at} 的前进靠的是聚合回写那条 UPDATE</b> —— 头部 UPDATE 的
     * {@code order} 是**从库里加载来的实体**, {@code updated_at} 已有值; 而 MP 的
     * {@code strictUpdateFill} 语义是「已有值不覆盖」→ 头部 UPDATE 写回去的
     * {@code updated_at} 还是旧值 (DDL 上的 {@code ON UPDATE CURRENT_TIMESTAMP}
     * 也因此被显式赋值压掉)。真正把它刷成 now 的是聚合回写那条 {@code agg}
     * —— 它是新实体, {@code updated_at} 为 null, 被 fill 成 now。
     * 也就是说「{@code updated_at} 会前进」这件事**依赖聚合回写恰好跑在最后**;
     * 哪天聚合回写被挪走或改成异步, {@code updated_at} 会静默冻结 —— 这里写明这个耦合。
     */
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
        requireEnums(request);
        requireNumerics(request);
        // 聚合同样在写库之前算 (与建单路径同一口径)
        CargoAggregate summary = aggregateCargo(cargo);

        applyHeader(order, request);
        applyUpdateOverrides(order, request);
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            throw new OrderException(OrderErrorCode.ORDER_CONFLICT,
                    "委托单已被他人修改, 请刷新后重试");
        }

        replaceChildren(order, cargo, items, summary);
        return get(id);
    }

    @Override
    public PageResult<OrderView> page(long current, long size, String status, Long customerId,
                                       String orderNo, String businessType) {
        long effectiveSize = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
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
        // customerId 为 null 时 MP 会拼出 `customer_id = null` → 恒空页、且不报错 ——
        // 与 getOwned 显式挡 null 的口径不一致, 这里同样显式挡成业务异常。
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        return page(current, size, status, customerId, null, null);
    }

    @Override
    public OrderDetailView getOwned(Long id, Long customerId) {
        // customerId 为 null 时下面的 equals 会 NPE → 500; 显式挡成业务异常
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
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
     * <p><b>customerId 一律以本方法的入参为准</b> —— 门户端 Task 7 会新建不带 customerId 字段的
     * DTO ({@code com.lumen.customer.dto.PortalFreightOrderRequest}); 本方法目前**复用管理端的
     * {@link CreateOrderRequest}** (那个 DTO 是带 customerId 的), 因此这里**强制覆盖** customerId ——
     * 客户端在 body 里传什么都不生效, 保证「客户只能给自己建单」。
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
        requireEnums(request);
        requireNumerics(request);
        // 聚合同样在写库之前算 —— 合计撑爆 DECIMAL(18,3) 时, 主表一行都还没插
        CargoAggregate summary = aggregateCargo(cargo);

        Order order = new Order();
        // 归属只在建单时定 —— 编辑路径永远不动 customerId (见 applyHeader 的说明)
        Long effectiveCustomerId = customerIdOverride != null ? customerIdOverride : request.customerId();
        // 归属不能为空 —— t_order.customer_id 是 NOT NULL, 空值一路走到 INSERT 就是 MySQL 1364 → 500。
        // DTO 上的 @NotNull 只在 HTTP 路径生效, 内部调用方绕过校验器 → 这里必须自己挡一道。
        if (effectiveCustomerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        order.setCustomerId(effectiveCustomerId);
        applyHeader(order, request);
        applyCreateDefaults(order, request);
        order.setOrderNo(numberGenerator.generate(OrderConstants.NUMBER_RULE_CODE));
        order.setOrderStatus(status);
        orderMapper.insert(order);

        replaceChildren(order, cargo, items, summary);
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
     * 受控词表校验 —— 头部 4 个必填枚举 + 服务项的 3 个。
     *
     * <p><b>为什么落在 service 而不是 DTO</b> —— DTO 上要用 {@code @Pattern} 把每个枚举值
     * 抄成正则, 抄漏一处就是一个洞, 而且码位会变成 {@code PARAM_INVALID (4001)};
     * {@code OrderConstants} 的各组 List 本来就是「受控词表的唯一权威」, 在 service 层校验
     * 才能一处定义、一处引用, 码位也留在 {@link OrderErrorCode} 里 (ENUM_VALUE_INVALID)。
     *
     * <p><b>可选字段只在非空时校验</b> —— {@code feeCategory} 是可空列, null / blank 放行。
     */
    private static void requireEnums(CreateOrderRequest request) {
        requireEnum(request.businessType(), OrderConstants.BUSINESS_TYPES, "businessType");
        requireEnum(request.tradeDirection(), OrderConstants.TRADE_DIRECTIONS, "tradeDirection");
        requireEnum(request.incoterms(), OrderConstants.INCOTERMS, "incoterms");
        requireEnum(request.serviceType(), OrderConstants.SERVICE_TYPES, "serviceType");
        for (ServiceItemRequest item : request.serviceItems()) {
            requireEnum(item.feeType(), OrderConstants.FEE_TYPES, "feeType");
            requireEnum(item.calcBasis(), OrderConstants.CALC_BASES, "calcBasis");
            if (item.feeCategory() != null && !item.feeCategory().isBlank()) {
                requireEnum(item.feeCategory(), OrderConstants.FEE_CATEGORIES, "feeCategory");
            }
        }
    }

    /** 取值不在受控词表里 → ENUM_VALUE_INVALID (400_106)。 */
    private static void requireEnum(String value, List<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new OrderException(OrderErrorCode.ENUM_VALUE_INVALID,
                    field + " 取值非法: " + value + " (合法取值: " + String.join(" / ", allowed) + ")");
        }
    }

    /**
     * 数值列的上界预检 —— **在任何写库之前**跑一遍。
     *
     * <p><b>为什么要有这一层</b> —— 越界的数值在 {@link #replaceChildren} 里转换时才会被发现,
     * 那时主表已经 INSERT/UPDATE 过了。虽然 {@code @Transactional} 会把整段回滚 (不会留下脏数据),
     * 但「参数不合法就别写库」是更干净的口径 —— 失败请求一行都不该碰数据库。
     */
    private static void requireNumerics(CreateOrderRequest request) {
        for (CargoRequest r : request.cargo()) {
            checkNumber(r.pkgQty(), SCALE_3, "pkg_qty");
            checkNumber(r.weightKg(), SCALE_3, "weight_kg");
            checkNumber(r.netWeightKg(), SCALE_3, "net_weight_kg");
            checkNumber(r.volumeCbm(), SCALE_3, "volume_cbm");
            checkNumber(r.lengthCm(), SCALE_3, "length_cm");
            checkNumber(r.widthCm(), SCALE_3, "width_cm");
            checkNumber(r.heightCm(), SCALE_3, "height_cm");
            checkNumber(r.cargoValue(), SCALE_2, "cargo_value");
        }
        for (ServiceItemRequest s : request.serviceItems()) {
            checkNumber(s.qty(), SCALE_3, "qty");
            checkNumber(s.unitPrice(), SCALE_4, "unit_price");
            checkNumber(s.amount(), SCALE_2, "amount");
            checkNumber(s.costAmount(), SCALE_2, "cost_amount");
        }
    }

    /** null 直接放行 (可空列本来就不该有值)。 */
    private static void checkNumber(Double v, int scale, String column) {
        if (v != null) {
            toColumnValue(v, scale, column);
        }
    }

    /**
     * 头部字段的部分覆盖 —— 建单与编辑走同一套 setter 口径。
     *
     * <p><b>不写 {@code customerId}</b> —— 归属只在建单时定 ({@link #createInternal} 里单独设),
     * 编辑路径一律不动它: 否则管理端一次 PUT 带 {@code customerId=999} 就能把单子转到别的客户名下。
     * 要换客户请另开一张单。
     *
     * <p><b>编辑是部分覆盖, 不是全量替换</b> —— MP 默认 {@code updateStrategy = NOT_NULL},
     * 实体上为 null 的字段不进 SET 子句, 客户端漏传的字段**保留库里的旧值** (而不是被清空)。
     *
     * <p><b>{@code settlementCurrency} / {@code isOversized} 不在这里</b> —— 这两列的「缺省」语义
     * 两条路径不同 (建单落 DDL 默认值, 编辑保留库中原值), 由 {@link #applyCreateDefaults} /
     * {@link #applyUpdateOverrides} 各自决定。
     */
    private void applyHeader(Order order, CreateOrderRequest request) {
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
     * <p><b>已知代价: 明细历史行线性堆积, 且明细 id 每次编辑都会变</b> ——
     * {@code @TableLogic} 的软删只是把旧行的 {@code deleted} 置 1, 行还在表里;
     * 每次编辑都插一批新行, 编辑 N 次 → 明细表里躺着 N 批历史行 (第 N 批之外全是 deleted=1)。
     * 明细的 {@code id} 也随之每次重新分配 (旧 id 不会复用) —— 任何按明细 id 的外部引用
     * (下游单据、报表快照) 都不能假定它稳定。本轮不做明细的版本化对比, 只在这里写明。
     *
     * <p>聚合值同时落在两处: (1) 一条**只含 4 个聚合列 + {@code version} + MP 自动维护的
     * {@code updated_at}** 的 UPDATE 语句 (SET 子句实际 6 列), 不碰主表其它业务列 ——
     * {@code updated_at} 必然进 SET: {@code BaseEntity.updatedAt} 标的是 {@code fill = INSERT_UPDATE},
     * 而 MP 的 {@code TableFieldInfo.getSqlSet} 对 fill 字段**不做 null 判断**;
     * {@code version} 进 SET 是因为它非 null, 且默认 {@code updateStrategy = NOT_NULL} 的
     * {@code <if>} 判断放行;
     * (2) 传进来的 {@code order} 实例 —— {@code create()} 直接拿它拼 {@code OrderView} 返回,
     * 聚合值必须在这份实例上可见, 否则建单接口返回的汇总恒为空。
     *
     * <p>WHERE 子句由 MP 拼: {@code id = ?} + 逻辑删除条件 {@code deleted = 0} +
     * 租户条件 {@code tenant_id = ?} (多租户拦截器只加 WHERE, 不加 SET) +
     * 乐观锁条件 {@code version = ?} (取的是 {@code MP_OPTLOCK_VERSION_ORIGINAL}, 即实体上的旧版本值)。
     *
     * <p><b>聚合回写也带乐观锁</b> —— {@code agg} 上带 {@code version}, MP 因此在 SET 里写
     * {@code version = <新值>} (新值 = 旧版本 + 1, 由
     * {@code OptimisticLockerInnerInterceptor.doOptimisticLocker} 算出后写回实体),
     * 在 WHERE 里写 {@code version = <旧值>}; 命中 0 行说明
     * 期间被别人改过 → 抛 {@link OrderErrorCode#ORDER_CONFLICT} (整个事务回滚)。
     * {@code version} 取 {@code order.getVersion()} —— 编辑路径里头部 UPDATE 已把版本顶到 v+1
     * (MP 会把新版本写回实体), 建单路径里 insert 之后实体上的 version 与库里一致 (都是 0)。
     */
    private void replaceChildren(Order order, List<CargoRequest> cargo, List<ServiceItemRequest> items,
                                 CargoAggregate summary) {
        // 聚合 (summary) 由调用方算好传进来 —— 调用方在**任何写库之前**就算,
        // 这样「合计溢出」和「单行越界」是同一口径: 失败请求一行都不碰数据库。
        Long orderId = order.getId();
        cargoMapper.delete(new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, orderId));
        serviceItemMapper.delete(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, orderId));

        for (CargoRequest r : cargo) {
            OrderCargo row = new OrderCargo();
            row.setOrderId(orderId);
            row.setCargoNameCn(r.cargoNameCn());
            row.setCargoNameEn(r.cargoNameEn());
            row.setHsCode(r.hsCode());
            row.setPkgType(r.pkgType());
            row.setPkgQty(nz(r.pkgQty(), SCALE_3, "pkg_qty"));
            row.setUnit(r.unit());
            row.setWeightKg(nz(r.weightKg(), SCALE_3, "weight_kg"));
            row.setNetWeightKg(opt(r.netWeightKg(), SCALE_3, "net_weight_kg"));
            row.setVolumeCbm(nz(r.volumeCbm(), SCALE_3, "volume_cbm"));
            row.setLengthCm(opt(r.lengthCm(), SCALE_3, "length_cm"));
            row.setWidthCm(opt(r.widthCm(), SCALE_3, "width_cm"));
            row.setHeightCm(opt(r.heightCm(), SCALE_3, "height_cm"));
            row.setIsStackable(r.isStackable() == null ? 1 : (r.isStackable() ? 1 : 0));
            row.setCargoValue(opt(r.cargoValue(), SCALE_2, "cargo_value"));
            row.setCurrencyCode(r.currencyCode());
            row.setBrandName(r.brandName());
            row.setModel(r.model());
            row.setIsDangerous(Boolean.TRUE.equals(r.isDangerous()) ? 1 : 0);
            row.setUnNo(r.unNo());
            row.setDangerClass(r.dangerClass());
            row.setRemark(r.remark());
            cargoMapper.insert(row);
        }

        for (ServiceItemRequest r : items) {
            OrderServiceItem row = new OrderServiceItem();
            row.setOrderId(orderId);
            row.setFeeType(r.feeType());
            row.setFeeName(r.feeName());
            row.setFeeCategory(r.feeCategory());
            row.setCalcBasis(r.calcBasis());
            row.setQty(nz(r.qty(), SCALE_3, "qty"));
            row.setUnitPrice(nz(r.unitPrice(), SCALE_4, "unit_price"));
            row.setAmount(nz(r.amount(), SCALE_2, "amount"));
            row.setCurrencyCode(r.currencyCode() == null || r.currencyCode().isBlank()
                    ? "CNY" : r.currencyCode());
            row.setSupplierId(r.supplierId());
            row.setCostAmount(opt(r.costAmount(), SCALE_2, "cost_amount"));
            row.setIsMandatory(Boolean.TRUE.equals(r.isMandatory()) ? 1 : 0);
            row.setSource(OrderConstants.SRC_MANUAL);
            row.setRemark(r.remark());
            serviceItemMapper.insert(row);
        }

        Order agg = new Order();
        agg.setId(orderId);
        agg.setTotalQty(summary.totalQty());
        agg.setTotalWeightKg(summary.totalWeightKg());
        agg.setTotalVolumeCbm(summary.totalVolumeCbm());
        agg.setIsDangerous(summary.anyDangerous() ? 1 : 0);
        agg.setVersion(order.getVersion());
        int rows = orderMapper.updateById(agg);
        if (rows == 0) {
            throw new OrderException(OrderErrorCode.ORDER_CONFLICT,
                    "委托单已被他人修改, 请刷新后重试");
        }

        order.setTotalQty(summary.totalQty());
        order.setTotalWeightKg(summary.totalWeightKg());
        order.setTotalVolumeCbm(summary.totalVolumeCbm());
        order.setIsDangerous(summary.anyDangerous() ? 1 : 0);
    }

    /** 货物明细的聚合结果。 */
    private record CargoAggregate(BigDecimal totalQty, BigDecimal totalWeightKg,
                                  BigDecimal totalVolumeCbm, boolean anyDangerous) {}

    /**
     * 算明细的聚合值 —— **在动数据库之前**算, 合计溢出当场抛。
     *
     * <p><b>为什么单独拎出来</b> —— 三个合计列本身也是 {@code DECIMAL(18,3)}, 多行相加
     * 一样可能撑爆 (例如两行 {@code pkg_qty = 999999999999999.9} 相加就超了)。
     * 放在明细 INSERT 之后才发现, 就要靠事务回滚来收拾; 放在这里, 失败请求**一行都不碰数据库**,
     * 与 {@link #requireNumerics} 是同一口径。
     */
    private static CargoAggregate aggregateCargo(List<CargoRequest> cargo) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        boolean anyDangerous = false;

        for (CargoRequest r : cargo) {
            totalQty = totalQty.add(nz(r.pkgQty(), SCALE_3, "pkg_qty"));
            totalWeight = totalWeight.add(nz(r.weightKg(), SCALE_3, "weight_kg"));
            totalVolume = totalVolume.add(nz(r.volumeCbm(), SCALE_3, "volume_cbm"));
            anyDangerous = anyDangerous || Boolean.TRUE.equals(r.isDangerous());
        }

        requireWithinUpperBound(totalQty, SCALE_3, "total_qty");
        requireWithinUpperBound(totalWeight, SCALE_3, "total_weight_kg");
        requireWithinUpperBound(totalVolume, SCALE_3, "total_volume_cbm");

        return new CargoAggregate(totalQty, totalWeight, totalVolume, anyDangerous);
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
     * NOT NULL 的数量/金额列 —— null 落 0, 非 null 截到列的 scale 并检查装不装得下。
     *
     * <p><b>必须截位</b> —— MySQL 对超出 DECIMAL scale 的小数是**静默舍入** (不报错),
     * 不显式 setScale 的话 12.12345 会悄悄变成 12.123。
     *
     * <p><b>越界必须在这里拦, 不能让它撞 MySQL</b> —— 客户端传 {@code 1e15} 超出
     * {@code DECIMAL(18,3)}, MySQL 报 1264 out of range → {@code DataIntegrityViolationException}
     * → {@code GlobalExceptionHandler} 没有对应分支 → HTTP 500; 传 {@code 1e999} 炸得更早:
     * Jackson 把它解析成 {@code Double.POSITIVE_INFINITY}, {@code BigDecimal.valueOf} 抛
     * {@code NumberFormatException} → 也是 500。两种都在这里变成 400 (NUMERIC_OUT_OF_RANGE)。
     */
    private static BigDecimal nz(Double v, int scale, String column) {
        return v == null ? BigDecimal.ZERO.setScale(scale) : toColumnValue(v, scale, column);
    }

    /**
     * 可空的金额/尺寸列 —— null 保持 null (不把「没填」变成 0), 非 null 截到列的 scale。
     */
    private static BigDecimal opt(Double v, int scale, String column) {
        return v == null ? null : toColumnValue(v, scale, column);
    }

    /** double → 列值: 先挡非有限数, 再按列 scale 截位, 再挡负数, 最后查越界。 */
    private static BigDecimal toColumnValue(double v, int scale, String column) {
        if (!Double.isFinite(v)) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 数值非法 (不是有限数): " + v);
        }
        BigDecimal value = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
        // 负数在这里再挡一道 —— DTO 上的 @PositiveOrZero 已经挡过一次, 但 service 不该
        // 只依赖 DTO: 门户端 DTO 是 Task 7 新写的, 内部调用更是直接绕过校验器。
        if (value.signum() < 0) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 不能为负数: " + value.toPlainString());
        }
        requireWithinUpperBound(value, scale, column);
        return value;
    }

    /**
     * 检查一个值**装不装得进** {@code DECIMAL(18, scale)} —— 整数位 {@code 18 - scale} 位。
     *
     * <p>比较用**截位之后**的值 —— MySQL 存的是截位/舍入后的数, 越界判定必须跟它一致。
     *
     * <p><b>只查上界, 不查负数</b> —— 负数在入口 {@link #toColumnValue} 就被挡了
     * (那里才是「单值」的唯一入口); 这个方法负责的是「列的容量」这回事。
     * 早先这里用的是 {@code value.abs()} 比较, 于是负数会被放过, 而方法名
     * {@code requireInRange} 又让人以为它管的是区间 —— 名实不符, 下一个人照着名字
     * 加一条不经 {@code toColumnValue} 的调用就会踩坑。现在改成**只查上界**,
     * 名字也跟着改成 {@code requireWithinUpperBound}, 职责单一。
     */
    private static void requireWithinUpperBound(BigDecimal value, int scale, String column) {
        if (value == null) {
            return;
        }
        if (value.compareTo(maxOfScale(scale)) > 0) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 超出列宽 DECIMAL(18," + scale + ") 的范围: " + value.toPlainString());
        }
    }

    /** {@code DECIMAL(18, scale)} 的最大绝对值 —— 整数位 18-scale 位全 9, 小数位全 9。 */
    private static BigDecimal maxOfScale(int scale) {
        return switch (scale) {
            case 2 -> MAX_18_2;
            case 3 -> MAX_18_3;
            case 4 -> MAX_18_4;
            default -> throw new IllegalArgumentException("未登记的列 scale: " + scale);
        };
    }
}

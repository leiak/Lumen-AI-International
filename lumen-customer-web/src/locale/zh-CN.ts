/**
 * 4.5A.4-T4.3 — 简体中文 (zh-CN) 文案。
 *
 * 4.5A 阶段页面以静态 import 此模块为主, key/value 同步于 en-US.ts;
 * 真正的 react-i18next 集成留待 4.5B+。
 */

export const zhCN = {
  common: {
    confirm: '确认',
    cancel: '取消',
    save: '保存',
    submit: '提交',
    back: '返回',
    refresh: '刷新',
    loading: '加载中...',
    yes: '是',
    no: '否',
    detail: '详情',
  },
  menu: {
    track: '运单追踪',
    orders: '我的订单',
    createOrder: '下单',
    profile: '我的资料',
    logout: '退出登录',
    reconciliation: '对账单',
    invoice: '发票',
    payment: '付款记录',
    adjustment: '我的调账单',
  },
  billing: {
    reconTitle: '月结对账单',
    invoiceTitle: '发票',
    paymentTitle: '付款记录',
    adjustmentTitle: '我的调账单',
    period: '期间',
    periodClosed: '期间状态',
    closed: '已闭期',
    /**
     * 未闭期的标识 —— **不是**「开放」。
     *
     * 「开放」是绿色 / 好消息的语义, 但未闭期真正的含义是「这期数字还在变,
     * 提异议没有意义」, 语义正好相反。所以未闭期一律显示这个 warning 色标签。
     */
    dataMayChange: '数据可能变动',
    expected: '应收',
    actual: '实收',
    variance: '差异',
    /** 对账状态列 (t_reconciliation.reconciliation_status)。 */
    reconStatus: '对账状态',
    disputeStatus: '异议状态',
    disputed: '已提异议',
    notDisputed: '未提异议',
    viewDetail: '查看详情',
    exportExcel: '导出 Excel',
    exportStarted: '导出已开始, 请留意浏览器下载',
    exportFailed: '导出失败, 请稍后重试',
    reconNotFound: '对账单不存在',
    raiseDispute: '对差异提异议',
    submitDispute: '提交异议',
    disputeReason: '异议理由',
    disputeReasonRequired: '请填写异议理由',
    /** 理由最短 10 字 —— 与后端 FinanceConstants.ADJUSTMENT_REASON_MIN_LEN 对齐。 */
    disputeReasonTooShort: '异议理由至少 10 个字',
    disputeAmount: '主张应收金额 (可选)',
    disputeAmountHint: '留空表示只质疑差额, 不主张具体金额',
    disputeAmountPlaceholder: '例如 -12.00',
    /** 主张金额为 0 无意义 —— 只质疑不主张金额请把这一栏留空。 */
    disputeAmountZero: '主张金额不能为 0 (只质疑差额请留空)',
    disputeSubmitted: (status: string) => `异议已提交, 对应调账单已进入 ${status}`,
    disputeFailed: '异议提交失败, 请稍后重试',
    disputeAmountInvalid: '主张金额超出可表示范围 (最多 14 位整数 + 4 位小数)',
    disputeParamInvalid: '请求参数非法, 请检查后重试',
    disputeNotDisputable: '该期对账单当前不可提异议 (期间未闭期 / 状态非 ADJUSTMENT_CREATED / 已异议过 / 无关联调账单)',
    disputeForbidden: '您没有提异议的权限, 请联系管理员',
    loadFailed: '加载失败, 请稍后重试',
    /** 列表加载失败时的「重试」按钮 —— 失败后商家能原地重试, 不用刷新浏览器。 */
    retry: '重试',
    currency: '币种',
    /**
     * 状态码 → 中文。
     *
     * 取自后端 FinanceConstants 的 INV_STATUS_* / ADJ_STATUS_*:
     * 发票 7 个 (DRAFT / PENDING_L1 / PENDING_L2 / L1_APPROVED / L2_APPROVED /
     * PUSHED / REJECTED) + 调账单 6 个 (DRAFT / PENDING_L1 / PENDING_L2 /
     * L2_APPROVED / POSTED / REJECTED), 去重后 8 个。
     *
     * 另外补了**对账状态** 4 个 (RECON_STATUS_*: PENDING / ADJUSTMENT_CREATED /
     * RESOLVED / WAIVED) —— 这组码只出现在 t_reconciliation 上, 与上面两组
     * 没有重名, 但商家要靠它判断"我这笔差异现在到哪一步了"。
     *
     * 未收录的码原样返回 —— 宁可让商家看到原始码, 也不要静默显示成错的文案。
     */
    statusText: (s: string | null | undefined): string => {
      switch (s) {
        case 'DRAFT': return '草稿';
        case 'PENDING_L1': return '待 L1 审批';
        case 'PENDING_L2': return '待 L2 审批';
        case 'L1_APPROVED': return 'L1 已通过';
        case 'L2_APPROVED': return 'L2 已通过';
        case 'PUSHED': return '已推送';
        case 'REJECTED': return '已驳回';
        case 'POSTED': return '已入账';
        // ---- 对账状态 (t_reconciliation.reconciliation_status) ----
        // PENDING = 还没跑过对账 (这一期压根没有差异记录)
        case 'PENDING': return '未对账';
        // ADJUSTMENT_CREATED = 差异已生成调账单 —— 这是**唯一**能提异议的状态
        case 'ADJUSTMENT_CREATED': return '已建调账单';
        // RESOLVED = 差异已结清
        case 'RESOLVED': return '已结清';
        // WAIVED = 差异已豁免 (财务主动放弃追讨)
        case 'WAIVED': return '已豁免';
        default: return s ?? '-';
      }
    },
    /**
     * 分页器的总条数提示 —— 4.5C-2.1 接了服务端分页后, 商家能翻页看到全部记录,
     * 所以这里只报总数, 不再有「当前仅显示前 N 条 / 请联系客服」那套截断措辞。
     */
    totalCount: (total: number) => `共 ${total} 条`,
    /**
     * 未闭期 → 不能提异议。
     *
     * ⚠️ 这一条的**键名**曾经写反 (旧键说的是「已闭期不能提异议」, 实际拦的是
     * 「**未**闭期不能提异议」) —— 键名带反向语义会误导下一个人, 所以连键名一起改了。
     */
    notClosedNoDispute: '该会计期间尚未闭期, 数字仍可能变动, 不能提异议',
    alreadyDisputed: '本期已提过异议, 财务处理后会推进审批',
    invoiceNo: '发票号',
    netAmount: '不含税金额',
    taxAmount: '税额',
    amount: '金额',
    status: '状态',
    reason: '原因',
    rejectComment: '审批意见',
    noComment: '暂无',
    adjustmentNo: '调账单号',
    paymentDate: '付款日期',
    paymentRef: '付款凭证号',
    paymentSource: '付款方式',
  },
  order: {
    title: '我的订单',
    create: '下单',
    cancel: '取消订单',
    cancelTitle: '确认取消订单',
    cancelReasonPlaceholder: '请填写取消原因',
    column: {
      doNo: '运单号',
      status: '状态',
      origin: '起点',
      destination: '目的地',
      pickupWindow: '取件时间窗',
      createdAt: '下单时间',
      signedAt: '签收时间',
    },
    filter: {
      doNo: '运单号',
      status: '状态',
    },
    detail: {
      title: '订单详情',
      base: '基本信息',
      timeline: '流转记录',
      emptyTimeline: '暂无流转记录',
      cancel: '取消订单',
      notCancellable: '当前状态不可取消',
    },
  },
  login: {
    title: 'Lumen 客户门户',
    subtitle: '登录以查询您的运单与资料',
    username: '用户名',
    password: '密码',
    submit: '登录',
    welcomeBack: '欢迎回来,{name}',
    invalidCredentials: '账号或密码错误,请重试',
  },
  selectCustomer: {
    title: '请选择客户',
    subtitle: '您有多个客户身份,请选择一个继续',
    primaryBadge: '主客户',
    choose: '选择',
  },
  track: {
    title: '运单追踪',
    column: {
      doNo: '运单号',
      status: '状态',
      origin: '起点',
      destination: '目的地',
      recipient: '收货人',
      phone: '联系电话',
      createdAt: '下单时间',
      signedAt: '签收时间',
    },
    filter: {
      doNo: '运单号',
      status: '状态',
    },
    detail: {
      title: '运单详情',
      base: '基本信息',
      timeline: '流转记录',
      pod: '签收凭证',
      weight: '重量',
      volume: '体积',
      weightUnit: 'kg',
      volumeUnit: 'm³',
      emptyTimeline: '暂无流转记录',
      podLoading: '签收凭证加载中...',
      podNone: '暂无签收凭证',
      podPhoto: '照片凭证',
      podSignature: '签名凭证',
      signer: '签收人',
      signedAtLabel: '签收时间',
    },
  },
  status: {
    CREATED: '已创建',
    PENDING_DISPATCH: '待派车',
    DISPATCHED: '已派车',
    IN_TRANSIT: '运输中',
    ARRIVED: '已到货',
    DELIVERED: '已签收',
    EXCEPTION: '异常',
    CANCELLED: '已取消',
    UNKNOWN: '未知',
  },
  profile: {
    title: '我的资料',
    current: '当前资料',
    update: '更新资料',
    customerId: '客户编号',
    note: '备注',
    phone: '联系电话',
    address: '联系地址',
    password: '修改密码(可选)',
    passwordPlaceholder: '留空表示不修改',
    success: '保存成功',
    fail: '保存失败',
  },
  notFound: {
    title: '404',
    description: '您访问的页面不存在',
    back: '返回首页',
  },
};

export type Locale = typeof zhCN;

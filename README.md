# 国际物流系统（Lumen International Logistics）

> 面向货代、跨境电商、进出口企业的全链路国际物流业务平台。
> 本仓库专注于**业务设计文档**，所有业务模型、流程、表结构、状态机、规则均以业务优先原则细化。

---

## 📚 文档导航

所有业务设计文档位于 [`docs/`](./docs/) 目录：

- **[docs/README.md](./docs/README.md)** - 文档总览与阅读建议
- **[docs/01-系统设计总览-v1.0.md](./docs/01-系统设计总览-v1.0.md)** - 系统全局设计
- **[docs/02-业务表结构设计-v2.0.md](./docs/02-业务表结构设计-v2.0.md)** - 业务表结构设计

### 业务模块细化（v3.0）

| # | 模块 | 文档 |
| --- | --- | --- |
| 03 | 基础资料 | [docs/business/master-data/](./docs/business/master-data/) |
| 04 | 询价与报价 | [docs/business/quotation/](./docs/business/quotation/) |
| 05 | 委托单与订单 | [docs/business/order/](./docs/business/order/) |
| 06 | 订舱与操作 | [docs/business/booking/](./docs/business/booking/) |
| 07 | 跟踪与异常 | [docs/business/tracking/](./docs/business/tracking/) |
| 08 | 报关与关务 | [docs/business/customs/](./docs/business/customs/) |
| 09 | 仓储（WMS） | [docs/business/wms/](./docs/business/wms/) |
| 10 | 财务 | [docs/business/finance/](./docs/business/finance/) |
| 11 | CRM 与客服 | [docs/business/crm/](./docs/business/crm/) |
| 12 | 通知与系统 | [docs/business/notification/](./docs/business/notification/) |

---

## 🎯 项目目标

构建一套**业务优先、端到端贯通**的国际物流业务模型，覆盖：

- **询价 → 报价 → 委托 → 订舱 → 操作 → 跟踪 → 报关 → 仓储 → 结算**
- **多运输方式**：海运 / 空运 / 铁路 / 陆运 / 快递 / 多式联运
- **多角色协同**：货主 / 销售 / 操作 / 关务 / 客服 / 财务 / 海外代理
- **多组织运营**：多租户 / 多币种 / 多语言 / 多时区
- **可配置化**：运价 / 附加费 / 费率 / 状态机 / SLA 均可配置

---

## 📖 设计原则

1. **业务优先**：先理清所有业务，再考虑技术实现
2. **端到端贯通**：从询价到结算形成完整业务闭环
3. **多角色协同**：所有业务方在同一平台协作
4. **可扩展性**：支持多组织、多币种、多语言、多运输方式
5. **可配置化**：运价、附加费、费率、状态机均支持配置
6. **状态可追溯**：所有业务单据均有状态机和操作日志

---

## 📦 仓库结构

```
lumen-international-logistics/
├── README.md                 （项目说明）
├── LICENSE                   （许可证）
├── .gitignore                （Git 忽略）
└── docs/                     （设计文档根目录）
    ├── README.md
    ├── 01-系统设计总览-v1.0.md
    ├── 02-业务表结构设计-v2.0.md
    └── business/
        ├── master-data/
        ├── quotation/
        ├── order/
        ├── booking/
        ├── tracking/
        ├── customs/
        ├── wms/
        ├── finance/
        ├── crm/
        ├── notification/
        └── system/
```

---

## 🚧 项目状态

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 第一轮 | 系统总览 + 全局业务结构 | ✅ |
| 第二轮 | 12 个业务模块细化 | ✅ |
| 第三轮（规划） | 流程时序图、状态机转换表、领域事件、接口契约 | 🔲 |
| 第四轮（规划） | 多式联运段状态机、分润分摊、电商成本口径 | 🔲 |

---

## 📝 License

本项目采用 [MIT License](./LICENSE)。

---

**Lumen International Logistics** - 让国际物流业务设计有据可依。
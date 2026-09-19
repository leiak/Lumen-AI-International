-- 为 sys_country 增加 status 列，作为 Iteration 1.5 表驱动状态机的承载字段。
-- 状态码：sys_country 状态机定义在 t_state_transition (V9) 中，状态枚举：DRAFT / ACTIVE / FROZEN / VOID。
ALTER TABLE sys_country
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：sys_country 状态机的状态字段' AFTER name_en;

-- 历史行默认 DRAFT（已存在的国家默认"草稿"语义，新流程要求显式发布为 ACTIVE）。

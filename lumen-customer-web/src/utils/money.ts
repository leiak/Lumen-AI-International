/**
 * 4.5C-2 — 金额展示的共享工具。
 *
 * <p>放在 {@code src/utils/} 而不是 {@code src/types/billing.ts}: 后者是纯类型文件
 * (只有 interface / type, 没有运行时值), 塞一个函数进去会破坏它的定位; 也放在
 * {@code src/services/} 外面 —— 它不是 HTTP 调用, 是纯展示层的格式化。
 */

/**
 * 金额 + 币种的统一渲染。
 *
 * <p>金额为 null 时只显示 '-', <b>不带币种</b> —— 否则会渲染成 "- CNY",
 * 那个横杠看着像个负数, 会被误读成"负 CNY"。
 *
 * <p>不做千分位 / 固定小数位的格式化 —— 精度口径 (12 还是 12.00) 待产品确认,
 * 那是另一个 task。
 */
export function money(v: number | null | undefined, currency?: string | null): string {
  if (v === null || v === undefined) return '-';
  return currency ? `${v} ${currency}` : String(v);
}

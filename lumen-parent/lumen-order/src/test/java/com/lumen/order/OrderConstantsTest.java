package com.lumen.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderConstants 自洽性断言 (Iteration 4.6).
 *
 * <p>锁住四件事:
 * <ul>
 *   <li>9 个状态常量, 无重复;</li>
 *   <li>15 条 (from, to) 转换无重复, 且 from/to 都是已知状态;</li>
 *   <li>终态 (CLOSED / CANCELLED / VOID) 没有任何出边 —— 终态必须只读;</li>
 *   <li>可编辑态 / 终态都是 {@code STATES} 的子集, 且两者互斥 (交集为空)。</li>
 * </ul>
 */
class OrderConstantsTest {

    @Test
    @DisplayName("9 个状态常量, 无重复")
    void nineStates_noDuplicate() {
        assertThat(OrderConstants.STATES).hasSize(9).doesNotHaveDuplicates();
        assertThat(OrderConstants.STATES).contains(
                "DRAFT", "PENDING_CONFIRM", "CONFIRMED", "IN_OPERATION",
                "DELIVERED", "SETTLED", "CLOSED", "CANCELLED", "VOID");
    }

    @Test
    @DisplayName("15 条转换, (from,to) 唯一且两端都是已知状态")
    void fifteenTransitions_consistent() {
        assertThat(OrderConstants.TRANSITIONS).hasSize(15);
        Set<String> seen = new HashSet<>();
        for (OrderConstants.Transition t : OrderConstants.TRANSITIONS) {
            assertThat(OrderConstants.STATES).contains(t.from());
            assertThat(OrderConstants.STATES).contains(t.to());
            assertThat(seen.add(t.from() + "->" + t.to()))
                    .as("转换 %s->%s 重复", t.from(), t.to()).isTrue();
        }
    }

    @Test
    @DisplayName("终态无出边")
    void terminalStates_haveNoOutgoingEdge() {
        List<String> terminals = List.of("CLOSED", "CANCELLED", "VOID");
        for (String term : terminals) {
            assertThat(OrderConstants.TRANSITIONS)
                    .as("终态 %s 不该有出边", term)
                    .noneMatch(t -> t.from().equals(term));
        }
    }

    @Test
    @DisplayName("可编辑态 / 终态都是 STATES 的子集, 且两者互斥")
    void editableAndTerminal_areSubsetsAndDisjoint() {
        assertThat(OrderConstants.EDITABLE_STATES)
                .as("可编辑态必须是 STATES 的子集")
                .isSubsetOf(OrderConstants.STATES);
        assertThat(OrderConstants.TERMINAL_STATES)
                .as("终态必须是 STATES 的子集")
                .isSubsetOf(OrderConstants.STATES);

        // 交集非空 = 有状态既能编辑又是终态 —— 语义自相矛盾。
        assertThat(OrderConstants.EDITABLE_STATES)
                .as("可编辑态与终态必须互斥")
                .doesNotContainAnyElementsOf(OrderConstants.TERMINAL_STATES);
    }
}

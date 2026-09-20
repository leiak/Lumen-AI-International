package com.lumen.masterdata.vo;

import com.lumen.masterdata.entity.SysDictItem;
import lombok.Data;

/**
 * 数据字典项 VO —— 用于前端 {@code useDict} hook 的 valueEnum 渲染。
 *
 * <p>字段命名匹配前端 {@code DictItem} 约定：
 * {@code code} = 字典项编码，{@code text} = 字典项显示文本（来源是实体 {@code label} 字段），
 * {@code status} = ProTable valueEnum 用的 BadgeStatus（success / default）。
 *
 * <p>不直接复用 {@link SysDictItem} 实体是为了：
 * <ol>
 *   <li>切断实体字段（{@code label}/{@code sortOrder}/{@code dictId}）对前端的暴露；</li>
 *   <li>把"启用/停用"映射成 ProTable BadgeStatus，使前端不用再写适配代码。</li>
 * </ol>
 */
@Data
public class SysDictItemVO {

    private String code;
    private String text;
    private String status;

    public static SysDictItemVO fromEntity(SysDictItem e) {
        SysDictItemVO vo = new SysDictItemVO();
        vo.code = e.getCode();
        vo.text = e.getLabel();
        vo.status = mapBadgeStatus(e.getStatus());
        return vo;
    }

    /**
     * 把 sys_dict_item.status (Integer 0/1) 映射成 ProTable BadgeStatus：
     * <ul>
     *   <li>{@code 1} → {@code "success"}（绿）</li>
     *   <li>{@code 0} → {@code "default"}（灰）</li>
     *   <li>{@code null} → {@code "default"}</li>
     * </ul>
     */
    private static String mapBadgeStatus(Integer status) {
        return status != null && status == 1 ? "success" : "default";
    }
}
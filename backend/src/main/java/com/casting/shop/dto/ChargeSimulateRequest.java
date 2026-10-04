package com.casting.shop.dto;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * 配料偏差双方案推演入参。
 *
 * <p>目标重量、某项上限、是否允许新料、回炉料上限共同构成一份「输入版本」，
 * 两套方案（最低补料成本 / 最小成分偏离）必须基于同一份入参同步算出。</p>
 */
@Data
public class ChargeSimulateRequest {

    /** 未出炉炉次 id（装料/熔炼/精炼阶段） */
    private Long heatId;

    /** 目标重量 kg，为空则取炉次计划重量（再无计划重量取已登记出炉重量） */
    private BigDecimal targetWeight;

    /** 是否允许引入当前配料明细之外的新料种 */
    private Boolean allowNewMaterial;

    /** 回炉料占比上限 %，为空按材质默认上限 */
    private BigDecimal scrapLimitRate;

    /** 各料种单次上限 kg 覆盖（键为料种编码），为空或未覆盖的项取全局默认上限 */
    private Map<String, BigDecimal> itemCaps;
}

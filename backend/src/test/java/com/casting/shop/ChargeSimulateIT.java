package com.casting.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casting.shop.common.BizException;
import com.casting.shop.domain.Charge;
import com.casting.shop.domain.ChargeItem;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.repository.ChargeItemRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import com.casting.shop.service.ChargeSimulateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 配料偏差双方案推演冒烟（H2 MySQL 兼容模式）。
 *
 * <p>覆盖：双方案同基准可行且目标值有序、等价方案标无偏好、容量/上限/回炉料冲突的具体提示、
 * 不兼容料项的移除建议、空明细与只有回炉料的冲突、已出炉炉次拒绝、推演只读不改库、
 * 同一输入版本结果一致、大量料项下耗时可控。</p>
 */
@SpringBootTest
class ChargeSimulateIT {

    @Autowired private ChargeSimulateService simulateService;
    @Autowired private ChargeRepository chargeRepository;
    @Autowired private ChargeItemRepository itemRepository;
    @Autowired private HeatRepository heatRepository;
    @Autowired private FurnaceRepository furnaceRepository;

    @BeforeEach
    void clean() {
        itemRepository.deleteAll();
        chargeRepository.deleteAll();
        heatRepository.deleteAll();
        furnaceRepository.deleteAll();
    }

    // ---------------- 造数工具 ----------------

    private Furnace newFurnace(String no, double capacity) {
        Furnace f = new Furnace();
        f.setFurnaceNo(no);
        f.setFurnaceName(no + " 试验炉");
        f.setFurnaceType("INDUCTION");
        f.setCapacityKg(BigDecimal.valueOf(capacity));
        f.setStatus("BUSY");
        f.setCreatedAt(LocalDateTime.now());
        return furnaceRepository.save(f);
    }

    private Heat newHeat(String no, String alloy, String stage, double plan, Furnace f) {
        Heat h = new Heat();
        h.setHeatNo(no);
        h.setFurnaceId(f == null ? null : f.getId());
        h.setFurnaceNo(f == null ? null : f.getFurnaceNo());
        h.setAlloy(alloy);
        h.setPlanWeight(BigDecimal.valueOf(plan));
        h.setStage(stage);
        h.setOperator("测试员");
        h.setStartTime(LocalDateTime.now());
        h.setCreatedAt(LocalDateTime.now());
        return heatRepository.save(h);
    }

    /** 直接落库配料单与明细（不走 create 的偏差校验，便于构造边界场景） */
    private Charge newCharge(Heat heat, String chargeNo, Object[][] items) {
        Charge c = new Charge();
        c.setChargeNo(chargeNo);
        c.setHeatId(heat.getId());
        c.setHeatNo(heat.getHeatNo());
        c.setOperator("测试员");
        c.setChargeTime(LocalDateTime.now());
        double total = 0;
        for (Object[] it : items) {
            total += ((Number) it[1]).doubleValue();
        }
        c.setItemCount(items.length);
        c.setTotalWeight(BigDecimal.valueOf(total));
        c.setTargetWeight(heat.getPlanWeight());
        c.setDeviationRate(BigDecimal.ZERO);
        Charge saved = chargeRepository.save(c);
        for (Object[] it : items) {
            ChargeItem item = new ChargeItem();
            item.setChargeId(saved.getId());
            item.setMaterialCode((String) it[0]);
            item.setMaterialName((String) it[0]);
            item.setWeightKg(BigDecimal.valueOf(((Number) it[1]).doubleValue()));
            item.setRatio(BigDecimal.valueOf(((Number) it[1]).doubleValue() / total * 100)
                    .setScale(2, RoundingMode.HALF_UP));
            itemRepository.save(item);
        }
        return saved;
    }

    private ChargeSimulateRequest req(Long heatId, Double target, Boolean allowNew) {
        ChargeSimulateRequest r = new ChargeSimulateRequest();
        r.setHeatId(heatId);
        r.setTargetWeight(target == null ? null : BigDecimal.valueOf(target));
        r.setAllowNewMaterial(allowNew);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plan(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<String> conflicts(Map<String, Object> result) {
        return (List<String>) result.get("conflicts");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recommendation(Map<String, Object> result) {
        return (Map<String, Object>) result.get("recommendation");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> adjustments(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("adjustments");
    }

    private static double num(Map<String, Object> map, String key) {
        return ((BigDecimal) map.get(key)).doubleValue();
    }

    // ---------------- 1. 双方案同基准可行，成本/偏离目标值有序 ----------------

    @Test
    void dualPlansFeasibleWithOrderedObjectives() {
        Furnace f = newFurnace("F-T-01", 3000);
        Heat h = newHeat("H-T-01", "CARBON_STEEL", "REFINING", 2700, f);
        newCharge(h, "CH-T-01", new Object[][]{
                {"PIG_IRON", 820}, {"SCRAP", 1680}, {"FERRO_MN", 76}, {"CARBURANT", 18}, {"FLUX", 40}});

        Map<String, Object> result = simulateService.simulate(req(h.getId(), 2700.0, false));
        assertTrue(conflicts(result).isEmpty(), "不应有冲突：" + conflicts(result));

        Map<String, Object> a = plan(result, "minCost");
        Map<String, Object> b = plan(result, "minDeviation");
        assertEquals(Boolean.TRUE, a.get("feasible"));
        assertEquals(Boolean.TRUE, b.get("feasible"));

        // 方案一成本不高于方案二，方案二偏离不高于方案一（各自的最优目标）
        assertTrue(num(a, "costTotal") <= num(b, "costTotal") + 0.01);
        assertTrue(num(b, "compositionDeviation") <= num(a, "compositionDeviation") + 0.01);

        // 两套方案共用同一份基准：总重都在允许带内、回炉料占比都不超材质上限 60%
        for (Map<String, Object> p : List.of(a, b)) {
            double total = num(p, "totalWeight");
            assertTrue(total >= 2700 * 0.97 - 0.01 && total <= 2700 * 1.03 + 0.01,
                    "试算总重应在允许带内：" + total);
            assertTrue(num(p, "scrapRate") <= 60.0 + 0.01, "回炉料占比应不超上限：" + p);
            assertNotNull(p.get("deviationRate"));
        }
        // 当前回炉料 63.8% 超限，方案一必须减回炉料
        assertTrue(adjustments(a).stream().anyMatch(adj ->
                "SCRAP".equals(adj.get("materialCode"))
                        && ((BigDecimal) adj.get("deltaKg")).doubleValue() < 0));

        // 推荐：方案一偏离在可控带内且更省钱 → 推荐最低成本，并说明未选方牺牲了什么
        Map<String, Object> rec = recommendation(result);
        assertEquals("MIN_COST", rec.get("preferred"));
        assertNotNull(rec.get("reason"));
        assertFalse(((String) rec.get("sacrifice")).isEmpty(), "必须说明未选方案牺牲了什么");
    }

    // ---------------- 2. 两套结果等价时明确标为无偏好 ----------------

    @Test
    void equivalentPlansMarkedNoPreference() {
        Furnace f = newFurnace("F-T-02", 1500);
        Heat h = newHeat("H-T-02", "CARBON_STEEL", "MELTING", 1000, f);
        // 当前明细即碳钢标准配比（32/58/1/3/1/5），总重恰好在允许带内
        newCharge(h, "CH-T-02", new Object[][]{
                {"PIG_IRON", 320}, {"SCRAP", 580}, {"FERRO_SI", 10},
                {"FERRO_MN", 30}, {"CARBURANT", 10}, {"FLUX", 50}});

        Map<String, Object> result = simulateService.simulate(req(h.getId(), 1000.0, false));
        Map<String, Object> a = plan(result, "minCost");
        Map<String, Object> b = plan(result, "minDeviation");
        assertEquals(Boolean.TRUE, a.get("feasible"));
        assertEquals(Boolean.TRUE, b.get("feasible"));
        assertTrue(adjustments(a).isEmpty(), "已达标不应有调整建议");
        assertTrue(adjustments(b).isEmpty(), "已达标不应有调整建议");
        assertEquals("NONE", recommendation(result).get("preferred"), "两套结果等价应标无偏好");
    }

    // ---------------- 3. 目标重量与炉体容量矛盾：指出具体冲突，不给误导推荐 ----------------

    @Test
    void capacityConflictReportedNotMisleading() {
        Furnace f = newFurnace("F-T-03", 3000);
        Heat h = newHeat("H-T-03", "CARBON_STEEL", "REFINING", 2700, f);
        newCharge(h, "CH-T-03", new Object[][]{{"PIG_IRON", 820}, {"SCRAP", 1680}});

        Map<String, Object> result = simulateService.simulate(req(h.getId(), 3200.0, false));
        List<String> conflicts = conflicts(result);
        assertFalse(conflicts.isEmpty(), "目标超容量必须报冲突");
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("额定容量")), "应指出容量冲突：" + conflicts);
        assertEquals(Boolean.FALSE, plan(result, "minCost").get("feasible"));
        assertEquals(Boolean.FALSE, plan(result, "minDeviation").get("feasible"));
        assertEquals("NONE", recommendation(result).get("preferred"), "不可行时不得给出误导性推荐");
        // 不可行原因要具体
        assertFalse(((List<?>) plan(result, "minCost").get("infeasibleReasons")).isEmpty());
    }

    // ---------------- 4. 原料上限与目标重量矛盾 ----------------

    @Test
    void itemCapConflictReported() {
        Furnace f = newFurnace("F-T-04", 3000);
        Heat h = newHeat("H-T-04", "CARBON_STEEL", "REFINING", 2700, f);
        newCharge(h, "CH-T-04", new Object[][]{
                {"PIG_IRON", 820}, {"SCRAP", 1680}, {"FERRO_MN", 76}, {"CARBURANT", 18}, {"FLUX", 40}});

        ChargeSimulateRequest r = req(h.getId(), 2700.0, false);
        r.setItemCaps(Map.of(
                "PIG_IRON", BigDecimal.valueOf(500),
                "SCRAP", BigDecimal.valueOf(900),
                "FERRO_MN", BigDecimal.valueOf(100),
                "CARBURANT", BigDecimal.valueOf(50),
                "FLUX", BigDecimal.valueOf(50)));
        Map<String, Object> result = simulateService.simulate(r);
        assertTrue(conflicts(result).stream().anyMatch(c -> c.contains("最大可配总重")),
                "应指出上限与目标的矛盾：" + conflicts(result));
        assertEquals(Boolean.FALSE, plan(result, "minCost").get("feasible"));
        assertEquals(Boolean.FALSE, plan(result, "minDeviation").get("feasible"));
        assertEquals("NONE", recommendation(result).get("preferred"));
    }

    // ---------------- 5. 现有明细含与材质不兼容的料项：建议移除并说明 ----------------

    @Test
    void incompatibleItemsSuggestedRemoval() {
        Furnace f = newFurnace("F-T-05", 600);
        Heat h = newHeat("H-T-05", "ALUMINUM", "MELTING", 400, f);
        newCharge(h, "CH-T-05", new Object[][]{
                {"SCRAP", 320}, {"FERRO_SI", 42}, {"FLUX", 22}, {"PIG_IRON", 30}, {"CARBURANT", 5}});

        Map<String, Object> result = simulateService.simulate(req(h.getId(), 400.0, false));
        Map<String, Object> a = plan(result, "minCost");
        Map<String, Object> b = plan(result, "minDeviation");
        assertEquals(Boolean.TRUE, a.get("feasible"));
        assertEquals(Boolean.TRUE, b.get("feasible"));
        // 生铁、增碳剂对铝合金不兼容，两套方案都必须建议移除
        for (Map<String, Object> p : List.of(a, b)) {
            List<Map<String, Object>> removed = adjustments(p).stream()
                    .filter(adj -> Boolean.TRUE.equals(adj.get("removed"))).toList();
            assertEquals(2, removed.size(), "应建议移除两项不兼容料：" + adjustments(p));
            assertTrue(removed.stream().anyMatch(adj -> "PIG_IRON".equals(adj.get("materialCode"))));
            assertTrue(removed.stream().anyMatch(adj -> "CARBURANT".equals(adj.get("materialCode"))));
        }
        // 方案二应把成分偏离压到 0（铝合金标准配比只用回炉料/硅铁/造渣剂）
        assertEquals(0.0, num(b, "compositionDeviation"), 0.01);
    }

    // ---------------- 6. 现有明细无法形成合法方案 ----------------

    @Test
    void emptyItemsWithoutNewMaterialConflicts() {
        Furnace f = newFurnace("F-T-06", 1500);
        Heat h = newHeat("H-T-06", "CARBON_STEEL", "CHARGING", 900, f);
        // 无配料单 + 不允许新料
        Map<String, Object> result = simulateService.simulate(req(h.getId(), 900.0, false));
        assertTrue(conflicts(result).stream().anyMatch(c -> c.contains("未允许引入新料种")),
                "应指出无可用料项：" + conflicts(result));
        assertEquals("NONE", recommendation(result).get("preferred"));

        // 允许新料后即可从零配料，两套方案都可行
        Map<String, Object> ok = simulateService.simulate(req(h.getId(), 900.0, true));
        assertEquals(Boolean.TRUE, plan(ok, "minCost").get("feasible"));
        assertEquals(Boolean.TRUE, plan(ok, "minDeviation").get("feasible"));
    }

    @Test
    void scrapOnlyConflicts() {
        Furnace f = newFurnace("F-T-07", 1500);
        Heat h = newHeat("H-T-07", "CARBON_STEEL", "CHARGING", 900, f);
        newCharge(h, "CH-T-07", new Object[][]{{"SCRAP", 900}});
        Map<String, Object> result = simulateService.simulate(req(h.getId(), 900.0, false));
        assertTrue(conflicts(result).stream().anyMatch(c -> c.contains("只有回炉料")),
                "应指出回炉料占比必然超限：" + conflicts(result));
        assertEquals("NONE", recommendation(result).get("preferred"));
    }

    // ---------------- 7. 已出炉炉次不开放推演 ----------------

    @Test
    void tappedHeatRejected() {
        Furnace f = newFurnace("F-T-08", 1500);
        Heat h = newHeat("H-T-08", "CAST_IRON", "TAPPING", 900, f);
        ChargeSimulateRequest r = req(h.getId(), 900.0, false);
        BizException e = assertThrows(BizException.class, () -> simulateService.simulate(r));
        assertTrue(e.getMessage().contains("未出炉"));
    }

    // ---------------- 8. 推演只读：原炉次、配料明细、炉体状态不被改动 ----------------

    @Test
    void simulationIsReadOnly() {
        Furnace f = newFurnace("F-T-09", 3000);
        Heat h = newHeat("H-T-09", "CARBON_STEEL", "REFINING", 2700, f);
        Charge c = newCharge(h, "CH-T-09", new Object[][]{
                {"PIG_IRON", 820}, {"SCRAP", 1680}, {"FERRO_MN", 76}, {"CARBURANT", 18}, {"FLUX", 40}});

        simulateService.simulate(req(h.getId(), 2700.0, true));
        simulateService.simulate(req(h.getId(), 2500.0, false));

        Heat afterHeat = heatRepository.findById(h.getId()).orElseThrow();
        assertEquals("REFINING", afterHeat.getStage());
        assertEquals(0, afterHeat.getPlanWeight().compareTo(BigDecimal.valueOf(2700)));
        Furnace afterFurnace = furnaceRepository.findById(f.getId()).orElseThrow();
        assertEquals("BUSY", afterFurnace.getStatus());
        assertEquals(0, afterFurnace.getCapacityKg().compareTo(BigDecimal.valueOf(3000)));
        Charge afterCharge = chargeRepository.findById(c.getId()).orElseThrow();
        assertEquals(0, afterCharge.getTotalWeight().compareTo(BigDecimal.valueOf(2634)));
        List<ChargeItem> items = itemRepository.findByChargeIdOrderByIdAsc(c.getId());
        assertEquals(5, items.size());
        assertEquals(0, items.get(1).getWeightKg().compareTo(BigDecimal.valueOf(1680)),
                "配料明细不得被推演改动");
    }

    // ---------------- 9. 同一输入版本重算：结果确定且一致 ----------------

    @Test
    void sameInputVersionYieldsSameResult() {
        Furnace f = newFurnace("F-T-10", 3000);
        Heat h = newHeat("H-T-10", "CARBON_STEEL", "REFINING", 2700, f);
        newCharge(h, "CH-T-10", new Object[][]{
                {"PIG_IRON", 820}, {"SCRAP", 1680}, {"FERRO_MN", 76}, {"CARBURANT", 18}, {"FLUX", 40}});

        Map<String, Object> r1 = simulateService.simulate(req(h.getId(), 2700.0, false));
        Map<String, Object> r2 = simulateService.simulate(req(h.getId(), 2700.0, false));
        assertEquals(plan(r1, "minCost").get("totalWeight"), plan(r2, "minCost").get("totalWeight"));
        assertEquals(plan(r1, "minDeviation").get("totalWeight"), plan(r2, "minDeviation").get("totalWeight"));
        assertEquals(recommendation(r1).get("preferred"), recommendation(r2).get("preferred"));

        // 输入回显与请求一致：前端可据此校验响应归属的输入版本
        @SuppressWarnings("unchecked")
        Map<String, Object> echo = (Map<String, Object>) r1.get("inputEcho");
        assertEquals(h.getId(), echo.get("heatId"));
        assertEquals(0, ((BigDecimal) echo.get("targetWeight")).compareTo(BigDecimal.valueOf(2700)));
        assertEquals(Boolean.FALSE, echo.get("allowNewMaterial"));
    }

    // ---------------- 10. 允许新料时方案二可引入明细外料种 ----------------

    @Test
    void minDeviationIntroducesNewMaterialWhenAllowed() {
        Furnace f = newFurnace("F-T-11", 3000);
        Heat h = newHeat("H-T-11", "CARBON_STEEL", "REFINING", 2700, f);
        newCharge(h, "CH-T-11", new Object[][]{
                {"PIG_IRON", 820}, {"SCRAP", 1680}, {"FERRO_MN", 76}, {"CARBURANT", 18}, {"FLUX", 40}});

        Map<String, Object> result = simulateService.simulate(req(h.getId(), 2700.0, true));
        Map<String, Object> b = plan(result, "minDeviation");
        assertEquals(Boolean.TRUE, b.get("feasible"));
        // 碳钢标准配比含 1% 硅铁，允许新料后方案二应引入硅铁把偏离压到 0
        assertTrue(adjustments(b).stream().anyMatch(adj ->
                "FERRO_SI".equals(adj.get("materialCode")) && Boolean.TRUE.equals(adj.get("isNew"))));
        assertEquals(0.0, num(b, "compositionDeviation"), 0.01);
    }

    // ---------------- 11. 大量料项下试算耗时可控 ----------------

    @Test
    void massiveItemsPerformanceAcceptable() {
        Furnace f = newFurnace("F-T-12", 5000);
        Heat h = newHeat("H-T-12", "DUCTILE_IRON", "MELTING", 2600, f);
        newCharge(h, "CH-T-12", new Object[][]{
                {"PIG_IRON", 700}, {"SCRAP", 1600}, {"FERRO_SI", 60},
                {"FERRO_MN", 55}, {"CARBURANT", 25}, {"FLUX", 45}});

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> result = simulateService.simulate(req(h.getId(), 2600.0, true));
            assertEquals(Boolean.TRUE, plan(result, "minCost").get("feasible"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "50 次全料种推演耗时应在 10s 内，实际 " + elapsedMs + "ms");
    }
}

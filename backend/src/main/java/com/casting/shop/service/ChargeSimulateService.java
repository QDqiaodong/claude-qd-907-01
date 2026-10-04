package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Charge;
import com.casting.shop.domain.ChargeItem;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.repository.ChargeItemRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配料偏差双方案推演（只读试算，不写库）。
 *
 * <p>以「当前配料明细 + 目标重量 + 炉体容量 + 合金材质 + 回炉料比例」为同一份基准，
 * 两套方案在同一次调用里基于同一份入参同步算出：</p>
 * <ul>
 *   <li>最低补料成本：优先利用已有料项，补料成本 = Σ增量×参考单价 + 新料种固定开料费，
 *       新料种按子集枚举精确求解；</li>
 *   <li>最小成分偏离：调整后配比与材质标准配比的 L1 距离（百分点）最小，
 *       折点枚举 + 盒约束 L1 投影求解。</li>
 * </ul>
 *
 * <p>硬约束（总重接近目标不能替代这些材质与工艺约束）：试算总重落在
 * [目标×(1−允许偏差), min(炉体容量, 目标×(1+允许偏差))]；各料项 ≤ 上限
 * （默认 {@link Dict#MAX_ITEM_WEIGHT}，可被请求覆盖）；回炉料占比 ≤ 材质上限（可被请求覆盖）；
 * 料种与材质兼容（不兼容的现有料项只能建议移除）。</p>
 *
 * <p>性能：最低成本为闭式解 O(n log n)（新料子集枚举 2^k，k 为明细外料种数，业务上 ≤ 6）；
 * 最小偏离为 O(折点数 × n²)，料项很多时仍是毫秒级。</p>
 */
@Service
public class ChargeSimulateService {

    private static final double EPS = 1e-9;
    /** 推荐比较容差：成本差 ≤ 0.01 元、偏离差 ≤ 0.5 个百分点视为相当 */
    private static final double COST_EPS = 0.01;
    private static final double DEV_EPS = 0.5;
    /** 调整量小于 0.005 kg 视为未变，不列入建议 */
    private static final double ADJUST_EPS = 0.005;

    private final HeatRepository heatRepository;
    private final FurnaceRepository furnaceRepository;
    private final ChargeRepository chargeRepository;
    private final ChargeItemRepository itemRepository;

    public ChargeSimulateService(HeatRepository heatRepository, FurnaceRepository furnaceRepository,
                                 ChargeRepository chargeRepository, ChargeItemRepository itemRepository) {
        this.heatRepository = heatRepository;
        this.furnaceRepository = furnaceRepository;
        this.chargeRepository = chargeRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 双方案推演主入口。只读：全程不 save 任何实体，原炉次、配料明细与炉体状态保持不变。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> simulate(ChargeSimulateRequest request) {
        if (request == null || request.getHeatId() == null) {
            throw new BizException("请先选择要推演的未出炉炉次");
        }
        Heat heat = heatRepository.findById(request.getHeatId())
                .orElseThrow(() -> new BizException("炉次不存在：" + request.getHeatId()));
        String stage = heat.getStage() == null ? "CHARGING" : heat.getStage();
        if ("TAPPING".equals(stage) || "POURED".equals(stage)) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 已处于「" + Dict.stageName(stage)
                    + "」阶段，配料偏差推演只对未出炉炉次开放");
        }
        if (!Dict.isValidMaterial(heat.getAlloy())) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 的材质非法，无法推演");
        }
        Furnace furnace = heat.getFurnaceId() != null
                ? furnaceRepository.findById(heat.getFurnaceId()).orElse(null) : null;
        double capacity = furnace != null && furnace.getCapacityKg() != null
                ? furnace.getCapacityKg().doubleValue() : Dict.MAX_TAP_WEIGHT.doubleValue();

        // 目标重量：请求覆盖 > 炉次计划重量 > 已登记出炉重量
        BigDecimal targetBd = request.getTargetWeight();
        if (targetBd == null || targetBd.compareTo(BigDecimal.ZERO) <= 0) {
            targetBd = heat.getPlanWeight();
        }
        if ((targetBd == null || targetBd.compareTo(BigDecimal.ZERO) <= 0) && heat.getTapWeight() != null) {
            targetBd = heat.getTapWeight();
        }
        if (targetBd == null || targetBd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 没有计划重量，请先在推演条件里填写目标重量");
        }
        double target = targetBd.doubleValue();

        // 回炉料占比上限：请求覆盖 > 材质默认；有效区间 (0, 100]
        double scrapLimit = Dict.scrapMaxRate(heat.getAlloy()).doubleValue() / 100.0;
        if (request.getScrapLimitRate() != null) {
            BigDecimal v = request.getScrapLimitRate();
            if (v.compareTo(BigDecimal.ZERO) <= 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BizException("回炉料占比上限必须在 (0, 100] % 之间");
            }
            scrapLimit = v.doubleValue() / 100.0;
        }

        // 各料项上限：默认全局上限，请求可逐项覆盖（不得超过全局硬上限）
        Map<String, Double> caps = new LinkedHashMap<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            caps.put(code, Dict.MAX_ITEM_WEIGHT.doubleValue());
        }
        if (request.getItemCaps() != null) {
            for (Map.Entry<String, BigDecimal> e : request.getItemCaps().entrySet()) {
                if (!Dict.isValidChargeMaterial(e.getKey())) {
                    throw new BizException("料项上限包含非法炉料品种：" + e.getKey());
                }
                if (e.getValue() == null) {
                    continue;
                }
                if (e.getValue().compareTo(BigDecimal.ZERO) <= 0
                        || e.getValue().compareTo(Dict.MAX_ITEM_WEIGHT) > 0) {
                    throw new BizException("料项「" + Dict.chargeMaterialName(e.getKey()) + "」上限必须在 (0, "
                            + Dict.MAX_ITEM_WEIGHT + "] kg 之间");
                }
                caps.put(e.getKey(), e.getValue().doubleValue());
            }
        }

        boolean allowNew = Boolean.TRUE.equals(request.getAllowNewMaterial());

        // 当前配料明细：该炉次最新一张配料单
        Charge charge = chargeRepository.findByHeatIdOrderByIdDesc(heat.getId()).stream()
                .findFirst().orElse(null);
        List<ChargeItem> items = charge != null
                ? itemRepository.findByChargeIdOrderByIdAsc(charge.getId()) : List.of();

        // 候选料种 = 现有明细中的兼容料项 +（允许新料时）材质兼容的其余料种；不兼容的现有料项只能建议移除
        List<Candidate> candidates = new ArrayList<>();
        List<ChargeItem> incompatible = new ArrayList<>();
        Set<String> inPlan = new HashSet<>();
        for (ChargeItem item : items) {
            String code = item.getMaterialCode();
            if (!Dict.isValidChargeMaterial(code) || !Dict.isMaterialAllowedForAlloy(heat.getAlloy(), code)) {
                incompatible.add(item);
                continue;
            }
            if (inPlan.add(code)) {
                candidates.add(new Candidate(code,
                        item.getWeightKg() == null ? 0 : item.getWeightKg().doubleValue(),
                        caps.get(code), false));
            }
        }
        if (allowNew) {
            for (String code : Dict.CHARGE_MATERIALS) {
                if (!inPlan.contains(code) && Dict.isMaterialAllowedForAlloy(heat.getAlloy(), code)) {
                    candidates.add(new Candidate(code, 0, caps.get(code), true));
                }
            }
        }

        double devRate = Dict.MAX_CHARGE_DEVIATION_RATE.doubleValue() / 100.0;
        double lo = target * (1 - devRate);
        double hi = Math.min(capacity, target * (1 + devRate));

        // 冲突检测：炉体容量、原料上限与目标重量互相矛盾时指出具体冲突，不给出误导性推荐
        List<String> conflicts = new ArrayList<>();
        if (target > capacity) {
            conflicts.add("目标重量 " + fmt(target) + " kg 超过熔炼炉 " + furnaceNo(heat, furnace)
                    + " 的额定容量 " + fmt(capacity) + " kg");
        }
        if (candidates.isEmpty()) {
            conflicts.add(allowNew
                    ? "材质「" + Dict.materialName(heat.getAlloy()) + "」没有可用的兼容料种"
                    : "现有配料明细没有可用于材质「" + Dict.materialName(heat.getAlloy())
                    + "」的料项，且未允许引入新料种");
        }
        double capSum = 0;
        double capNonScrap = 0;
        for (Candidate c : candidates) {
            capSum += c.cap;
            if (!"SCRAP".equals(c.code)) {
                capNonScrap += c.cap;
            }
        }
        // 回炉料占比上限约束下的最大可配总重：min(hi, Σcap, 非回炉料上限和 /(1−回炉料上限))
        double maxT = Math.min(hi, capSum);
        if (scrapLimit < 1) {
            maxT = Math.min(maxT, capNonScrap / (1 - scrapLimit));
        }
        if (!candidates.isEmpty() && maxT < lo - EPS) {
            conflicts.add("各项上限与回炉料占比上限 " + fmt(scrapLimit * 100) + "% 约束下，最大可配总重仅 "
                    + fmt(maxT) + " kg，达不到目标允许下限 " + fmt(lo) + " kg");
        }
        boolean onlyScrap = !candidates.isEmpty()
                && candidates.stream().allMatch(c -> "SCRAP".equals(c.code));
        if (onlyScrap && scrapLimit < 1) {
            conflicts.add("候选料项只有回炉料，回炉料占比必然 100%，超过材质允许上限 " + fmt(scrapLimit * 100) + "%");
        }

        Map<String, Double> mix = Dict.standardMix(heat.getAlloy());
        Plan minCost = solveMinCost(candidates, incompatible, mix, lo, maxT, scrapLimit, target);
        Plan minDeviation = solveMinDeviation(candidates, incompatible, mix, lo, maxT, scrapLimit, target);
        Map<String, Object> recommendation = recommend(minCost, minDeviation);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baseline", buildBaseline(heat, furnace, charge, items, incompatible,
                targetBd, capacity, scrapLimit, allowNew, caps));
        data.put("inputEcho", buildInputEcho(request, targetBd, allowNew, scrapLimit));
        data.put("conflicts", conflicts);
        data.put("minCost", minCost.toMap());
        data.put("minDeviation", minDeviation.toMap());
        data.put("recommendation", recommendation);
        return data;
    }

    // ---------------- 方案一：最低补料成本（闭式 + 新料子集枚举） ----------------

    /**
     * 成本只来自补料（增量 × 单价 + 新料开料费），减料免费。
     * 保留量 R(T) 关于总重 T 的斜率 ≤ 回炉料上限 &lt; 1，因此成本(T) 在 R(T) ≥ T 时为 0、
     * 之后单调不减：最优总重要么落在「零成本区间」内（取最接近当前总重的点），要么取允许下限 lo。
     */
    private Plan solveMinCost(List<Candidate> cands, List<ChargeItem> incompatible, Map<String, Double> mix,
                              double lo, double maxT, double scrapLimit, double target) {
        if (cands.isEmpty()) {
            return Plan.infeasible("无可用候选料项");
        }
        if (maxT < lo - EPS) {
            return Plan.infeasible("约束下最大可配总重 " + fmt(maxT) + " kg 低于目标允许下限 " + fmt(lo) + " kg");
        }
        Map<String, Double> x = new LinkedHashMap<>();
        double cn = 0;
        Candidate scrap = null;
        for (Candidate c : cands) {
            if ("SCRAP".equals(c.code)) {
                scrap = c;
            } else {
                cn += Math.min(c.cur, c.cap);
            }
        }
        double rsCap = scrap == null ? 0 : Math.min(scrap.cur, scrap.cap);

        double tZero;
        if (scrapLimit >= 1) {
            tZero = maxT;
        } else if (scrapLimit * cn <= rsCap * (1 - scrapLimit) + EPS) {
            tZero = Math.min(maxT, cn / (1 - scrapLimit));
        } else {
            tZero = Math.min(maxT, cn + rsCap);
        }

        double total;
        if (lo <= tZero + EPS) {
            // 零成本可达：选最接近当前总重的 T，保留后超出 T 的部分免费减掉
            double curT = cn + (scrap == null ? 0 : scrap.cur);
            total = clamp(curT, lo, tZero);
            for (Candidate c : cands) {
                x.put(c.code, Math.min(c.cur, c.cap));
            }
            if (scrap != null) {
                x.put("SCRAP", Math.min(x.get("SCRAP"), scrapLimit * total));
            }
            double excess = sum(x) - total;
            if (excess > EPS) {
                // 减料免费，按单价降序减（先释放占用资金多的料）；回炉料占比约束在保留时已满足
                List<Candidate> order = new ArrayList<>(cands);
                order.sort((a, b) -> Double.compare(price(b.code), price(a.code)));
                for (Candidate c : order) {
                    if (excess <= EPS) {
                        break;
                    }
                    double cut = Math.min(x.get(c.code), excess);
                    x.put(c.code, x.get(c.code) - cut);
                    excess -= cut;
                }
            }
        } else {
            // 必须补料：成本关于 T 单调不减，取 T = lo
            total = lo;
            for (Candidate c : cands) {
                x.put(c.code, Math.min(c.cur, c.cap));
            }
            if (scrap != null) {
                x.put("SCRAP", Math.min(x.get("SCRAP"), scrapLimit * total));
            }
            double need = total - sum(x);
            if (need > EPS) {
                // 补料源：已有料恒可用；新料种带固定开料费，枚举子集精确求解
                Map<String, Double> room = new LinkedHashMap<>();
                for (Candidate c : cands) {
                    double r = c.cap - x.get(c.code);
                    if ("SCRAP".equals(c.code)) {
                        r = Math.min(r, scrapLimit * total - x.get("SCRAP"));
                    }
                    room.put(c.code, Math.max(0, r));
                }
                List<Candidate> fresh = cands.stream().filter(c -> c.isNew).toList();
                Fill fill = bestFill(cands, fresh, room, need);
                if (fill == null) {
                    return Plan.infeasible("所有料项补到上限仍差 " + fmt(need) + " kg，达不到目标允许下限");
                }
                for (Map.Entry<String, Double> e : fill.use.entrySet()) {
                    x.put(e.getKey(), x.get(e.getKey()) + e.getValue());
                }
            }
        }
        return buildPlan(cands, incompatible, mix, x, total, target);
    }

    /** 新料子集枚举：子集内按单价升序贪心，取「开料费 + 补料费」最小的子集 */
    private Fill bestFill(List<Candidate> cands, List<Candidate> fresh, Map<String, Double> room, double need) {
        int k = fresh.size();
        if (k > 20) {
            // 防御：料种异常增多时退化为等效成本（单价 + 开料费/可用量）贪心，避免指数爆炸；
            // 当前业务固定 6 个料种，走不到这里
            return greedyFill(cands, room, need);
        }
        List<Candidate> existing = cands.stream().filter(c -> !c.isNew).toList();
        Fill best = null;
        for (int mask = 0; mask < (1 << k); mask++) {
            List<Candidate> pool = new ArrayList<>(existing);
            double fee = 0;
            for (int i = 0; i < k; i++) {
                if ((mask & (1 << i)) != 0) {
                    fee += Dict.NEW_MATERIAL_FEE.doubleValue();
                    pool.add(fresh.get(i));
                }
            }
            pool.sort((a, b) -> Double.compare(price(a.code), price(b.code)));
            double remain = need;
            double cost = fee;
            Map<String, Double> use = new LinkedHashMap<>();
            for (Candidate c : pool) {
                if (remain <= EPS) {
                    break;
                }
                double add = Math.min(room.getOrDefault(c.code, 0.0), remain);
                if (add > EPS) {
                    use.merge(c.code, add, Double::sum);
                    cost += add * price(c.code);
                    remain -= add;
                }
            }
            if (remain > 1e-6) {
                continue;
            }
            if (best == null || cost < best.cost - 1e-9) {
                best = new Fill(cost, use);
            }
        }
        return best;
    }

    /** 兜底贪心：新料按等效成本（单价 + 开料费/可用量）与已有料统一排序 */
    private Fill greedyFill(List<Candidate> cands, Map<String, Double> room, double need) {
        List<Candidate> pool = new ArrayList<>(cands);
        pool.sort((a, b) -> {
            double ea = a.isNew ? price(a.code) + Dict.NEW_MATERIAL_FEE.doubleValue()
                    / Math.max(room.getOrDefault(a.code, 0.0), 1) : price(a.code);
            double eb = b.isNew ? price(b.code) + Dict.NEW_MATERIAL_FEE.doubleValue()
                    / Math.max(room.getOrDefault(b.code, 0.0), 1) : price(b.code);
            return Double.compare(ea, eb);
        });
        double remain = need;
        double cost = 0;
        Map<String, Double> use = new LinkedHashMap<>();
        Set<String> opened = new HashSet<>();
        for (Candidate c : pool) {
            if (remain <= EPS) {
                break;
            }
            double add = Math.min(room.getOrDefault(c.code, 0.0), remain);
            if (add > EPS) {
                use.put(c.code, add);
                cost += add * price(c.code);
                if (c.isNew && opened.add(c.code)) {
                    cost += Dict.NEW_MATERIAL_FEE.doubleValue();
                }
                remain -= add;
            }
        }
        return remain > 1e-6 ? null : new Fill(cost, use);
    }

    // ---------------- 方案二：最小成分偏离（折点枚举 + L1 投影） ----------------

    /**
     * 偏离(T) 是关于总重 T 的分段线性函数，折点只可能出现在：允许区间端点、
     * 各料项理想量触及上限的 T = cap/s、回炉料上限与上限线交点 T = cap/L。
     * 枚举全部折点（另加当前总重、目标重量与均匀网格做双保险），逐点做盒约束 L1 投影取最优。
     */
    private Plan solveMinDeviation(List<Candidate> cands, List<ChargeItem> incompatible, Map<String, Double> mix,
                                   double lo, double maxT, double scrapLimit, double target) {
        if (cands.isEmpty()) {
            return Plan.infeasible("无可用候选料项");
        }
        if (maxT < lo - EPS) {
            return Plan.infeasible("约束下最大可配总重 " + fmt(maxT) + " kg 低于目标允许下限 " + fmt(lo) + " kg");
        }
        double curT = cands.stream().mapToDouble(c -> c.cur).sum();
        Set<Double> tSet = new TreeSet<>();
        tSet.add(lo);
        tSet.add(maxT);
        tSet.add(clamp(target, lo, maxT));
        tSet.add(clamp(curT, lo, maxT));
        for (Candidate c : cands) {
            double s = mix.getOrDefault(c.code, 0.0) / 100.0;
            if (s > EPS) {
                tSet.add(clamp(c.cap / s, lo, maxT));
            }
            if ("SCRAP".equals(c.code) && scrapLimit > EPS && scrapLimit < 1) {
                tSet.add(clamp(c.cap / scrapLimit, lo, maxT));
            }
        }
        int grids = 21;
        for (int i = 0; i <= grids; i++) {
            tSet.add(lo + (maxT - lo) * i / grids);
        }

        Plan best = null;
        for (double t : tSet) {
            Map<String, Double> x = project(t, cands, mix, scrapLimit);
            if (x == null) {
                continue;
            }
            Plan p = buildPlan(cands, incompatible, mix, x, t, target);
            if (best == null || compareKey(p.dev, p.adjustSum, Math.abs(t - target),
                    best.dev, best.adjustSum, Math.abs(best.total - target)) < 0) {
                best = p;
            }
        }
        return best != null ? best : Plan.infeasible("约束交集为空，无法形成合法配比");
    }

    /**
     * 固定总重 T：理想配比 x = s×T 在盒约束 [0, cap] 与回炉料占比上限下的 L1 投影。
     * 越界项钉在上界，差额按标准配比比例分摊给未钉住项；每轮至少钉住一项，有限步收敛。
     */
    private Map<String, Double> project(double total, List<Candidate> cands, Map<String, Double> mix,
                                        double scrapLimit) {
        Map<String, Double> x = new LinkedHashMap<>();
        for (Candidate c : cands) {
            x.put(c.code, mix.getOrDefault(c.code, 0.0) / 100.0 * total);
        }
        Set<String> pinned = new HashSet<>();
        for (int round = 0; round <= cands.size() + 2; round++) {
            for (Candidate c : cands) {
                if (pinned.contains(c.code)) {
                    continue;
                }
                double up = c.cap;
                if ("SCRAP".equals(c.code)) {
                    up = Math.min(up, scrapLimit * total);
                }
                if (x.get(c.code) > up + 1e-9) {
                    x.put(c.code, up);
                    pinned.add(c.code);
                }
            }
            double deficit = total - sum(x);
            if (deficit <= 1e-6) {
                return x;
            }
            List<Candidate> free = cands.stream().filter(c -> !pinned.contains(c.code)).toList();
            if (free.isEmpty()) {
                return null;
            }
            double sSum = free.stream().mapToDouble(c -> mix.getOrDefault(c.code, 0.0)).sum();
            for (Candidate c : free) {
                double share = sSum > EPS ? mix.getOrDefault(c.code, 0.0) / sSum : 1.0 / free.size();
                x.put(c.code, x.get(c.code) + deficit * share);
            }
        }
        return Math.abs(sum(x) - total) > 1e-4 ? null : x;
    }

    // ---------------- 推荐取舍 ----------------

    /**
     * 两套方案都可行时：结果等价标「无偏好」；成本相当取偏离小者；偏离相当取成本低者；
     * 否则看最低成本方案的偏离是否仍在可控带内——在内推荐省钱，超带推荐花钱买稳妥。
     * 推荐语必须说明未选方案牺牲了什么。
     */
    private Map<String, Object> recommend(Plan a, Plan b) {
        Map<String, Object> rec = new LinkedHashMap<>();
        String preferred;
        String reason;
        String sacrifice = "";
        if (!a.feasible && !b.feasible) {
            preferred = "NONE";
            reason = "两套方案均不可行：请先解决列出的约束冲突，再重新推演";
        } else if (a.feasible) {
            if (!b.feasible) {
                preferred = "MIN_COST";
                reason = "仅「最低补料成本」方案可行";
                sacrifice = "未选「最小成分偏离」方案：该方案不可行 —— " + String.join("；", b.reasons);
            } else {
                Set<String> codes = new HashSet<>(a.x.keySet());
                codes.addAll(b.x.keySet());
                boolean equivalent = true;
                for (String code : codes) {
                    if (Math.abs(a.x.getOrDefault(code, 0.0) - b.x.getOrDefault(code, 0.0)) > 0.01) {
                        equivalent = false;
                        break;
                    }
                }
                double dCost = b.cost - a.cost;
                double dDev = a.dev - b.dev;
                if (equivalent || (Math.abs(dCost) <= COST_EPS && Math.abs(dDev) <= DEV_EPS)) {
                    preferred = "NONE";
                    reason = "两套方案推演结果一致，无偏好";
                } else if (Math.abs(dCost) <= COST_EPS) {
                    preferred = "MIN_DEVIATION";
                    reason = "两套方案补料成本相当，「最小成分偏离」方案的成分偏离低 "
                            + fmt(dDev) + " 个百分点";
                    sacrifice = "未选「最低补料成本」方案：没有额外成本损失，两者补料成本相当";
                } else if (Math.abs(dDev) <= DEV_EPS) {
                    preferred = "MIN_COST";
                    reason = "两套方案成分偏离相当，「最低补料成本」方案再省 " + fmt(dCost) + " 元";
                    sacrifice = "未选「最小成分偏离」方案：没有额外偏离收益，两者成分偏离相当";
                } else if (a.dev <= Dict.ACCEPTABLE_COMPOSITION_DEVIATION) {
                    preferred = "MIN_COST";
                    reason = "「最低补料成本」方案的成分偏离 " + fmt(a.dev) + " 个百分点仍在可控带 "
                            + fmt(Dict.ACCEPTABLE_COMPOSITION_DEVIATION) + " 内，且补料成本省 " + fmt(dCost) + " 元";
                    sacrifice = "未选「最小成分偏离」方案：放弃了再降 " + fmt(dDev)
                            + " 个百分点偏离的空间，但它要多花 " + fmt(dCost) + " 元补料";
                } else {
                    preferred = "MIN_DEVIATION";
                    reason = "「最低补料成本」方案的成分偏离 " + fmt(a.dev) + " 个百分点超出可控带 "
                            + fmt(Dict.ACCEPTABLE_COMPOSITION_DEVIATION) + "，后续成分风险高";
                    sacrifice = "未选「最低补料成本」方案：放弃了 " + fmt(dCost)
                            + " 元的补料成本节省，换来成分偏离降低 " + fmt(dDev) + " 个百分点";
                }
            }
        } else {
            preferred = "MIN_DEVIATION";
            reason = "仅「最小成分偏离」方案可行";
            sacrifice = "未选「最低补料成本」方案：该方案不可行 —— " + String.join("；", a.reasons);
        }
        rec.put("preferred", preferred);
        rec.put("reason", reason);
        rec.put("sacrifice", sacrifice);
        return rec;
    }

    // ---------------- 结果组装 ----------------

    private Plan buildPlan(List<Candidate> cands, List<ChargeItem> incompatible, Map<String, Double> mix,
                           Map<String, Double> x, double total, double target) {
        Plan p = new Plan();
        p.feasible = true;
        p.x = x;
        p.total = total;
        double cost = 0;
        double adjustSum = 0;
        for (Candidate c : cands) {
            double w = x.getOrDefault(c.code, 0.0);
            double delta = w - c.cur;
            adjustSum += Math.abs(delta);
            if (delta > EPS) {
                cost += delta * price(c.code);
                if (c.isNew) {
                    cost += Dict.NEW_MATERIAL_FEE.doubleValue();
                }
            }
            if (Math.abs(delta) > ADJUST_EPS) {
                p.adjustments.add(adjustment(c.code, c.cur, w, c.isNew, false));
            }
        }
        for (ChargeItem item : incompatible) {
            double w = item.getWeightKg() == null ? 0 : item.getWeightKg().doubleValue();
            adjustSum += w;
            p.adjustments.add(adjustment(item.getMaterialCode(), w, 0, false, true));
        }
        p.adjustments.sort((a, b) -> Double.compare(
                Math.abs(((BigDecimal) b.get("deltaKg")).doubleValue()),
                Math.abs(((BigDecimal) a.get("deltaKg")).doubleValue())));
        // 成分偏离：对全部炉料品种求 |实际占比 − 标准配比| 的 L1 和（百分点）
        double dev = 0;
        for (String code : Dict.CHARGE_MATERIALS) {
            double actual = x.getOrDefault(code, 0.0) / total * 100.0;
            dev += Math.abs(actual - mix.getOrDefault(code, 0.0));
        }
        p.dev = dev;
        p.cost = cost;
        p.adjustSum = adjustSum;
        p.deviationRate = (total - target) / target * 100.0;
        p.scrapRate = x.getOrDefault("SCRAP", 0.0) / total * 100.0;
        return p;
    }

    private Map<String, Object> adjustment(String code, double from, double to, boolean isNew, boolean removed) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("materialCode", code);
        a.put("materialName", Dict.chargeMaterialName(code));
        a.put("currentKg", bd(from));
        a.put("suggestedKg", bd(to));
        a.put("deltaKg", bd(to - from));
        a.put("isNew", isNew);
        a.put("removed", removed);
        return a;
    }

    private Map<String, Object> buildBaseline(Heat heat, Furnace furnace, Charge charge, List<ChargeItem> items,
                                              List<ChargeItem> incompatible, BigDecimal target, double capacity,
                                              double scrapLimit, boolean allowNew, Map<String, Double> caps) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("heatId", heat.getId());
        b.put("heatNo", heat.getHeatNo());
        b.put("stage", heat.getStage());
        b.put("stageName", heat.getStageName());
        b.put("alloy", heat.getAlloy());
        b.put("alloyName", heat.getAlloyName());
        b.put("furnaceNo", furnaceNo(heat, furnace));
        b.put("capacityKg", bd(capacity));
        b.put("chargeId", charge == null ? null : charge.getId());
        b.put("chargeNo", charge == null ? null : charge.getChargeNo());
        double total = 0;
        double scrap = 0;
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (ChargeItem item : items) {
            double w = item.getWeightKg() == null ? 0 : item.getWeightKg().doubleValue();
            total += w;
            if ("SCRAP".equals(item.getMaterialCode())) {
                scrap += w;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialCode", item.getMaterialCode());
            m.put("materialName", Dict.chargeMaterialName(item.getMaterialCode()));
            m.put("weightKg", bd(w));
            m.put("compatible", !incompatible.contains(item));
            itemMaps.add(m);
        }
        b.put("items", itemMaps);
        b.put("currentTotalKg", bd(total));
        b.put("currentScrapRate", total > 0 ? bd(scrap / total * 100.0) : bd(0));
        b.put("targetWeight", target);
        b.put("deviationLimitRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        b.put("scrapLimitRate", bd(scrapLimit * 100.0));
        b.put("allowNewMaterial", allowNew);
        Map<String, Object> capMaps = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : caps.entrySet()) {
            capMaps.put(e.getKey(), bd(e.getValue()));
        }
        b.put("itemCaps", capMaps);
        return b;
    }

    /** 生效的输入回显：前端据此校验响应与当前输入版本是否一致（配合请求序号防旧计算覆盖） */
    private Map<String, Object> buildInputEcho(ChargeSimulateRequest request, BigDecimal target,
                                               boolean allowNew, double scrapLimit) {
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("heatId", request.getHeatId());
        echo.put("targetWeight", target);
        echo.put("allowNewMaterial", allowNew);
        echo.put("scrapLimitRate", bd(scrapLimit * 100.0));
        Map<String, Object> capsEcho = new LinkedHashMap<>();
        if (request.getItemCaps() != null) {
            for (Map.Entry<String, BigDecimal> e : request.getItemCaps().entrySet()) {
                capsEcho.put(e.getKey(), e.getValue());
            }
        }
        echo.put("itemCaps", capsEcho);
        return echo;
    }

    // ---------------- 小工具 ----------------

    private static String furnaceNo(Heat heat, Furnace furnace) {
        if (furnace != null && furnace.getFurnaceNo() != null) {
            return furnace.getFurnaceNo();
        }
        return heat.getFurnaceNo() == null ? "（未分配熔炼炉）" : heat.getFurnaceNo();
    }

    private static double price(String code) {
        return Dict.chargeMaterialPrice(code).doubleValue();
    }

    private static double sum(Map<String, Double> x) {
        double s = 0;
        for (double v : x.values()) {
            s += v;
        }
        return s;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private static int compareKey(double a1, double a2, double a3, double b1, double b2, double b3) {
        if (a1 < b1 - 1e-6) {
            return -1;
        }
        if (a1 > b1 + 1e-6) {
            return 1;
        }
        if (a2 < b2 - 1e-6) {
            return -1;
        }
        if (a2 > b2 + 1e-6) {
            return 1;
        }
        return Double.compare(a3, b3);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return bd(v).stripTrailingZeros().toPlainString();
    }

    /** 候选料种：cur 为现有重量（新料为 0），cap 为生效上限 */
    private static final class Candidate {
        final String code;
        final double cur;
        final double cap;
        final boolean isNew;

        Candidate(String code, double cur, double cap, boolean isNew) {
            this.code = code;
            this.cur = cur;
            this.cap = cap;
            this.isNew = isNew;
        }
    }

    /** 补料子集结果 */
    private static final class Fill {
        final double cost;
        final Map<String, Double> use;

        Fill(double cost, Map<String, Double> use) {
            this.cost = cost;
            this.use = use;
        }
    }

    /** 一套推演方案 */
    private static final class Plan {
        boolean feasible;
        final List<String> reasons = new ArrayList<>();
        final List<Map<String, Object>> adjustments = new ArrayList<>();
        Map<String, Double> x = new LinkedHashMap<>();
        double total;
        double deviationRate;
        double scrapRate;
        double cost;
        double dev;
        double adjustSum;

        static Plan infeasible(String reason) {
            Plan p = new Plan();
            p.feasible = false;
            p.reasons.add(reason);
            return p;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feasible", feasible);
            m.put("adjustments", adjustments);
            m.put("totalWeight", feasible ? bd(total) : null);
            m.put("deviationRate", feasible ? bd(deviationRate) : null);
            m.put("scrapRate", feasible ? bd(scrapRate) : null);
            m.put("costTotal", feasible ? bd(cost) : null);
            m.put("compositionDeviation", feasible ? bd(dev) : null);
            m.put("infeasibleReasons", reasons);
            return m;
        }
    }
}

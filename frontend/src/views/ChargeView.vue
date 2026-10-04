<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const sheets = ref([])
const detail = ref(null)
const board = ref({ heats: [], materials: [], maxDeviationRate: 3, maxItemWeight: 2000, openHeats: [] })
const selectedId = ref(null)
const createOpen = ref(false)
const loading = ref(false)

const form = ref({
  chargeNo: '',
  heatId: null,
  operator: '吴桂芳',
  remark: '',
  items: [{ materialCode: 'PIG_IRON', weightKg: 400 }, { materialCode: 'SCRAP', weightKg: 500 }]
})
const step = ref(10)

const maxRate = computed(() => Number(board.value.maxDeviationRate ?? 3))

const selected = computed(() => sheets.value.find((c) => c.id === selectedId.value) || null)
const items = computed(() => detail.value?.items || [])
const totalWeight = computed(() =>
  items.value.reduce((sum, i) => sum + Number(i.weightKg || 0), 0)
)
const ratioSum = computed(() => items.value.reduce((sum, i) => sum + Number(i.ratio || 0), 0))
const ratioOff = computed(() => Math.abs(ratioSum.value - 100) > 0.5)

// 新建表单的实时偏差：与所选炉次的出炉重量比，超过允许比例就标红
const formTotal = computed(() =>
  form.value.items.reduce((sum, i) => sum + Number(i.weightKg || 0), 0)
)
const formTarget = computed(() => {
  const heat = (board.value.heats || []).find((h) => h.id === form.value.heatId)
  return heat ? Number(heat.tapWeight || heat.planWeight || 0) : 0
})
const formDeviation = computed(() => {
  if (!formTarget.value) return null
  return ((formTotal.value - formTarget.value) / formTarget.value) * 100
})
const formOver = computed(() =>
  formDeviation.value !== null && Math.abs(formDeviation.value) > maxRate.value
)

// ==================== 配料偏差双方案推演 ====================
// 同一份基准（当前配料明细 + 目标重量 + 炉体容量 + 合金材质 + 回炉料比例）下，
// 后端同步算出「最低补料成本」与「最小成分偏离」两套方案；全程只读，不写回任何业务数据。
const simHeats = computed(() => board.value.openHeats || [])
const simForm = ref({
  heatId: null,
  targetWeight: null,
  allowNewMaterial: false,
  scrapLimitRate: null, // 空 = 按材质默认上限
  caps: {}              // 各料种上限覆盖，默认 = 后端 maxItemWeight
})
const simResult = ref(null)
const simLoading = ref(false)
// 请求序号：每次发起推演自增，响应回来时序号不是最新的直接丢弃 ——
// 用户改了目标重量 / 某项上限 / 是否允许新料后，较慢的旧计算返回不得覆盖新结果。
let simSeq = 0
let simTimer = null

const simBaseline = computed(() => simResult.value?.baseline || null)
const simConflicts = computed(() => simResult.value?.conflicts || [])
const simRecommendation = computed(() => simResult.value?.recommendation || null)
const simPlans = computed(() => {
  if (!simResult.value) return []
  return [
    { key: 'MIN_COST', title: '方案一 · 最低补料成本', desc: '优先利用已有料项以减少成本', plan: simResult.value.minCost },
    { key: 'MIN_DEVIATION', title: '方案二 · 最小成分偏离', desc: '优先降低成分偏离以减少后续风险', plan: simResult.value.minDeviation }
  ]
})

function defaultCaps() {
  const caps = {}
  ;(board.value.materials || []).forEach((m) => { caps[m.code] = Number(board.value.maxItemWeight || 2000) })
  return caps
}

function resetCaps() {
  simForm.value.caps = defaultCaps()
}

function onSimHeatChange() {
  const h = simHeats.value.find((x) => x.id === simForm.value.heatId)
  const w = h ? Number(h.planWeight || h.tapWeight || 0) : 0
  simForm.value.targetWeight = w > 0 ? w : null
}

function scheduleSimulate(delay = 300) {
  clearTimeout(simTimer)
  simTimer = setTimeout(runSimulate, delay)
}

async function runSimulate() {
  if (!simForm.value.heatId || !simForm.value.targetWeight) {
    simSeq += 1 // 让在途的旧响应失效
    simResult.value = null
    simLoading.value = false
    return
  }
  const seq = ++simSeq
  simLoading.value = true
  const payload = {
    heatId: simForm.value.heatId,
    targetWeight: simForm.value.targetWeight,
    allowNewMaterial: simForm.value.allowNewMaterial,
    scrapLimitRate: simForm.value.scrapLimitRate || null,
    itemCaps: { ...simForm.value.caps }
  }
  try {
    const res = await api.charge.simulate(payload)
    if (seq !== simSeq) return // 较慢的旧计算返回，丢弃，不得覆盖新结果
    simResult.value = res
  } catch (e) {
    if (seq !== simSeq) return
    simResult.value = null
    ElMessage.error(e.message)
  } finally {
    if (seq === simSeq) simLoading.value = false
  }
}

// 目标重量 / 某项上限 / 是否允许新料 / 回炉料上限变化 → 基于同一份输入版本重算（防抖合并）
watch(simForm, () => scheduleSimulate(), { deep: true })

function recText(rec) {
  if (!rec) return ''
  if (rec.preferred === 'MIN_COST') return '推荐「最低补料成本」方案'
  if (rec.preferred === 'MIN_DEVIATION') return '推荐「最小成分偏离」方案'
  return '两套方案无偏好'
}

function fmtKg(v) {
  return v === null || v === undefined ? '—' : Number(v).toFixed(2)
}
// ==================== 推演结束 ====================

function materialName(code) {
  const m = (board.value.materials || []).find((x) => x.code === code)
  return m ? m.name : code
}
function materialColor(code) {
  const m = (board.value.materials || []).find((x) => x.code === code)
  return m ? m.color : '#8e24aa'
}

async function selectSheet(sheet) {
  selectedId.value = sheet.id
  loading.value = true
  try {
    detail.value = await api.charge.detail(sheet.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 逐项 ± 微调 */
async function adjust(item, delta) {
  loading.value = true
  try {
    await api.charge.adjust(selectedId.value, { materialCode: item.materialCode, delta })
    ElMessage.success((delta > 0 ? '+' : '') + delta + ' kg 已并入「' + item.materialRealName + '」')
    await Promise.all([loadSheets(), api.charge.detail(selectedId.value).then((d) => (detail.value = d))])
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createCharge() {
  if (!form.value.chargeNo) return ElMessage.warning('请填配料单号')
  if (formOver.value) return ElMessage.warning('配料偏差超过允许比例，请调整后再提交')
  loading.value = true
  try {
    await api.charge.create(form.value)
    ElMessage.success('配料单已建立')
    createOpen.value = false
    await loadSheets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function removeSheet() {
  if (!selected.value) return
  loading.value = true
  try {
    await api.charge.remove(selected.value.id)
    ElMessage.success('配料单已删除')
    selectedId.value = null
    detail.value = null
    await loadSheets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function addRow() {
  form.value.items.push({ materialCode: 'SCRAP', weightKg: 100 })
}
function removeRow(idx) {
  form.value.items.splice(idx, 1)
}

async function loadSheets() {
  sheets.value = await api.charge.list()
  if (selectedId.value === null && sheets.value.length) {
    await selectSheet(sheets.value[0])
  } else if (selectedId.value) {
    detail.value = await api.charge.detail(selectedId.value)
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.charge.board()
    if (!Object.keys(simForm.value.caps || {}).length) {
      simForm.value.caps = defaultCaps()
    }
    if (simForm.value.heatId === null && simHeats.value.length) {
      simForm.value.heatId = simHeats.value[0].id
      onSimHeatChange()
    }
    await loadSheets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">熔炼工段 · 炉料配比滑杆条</span>
      <span class="view-tip">每条横向滑杆＝一种炉料占比；合计不对 100% 会标红，逐项 ± 微调实时重算占比与偏差</span>
    </div>

    <div class="grid-2">
      <section class="panel">
        <div class="panel-title">
          配比滑杆
          <span class="view-tip" v-if="selected">
            {{ selected.chargeNo }} · 合计 {{ totalWeight.toFixed(2) }} kg · 占合计 {{ ratioSum.toFixed(2) }}%
          </span>
          <span :class="['sum-flag', { over: ratioOff }]">{{ ratioSum.toFixed(2) }}%</span>
        </div>

        <div v-if="items.length">
          <div class="slide-row" v-for="item in items" :key="item.id">
            <div class="slide-head">
              <span class="mat-name">
                <span class="dot" :style="{ background: item.materialColor }"></span>
                {{ item.materialRealName }}
              </span>
              <span class="mat-weight">{{ Number(item.weightKg).toFixed(2) }} kg</span>
              <span class="mat-ratio">{{ Number(item.ratio).toFixed(2) }}%</span>
            </div>
            <div class="slide-track">
              <div class="slide-bar" :style="{ width: Number(item.ratio) + '%', background: item.materialColor }"></div>
            </div>
            <div class="slide-op">
              <el-button size="small" circle @click="adjust(item, -step)">−</el-button>
              <el-button size="small" circle @click="adjust(item, step)">＋</el-button>
              <el-select v-model="step" size="small" style="width:88px">
                <el-option :value="5" label="±5 kg" />
                <el-option :value="10" label="±10 kg" />
                <el-option :value="25" label="±25 kg" />
              </el-select>
            </div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无配料明细</div>

        <div class="dev-row" v-if="selected">
          <span class="tag-line">目标重量</span>
          <b>{{ Number(selected.targetWeight).toFixed(2) }} kg</b>
          <span class="tag-line">配料合计</span>
          <b>{{ Number(selected.totalWeight).toFixed(2) }} kg</b>
          <span class="tag-line">偏差</span>
          <b :class="{ over: selected.overDeviation }">{{ Number(selected.deviationRate).toFixed(2) }}%</b>
          <span class="tag-line">允许</span>
          <b>±{{ maxRate }}%</b>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">配料单</div>
        <div class="sheet-list" v-if="sheets.length">
          <div
            v-for="s in sheets"
            :key="s.id"
            class="sheet-item"
            :class="{ on: selectedId === s.id }"
            @click="selectSheet(s)"
          >
            <div class="sheet-no">{{ s.chargeNo }}</div>
            <div class="sheet-sub">{{ s.heatNo }} · {{ s.itemCount }} 项 · {{ Number(s.totalWeight).toFixed(0) }} kg</div>
            <div class="sheet-rate" :class="{ over: s.overDeviation }">{{ Number(s.deviationRate).toFixed(2) }}%</div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无配料单</div>

        <div class="op-row">
          <el-button size="small" type="primary" plain @click="createOpen = true">新建配料单</el-button>
          <el-button size="small" type="danger" plain :disabled="!selected" @click="removeSheet">删除配料单</el-button>
        </div>
      </section>
    </div>

    <!-- ==================== 配料偏差双方案推演（只读试算） ==================== -->
    <section class="panel sim-panel">
      <div class="panel-title">
        配料偏差双方案推演
        <span class="view-tip">
          以当前配料明细、目标重量、炉体容量、合金材质、回炉料比例为同一份基准，同步试算两套方案；
          全程只读，不改动炉次、配料明细与炉体状态，正式调整仍用上方「逐项 ± 微调」
        </span>
      </div>

      <div class="sim-inputs">
        <el-select
          v-model="simForm.heatId"
          placeholder="选择未出炉炉次"
          style="width:300px"
          @change="onSimHeatChange"
        >
          <el-option
            v-for="h in simHeats"
            :key="h.id"
            :value="h.id"
            :label="h.heatNo + ' · ' + h.alloyName + ' · ' + h.stageName + ' · 计划 ' + (h.planWeight ?? '—') + ' kg'"
          />
        </el-select>
        <span class="sim-label">目标重量</span>
        <el-input-number v-model="simForm.targetWeight" :min="1" :max="5000" :step="10" controls-position="right" />
        <span class="sim-label">kg</span>
        <el-checkbox v-model="simForm.allowNewMaterial">允许引入新料种</el-checkbox>
        <span class="sim-label">回炉料上限</span>
        <el-input-number v-model="simForm.scrapLimitRate" :min="1" :max="100" :step="1" controls-position="right" placeholder="按材质" />
        <span class="sim-label">%（空＝按材质默认）</span>
        <el-button size="small" :loading="simLoading" @click="runSimulate">重新推演</el-button>
      </div>

      <div class="sim-caps" v-if="board.materials.length">
        <span class="sim-label">料项上限</span>
        <span v-for="m in board.materials" :key="m.code" class="cap-item">
          <span class="dot" :style="{ background: m.color }"></span>
          {{ m.name }}
          <el-input-number v-model="simForm.caps[m.code]" :min="1" :max="Number(board.maxItemWeight || 2000)" :step="50" size="small" controls-position="right" style="width:108px" />
        </span>
        <el-button size="small" text @click="resetCaps">恢复默认上限</el-button>
      </div>

      <div class="empty-tip" v-if="!simHeats.length">暂无未出炉炉次（装料 / 熔炼 / 精炼阶段的炉次才能推演）</div>
      <div class="empty-tip" v-else-if="!simResult && !simLoading">请选择炉次并确认目标重量后自动推演</div>

      <template v-if="simResult">
        <!-- 同一份基准 -->
        <div class="sim-baseline" v-if="simBaseline">
          <span class="tag-line">基准炉次</span><b>{{ simBaseline.heatNo }} · {{ simBaseline.alloyName }} · {{ simBaseline.stageName }}</b>
          <span class="tag-line">配料单</span><b>{{ simBaseline.chargeNo || '（无配料明细）' }}</b>
          <span class="tag-line">当前合计</span><b>{{ fmtKg(simBaseline.currentTotalKg) }} kg</b>
          <span class="tag-line">当前回炉料</span>
          <b :class="{ 'over-text': Number(simBaseline.currentScrapRate) > Number(simBaseline.scrapLimitRate) }">
            {{ fmtKg(simBaseline.currentScrapRate) }}%
          </b>
          <span class="tag-line">目标</span><b>{{ fmtKg(simBaseline.targetWeight) }} kg</b>
          <span class="tag-line">炉体容量</span><b>{{ simBaseline.furnaceNo }} · {{ fmtKg(simBaseline.capacityKg) }} kg</b>
          <span class="tag-line">允许偏差</span><b>±{{ simBaseline.deviationLimitRate }}%</b>
          <span class="tag-line">回炉料上限</span><b>{{ fmtKg(simBaseline.scrapLimitRate) }}%</b>
        </div>
        <div class="sim-baseline sim-items" v-if="simBaseline && simBaseline.items && simBaseline.items.length">
          <span class="tag-line">当前明细</span>
          <span
            v-for="it in simBaseline.items"
            :key="it.materialCode"
            class="mini-item"
            :class="{ incompatible: !it.compatible }"
          >{{ it.materialName }} {{ fmtKg(it.weightKg) }} kg<template v-if="!it.compatible">（与材质不兼容）</template></span>
        </div>

        <!-- 具体冲突：不显示误导性推荐 -->
        <div class="sim-conflicts" v-if="simConflicts.length">
          <div class="conflict-title">⚠ 约束冲突，无法形成合法方案</div>
          <div v-for="(c, i) in simConflicts" :key="i" class="conflict-line">{{ c }}</div>
        </div>

        <!-- 双方案卡片 -->
        <div class="sim-plans">
          <div
            v-for="entry in simPlans"
            :key="entry.key"
            class="plan-card"
            :class="{ recommended: simRecommendation && simRecommendation.preferred === entry.key }"
          >
            <div class="plan-head">
              <span class="plan-title">{{ entry.title }}</span>
              <el-tag v-if="simRecommendation && simRecommendation.preferred === entry.key" size="small" type="success">推荐</el-tag>
              <span class="plan-desc">{{ entry.desc }}</span>
            </div>

            <template v-if="entry.plan && entry.plan.feasible">
              <el-table :data="entry.plan.adjustments" size="small" border class="plan-table">
                <el-table-column label="料项" min-width="150">
                  <template #default="{ row }">
                    <span class="dot" :style="{ background: materialColor(row.materialCode) }"></span>
                    {{ row.materialName }}
                    <el-tag v-if="row.isNew" size="small" type="warning" class="row-tag">新料</el-tag>
                    <el-tag v-if="row.removed" size="small" type="danger" class="row-tag">移除</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="当前 kg" width="100" align="right">
                  <template #default="{ row }">{{ fmtKg(row.currentKg) }}</template>
                </el-table-column>
                <el-table-column label="建议 kg" width="100" align="right">
                  <template #default="{ row }">{{ fmtKg(row.suggestedKg) }}</template>
                </el-table-column>
                <el-table-column label="增减 kg" width="110" align="right">
                  <template #default="{ row }">
                    <b :class="Number(row.deltaKg) > 0 ? 'up-text' : 'down-text'">
                      {{ Number(row.deltaKg) > 0 ? '+' : '' }}{{ fmtKg(row.deltaKg) }}
                    </b>
                  </template>
                </el-table-column>
              </el-table>
              <div class="empty-tip" v-if="!entry.plan.adjustments || !entry.plan.adjustments.length">无需调整，当前配料已满足全部约束</div>
              <div class="plan-metrics">
                <span class="tag-line">试算总重</span><b>{{ fmtKg(entry.plan.totalWeight) }} kg</b>
                <span class="tag-line">偏差率</span>
                <b :class="{ 'over-text': Math.abs(Number(entry.plan.deviationRate)) > maxRate }">{{ fmtKg(entry.plan.deviationRate) }}%</b>
                <span class="tag-line">回炉料占比</span><b>{{ fmtKg(entry.plan.scrapRate) }}%</b>
                <span class="tag-line">补料成本</span><b>{{ fmtKg(entry.plan.costTotal) }} 元</b>
                <span class="tag-line">成分偏离</span><b>{{ fmtKg(entry.plan.compositionDeviation) }} pt</b>
              </div>
            </template>
            <div class="plan-infeasible" v-else>
              <div class="conflict-title">✕ 本方案不可行</div>
              <div v-for="(r, i) in (entry.plan ? entry.plan.infeasibleReasons : [])" :key="i" class="conflict-line">{{ r }}</div>
            </div>
          </div>
        </div>

        <!-- 推荐取舍 -->
        <div
          class="sim-rec"
          :class="{ none: !simRecommendation || simRecommendation.preferred === 'NONE', conflict: simConflicts.length > 0 }"
          v-if="simRecommendation"
        >
          <b>{{ recText(simRecommendation) }}</b>
          <span>{{ simRecommendation.reason }}</span>
          <span v-if="simRecommendation.sacrifice" class="sacrifice">牺牲说明：{{ simRecommendation.sacrifice }}</span>
        </div>
      </template>
    </section>

    <el-dialog v-model="createOpen" title="新建配料单" width="620px">
      <el-form label-width="88px">
        <el-form-item label="配料单号"><el-input v-model="form.chargeNo" placeholder="如 CH-2604-0101" /></el-form-item>
        <el-form-item label="目标炉次">
          <el-select v-model="form.heatId" placeholder="选择已出炉的炉次" style="width:100%">
            <el-option
              v-for="h in board.heats"
              :key="h.id"
              :label="h.heatNo + ' · ' + h.alloyName + ' · 出炉 ' + Number(h.tapWeight).toFixed(0) + ' kg'"
              :value="h.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配料员"><el-input v-model="form.operator" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>

      <div class="rows-title">配料明细（同一配料单重复品种由后端自动合并）</div>
      <div class="new-row" v-for="(it, idx) in form.items" :key="idx">
        <el-select v-model="it.materialCode" size="small" style="width:180px">
          <el-option v-for="m in board.materials" :key="m.code" :label="m.name" :value="m.code" />
        </el-select>
        <el-input-number v-model="it.weightKg" size="small" :min="1" :step="10" />
        <el-button size="small" type="danger" plain @click="removeRow(idx)">移除</el-button>
      </div>
      <el-button size="small" plain @click="addRow">+ 再加一种炉料</el-button>

      <div class="dev-box" :class="{ over: formOver }">
        <span>合计 {{ formTotal.toFixed(2) }} kg</span>
        <span>目标 {{ formTarget.toFixed(2) }} kg</span>
        <span v-if="formDeviation !== null">偏差 {{ formDeviation.toFixed(2) }}%</span>
        <span>（允许 ±{{ maxRate }}%）</span>
      </div>

      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="createCharge">建立配料单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sum-flag {
  margin-left: auto;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--pass);
}
.sum-flag.over { background: var(--pour); }

.slide-row { padding: 8px 0; border-bottom: 1px dashed var(--line); }
.slide-head { display: flex; align-items: center; gap: 10px; font-size: 12.5px; }
.mat-name { flex: 1 1 auto; font-weight: 700; }
.mat-weight { color: var(--ink-soft); }
.mat-ratio { width: 62px; text-align: right; font-weight: 800; color: var(--primary-deep); }

.slide-track {
  margin: 6px 0;
  height: 14px;
  border-radius: 8px;
  background: #f1ebf6;
  overflow: hidden;
  box-shadow: inset 0 1px 2px rgba(80, 40, 100, 0.08);
}
.slide-bar {
  height: 100%;
  border-radius: 8px;
  transition: width .3s ease;
  box-shadow: 0 2px 8px rgba(142, 36, 170, 0.24);
}

.slide-op { display: flex; align-items: center; gap: 6px; }

.dev-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; font-size: 12.5px; }
.dev-row b { color: var(--primary-deep); }
.dev-row b.over { color: var(--pour); }

.sheet-list { max-height: 340px; overflow-y: auto; }
.sheet-item {
  position: relative;
  padding: 9px 90px 9px 12px;
  border-radius: 9px;
  border: 1px solid var(--line);
  margin-bottom: 8px;
  cursor: pointer;
  transition: all .18s ease;
}
.sheet-item:hover { border-color: var(--primary-soft); background: #fbf7fd; }
.sheet-item.on { border-color: var(--primary); background: var(--primary-wash); }
.sheet-no { font-size: 13px; font-weight: 700; color: var(--primary-deep); }
.sheet-sub { font-size: 11px; color: var(--ink-dim); margin-top: 2px; }
.sheet-rate {
  position: absolute;
  right: 12px; top: 50%;
  transform: translateY(-50%);
  font-size: 13px; font-weight: 800; color: var(--pass);
}
.sheet-rate.over { color: var(--pour); }

.rows-title { font-size: 12.5px; font-weight: 700; margin: 6px 0 8px; }
.new-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }

.dev-box {
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  background: #eef6ef;
  border: 1px solid #c9e6cf;
  color: #256b2c;
  font-size: 12.5px;
  display: flex;
  gap: 12px;
}
.dev-box.over { background: #fdecea; border-color: #f3c2bd; color: #a01a14; font-weight: 700; }

.op-row { margin-top: 12px; display: flex; gap: 8px; }

/* ---------------- 双方案推演面板 ---------------- */
.sim-panel { margin-top: 12px; }
.sim-inputs { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sim-label { font-size: 12px; color: var(--ink-dim); }
.sim-caps {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 8px 10px; border-radius: 9px; background: #faf8fc; border: 1px dashed var(--line);
  margin-bottom: 10px; font-size: 12px; color: var(--ink-soft);
}
.cap-item { display: inline-flex; align-items: center; gap: 5px; }

.sim-baseline { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12.5px; margin-bottom: 6px; }
.sim-baseline b { color: var(--primary-deep); }
.sim-baseline b.over-text { color: var(--pour); }
.sim-items { margin-bottom: 10px; }
.mini-item {
  padding: 2px 8px; border-radius: 6px; font-size: 11.5px;
  background: #f1ecf6; color: var(--ink-soft);
}
.mini-item.incompatible { background: #fdecea; color: #a01a14; font-weight: 700; }

.sim-conflicts {
  padding: 10px 12px; border-radius: 9px; margin-bottom: 10px;
  background: #fdecea; border: 1px solid #f3c2bd; color: #a01a14; font-size: 12.5px;
}
.conflict-title { font-weight: 800; margin-bottom: 4px; }
.conflict-line { padding-left: 14px; line-height: 1.7; }

.sim-plans { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.plan-card {
  border: 1px solid var(--line-strong); border-radius: 10px; padding: 10px 12px;
  background: #fdfcfe; transition: box-shadow .2s ease;
}
.plan-card.recommended { border-color: var(--pass); box-shadow: 0 0 0 2px rgba(46, 125, 50, 0.18); }
.plan-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.plan-title { font-size: 13px; font-weight: 800; color: var(--primary-deep); }
.plan-desc { font-size: 11px; color: var(--ink-dim); }
.plan-table { margin-bottom: 8px; }
.row-tag { margin-left: 4px; }
.plan-metrics { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12.5px; }
.plan-metrics b { color: var(--primary-deep); }
.plan-metrics b.over-text { color: var(--pour); }
.up-text { color: var(--pour); }
.down-text { color: var(--pass); }
.plan-infeasible {
  padding: 10px 12px; border-radius: 9px;
  background: #fdecea; border: 1px solid #f3c2bd; color: #a01a14; font-size: 12.5px;
}

.sim-rec {
  margin-top: 12px; padding: 10px 12px; border-radius: 9px;
  background: #eef6ef; border: 1px solid #c9e6cf; color: #256b2c;
  font-size: 12.5px; display: flex; flex-direction: column; gap: 3px;
}
.sim-rec.none { background: #f1ecf6; border-color: var(--line-strong); color: var(--ink-soft); }
.sim-rec.conflict { background: #fdecea; border-color: #f3c2bd; color: #a01a14; }
.sim-rec .sacrifice { font-size: 12px; opacity: 0.9; }
</style>

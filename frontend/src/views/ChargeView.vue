<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const sheets = ref([])
const detail = ref(null)
const board = ref({ heats: [], materials: [], maxDeviationRate: 3 })
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
</style>

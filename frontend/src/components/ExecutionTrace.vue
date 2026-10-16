<template>
  <details v-if="trace.length" class="execution-trace" :open="expanded">
    <summary>执行记录 · {{ trace.length }} 步<span v-if="requestId"> · {{ requestId }}</span></summary>
    <div v-for="(step, index) in trace" :key="step.id || index" class="trace-step">
      <div class="trace-meta">
        <span>{{ step.kind === 'tool' ? '工具' : step.kind === 'model' ? '模型' : '错误' }} · {{ step.name }}</span>
        <span :class="step.status === 'error' ? 'trace-error' : 'trace-success'">{{ step.status }}</span>
        <span>{{ step.durationMs ?? 0 }} ms</span>
      </div>
      <pre v-if="step.arguments != null">参数：{{ format(step.arguments) }}</pre>
      <pre v-if="step.result != null">结果：{{ format(step.result) }}</pre>
      <pre v-if="step.error != null" class="trace-error">错误：{{ format(step.error) }}</pre>
    </div>
  </details>
</template>

<script setup>
defineProps({
  trace: { type: Array, default: () => [] },
  requestId: { type: String, default: '' },
  expanded: { type: Boolean, default: false }
})
const format = (value) => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
</script>

<style scoped>
.execution-trace { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 10px; color: var(--text-2); font-size: 12px; }
summary { cursor: pointer; overflow-wrap: anywhere; }
summary span { color: var(--text-3); font-size: 10px; }
.trace-step { margin-top: 10px; padding: 10px; background: var(--bg-deep); border-radius: 6px; }
.trace-meta { display: flex; flex-wrap: wrap; gap: 12px; font-family: var(--font-mono); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px 0 0; max-height: 240px; overflow-y: auto; font: 12px/1.6 var(--font-mono); }
.trace-error { color: var(--red); }
.trace-success { color: var(--green); }
</style>

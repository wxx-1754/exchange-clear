<script setup lang="ts">
import { computed } from 'vue'
import { toneColor, type StatusMeta } from '@/utils/enums'

const props = defineProps<{
  meta: StatusMeta
  showCode?: boolean
}>()

const color = computed(() => toneColor(props.meta.tone))
</script>

<template>
  <span
    class="status-tag"
    :style="{
      color: color.fg,
      background: color.bg,
      borderColor: color.bd,
    }"
  >
    <span class="dot" :style="{ background: color.fg }" />
    <span class="desc">{{ meta.desc }}</span>
    <span v-if="showCode" class="code">{{ meta.code }}</span>
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  border: 1px solid;
  border-radius: 3px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
  line-height: 1;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.desc {
  font-family: var(--font-ui);
}
.code {
  opacity: 0.6;
  font-size: 10px;
}
</style>

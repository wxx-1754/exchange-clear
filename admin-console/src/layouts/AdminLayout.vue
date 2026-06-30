<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { navItems } from '@/router'

const route = useRoute()
const activeIndex = computed(() => route.meta.index ?? '')
const activeTitle = computed(() => route.meta.title ?? '')
const activeCaption = computed(() => route.meta.caption ?? '')

// live session clock — the terminal's heartbeat
const now = ref('')
let timer: number | undefined
function tick() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  now.value = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell">
    <!-- ───────── sidebar ───────── -->
    <aside class="rail">
      <div class="brand">
        <span class="mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22">
            <rect x="5" y="3.5" width="14" height="17" rx="1.2" fill="none" stroke="#e8b339" stroke-width="1.3" />
            <line x1="8" y1="8" x2="16" y2="8" stroke="#e8b339" stroke-width="1.3" stroke-linecap="round" />
            <line x1="8" y1="12" x2="16" y2="12" stroke="#ece6d8" stroke-width="1.3" stroke-linecap="round" />
            <line x1="8" y1="16" x2="13" y2="16" stroke="#ece6d8" stroke-width="1.3" stroke-linecap="round" opacity="0.55" />
          </svg>
        </span>
        <div class="brand-text">
          <span class="brand-name">ExchangeClear</span>
          <span class="brand-sub font-mono">CLEARING DESK · V6</span>
        </div>
      </div>

      <nav class="nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.index"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: activeIndex === item.index }"
        >
          <span class="nav-idx font-mono">{{ item.index }}</span>
          <span class="nav-label">{{ item.title }}</span>
          <span v-if="!item.ready" class="nav-tag font-mono">soon</span>
        </RouterLink>
      </nav>

      <div class="rail-foot">
        <div class="env">
          <span class="env-dot" />
          <span class="font-mono">DEV · localhost</span>
        </div>
      </div>
    </aside>

    <!-- ───────── main ───────── -->
    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-idx font-mono">{{ activeIndex }}</span>
          <span class="crumb-sep">/</span>
          <h1 class="crumb-title">{{ activeTitle }}</h1>
          <span class="crumb-cap font-mono">{{ activeCaption }}</span>
        </div>

        <div class="session">
          <div class="session-item">
            <span class="session-label font-mono">SESSION</span>
            <span class="session-val font-mono">{{ now }}</span>
          </div>
          <span class="session-sep" />
          <div class="session-item">
            <span class="live-dot" />
            <span class="session-val font-mono">SYSTEM · ONLINE</span>
          </div>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 248px 1fr;
  height: 100%;
  position: relative;
  z-index: 1;
}

/* ---- sidebar ---- */
.rail {
  background: var(--ink-raised);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  padding: 22px 0 0;
  position: relative;
}
.rail::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 1px;
  height: 120px;
  background: linear-gradient(var(--amber-line), transparent);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 22px 26px;
  border-bottom: 1px solid var(--line);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.brand-name {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 19px;
  color: var(--cream);
  letter-spacing: -0.01em;
}
.brand-sub {
  color: var(--fg-dim);
  font-size: 10px;
  letter-spacing: 0.14em;
  margin-top: 3px;
}

.nav {
  flex: 1;
  padding: 14px 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 22px;
  color: var(--fg-muted);
  text-decoration: none;
  font-size: 13.5px;
  font-weight: 500;
  position: relative;
  transition: color 0.15s, background 0.15s;
}
.nav-item:hover {
  color: var(--cream);
  background: var(--surface);
}
.nav-idx {
  font-size: 11px;
  color: var(--fg-dim);
  width: 18px;
  letter-spacing: 0.06em;
}
.nav-label {
  flex: 1;
}
.nav-tag {
  font-size: 9px;
  letter-spacing: 0.1em;
  color: var(--fg-dim);
  border: 1px solid var(--line);
  padding: 1px 5px;
  border-radius: 3px;
}
.nav-item.active {
  color: var(--amber);
  background: var(--surface);
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  background: var(--amber);
}
.nav-item.active .nav-idx {
  color: var(--amber);
}

.rail-foot {
  padding: 16px 22px;
  border-top: 1px solid var(--line);
}
.env {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--fg-muted);
  font-size: 11px;
}
.env-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sage);
  box-shadow: 0 0 8px var(--sage);
}

/* ---- topbar ---- */
.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}
.topbar {
  height: 60px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  background: rgba(12, 14, 17, 0.85);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  position: sticky;
  top: 0;
  z-index: 10;
}
.crumb {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.crumb-idx {
  color: var(--amber);
  font-size: 12px;
  letter-spacing: 0.1em;
}
.crumb-sep {
  color: var(--fg-dim);
}
.crumb-title {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 20px;
  color: var(--cream);
  margin: 0;
  letter-spacing: -0.01em;
}
.crumb-cap {
  color: var(--fg-dim);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  margin-left: 4px;
}

.session {
  display: flex;
  align-items: center;
  gap: 18px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.session-label {
  color: var(--fg-dim);
  font-size: 10px;
  letter-spacing: 0.14em;
}
.session-val {
  color: var(--fg);
  font-size: 12px;
  letter-spacing: 0.06em;
}
.session-sep {
  width: 1px;
  height: 16px;
  background: var(--line-strong);
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sage);
  box-shadow: 0 0 0 0 var(--sage);
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(127, 176, 105, 0.5);
  }
  70% {
    box-shadow: 0 0 0 6px rgba(127, 176, 105, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(127, 176, 105, 0);
  }
}

.content {
  flex: 1;
  overflow: auto;
  padding: 28px;
}

/* route transition */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.fade-leave-to {
  opacity: 0;
}
</style>

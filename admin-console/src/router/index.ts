import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

export interface NavMeta {
  name: string
  index: string
  title: string
  caption: string
  icon: string
  ready: boolean
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { name: 'dashboard', index: '01', title: '概览看板', caption: 'Dashboard', icon: 'DataLine', ready: true },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/views/task/TaskView.vue'),
    meta: { name: 'tasks', index: '02', title: '任务管理', caption: 'Tasks', icon: 'Tickets', ready: true },
  },
  {
    path: '/files',
    name: 'files',
    component: () => import('@/views/file/FileView.vue'),
    meta: { name: 'files', index: '03', title: '文件管理', caption: 'Files', icon: 'Document', ready: true },
  },
  {
    path: '/publish',
    name: 'publish',
    component: () => import('@/views/publish/PublishView.vue'),
    meta: { name: 'publish', index: '04', title: '发布管理', caption: 'Publish', icon: 'Promotion', ready: true },
  },
  {
    path: '/download',
    name: 'download',
    component: () => import('@/views/download/DownloadView.vue'),
    meta: { name: 'download', index: '05', title: '下载中心', caption: 'Download', icon: 'Download', ready: true },
  },
  {
    path: '/audits',
    name: 'audits',
    component: () => import('@/views/audit/AuditView.vue'),
    meta: { name: 'audits', index: '06', title: '审计查询', caption: 'Audit', icon: 'DocumentChecked', ready: true },
  },
  {
    path: '/stats',
    name: 'stats',
    component: () => import('@/views/stats/StatsView.vue'),
    meta: { name: 'stats', index: '07', title: '下载统计', caption: 'Stats', icon: 'TrendCharts', ready: true },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export default router

export const navItems: NavMeta[] = [
  { name: 'dashboard', index: '01', title: '概览看板', caption: 'Dashboard', icon: 'DataLine', ready: true },
  { name: 'tasks', index: '02', title: '任务管理', caption: 'Tasks', icon: 'Tickets', ready: true },
  { name: 'files', index: '03', title: '文件管理', caption: 'Files', icon: 'Document', ready: true },
  { name: 'publish', index: '04', title: '发布管理', caption: 'Publish', icon: 'Promotion', ready: true },
  { name: 'download', index: '05', title: '下载中心', caption: 'Download', icon: 'Download', ready: true },
  { name: 'audits', index: '06', title: '审计查询', caption: 'Audit', icon: 'DocumentChecked', ready: true },
  { name: 'stats', index: '07', title: '下载统计', caption: 'Stats', icon: 'TrendCharts', ready: true },
]

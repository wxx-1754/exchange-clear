import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    name?: string
    index?: string
    title?: string
    caption?: string
    icon?: string
    ready?: boolean
  }
}

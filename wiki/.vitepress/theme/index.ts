import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { defineAsyncComponent, h } from 'vue'
import HeroAnimation from './HeroAnimation.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  // Home page hero image: a paused leader's stale write is rejected by its fencing token
  Layout: () => h(DefaultTheme.Layout, null, {
    'home-hero-image': () => h(HeroAnimation),
  }),
  enhanceApp({ app }) {
    // As in the CoCache and Wow docs: vitepress-plugin-mermaid turns ```mermaid blocks into <Mermaid>;
    // the component renders them, follows the light/dark theme and adds zoom, pan and an expanded view.
    app.component('Mermaid', defineAsyncComponent(() => import('./Mermaid.vue')))
  },
} satisfies Theme

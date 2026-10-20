import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { defineAsyncComponent } from 'vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    // As in the CoCache and Wow docs: vitepress-plugin-mermaid turns ```mermaid blocks into <Mermaid>;
    // the component renders them, follows the light/dark theme and adds zoom, pan and an expanded view.
    app.component('Mermaid', defineAsyncComponent(() => import('./Mermaid.vue')))
  },
} satisfies Theme

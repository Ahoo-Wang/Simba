import { defineConfig } from 'vitepress'
import { en } from './en'
import { zh } from './zh'

export default defineConfig({
  title: 'Simba',
  description: 'Distributed Mutex Library for the JVM',
  site: 'https://simba.ahoo.me/',
  lastUpdated: true,
  cleanUrls: true,
  sitemap: {
    hostname: 'https://simba.ahoo.me/',
  },
  ignoreDeadLinks: [
    /localhost/,
    /file_path:/,
  ],
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
  ],
  themeConfig: {
    search: {
      provider: 'local',
      options: {
        detailedView: true,
        translations: {
          button: {
            buttonText: 'Search',
            buttonAriaLabel: 'Search',
          },
          modal: {
            displayDetails: 'Display detailed list',
            resetButtonTitle: 'Reset search',
            backButtonTitle: 'Back',
            noResultsText: 'No results found',
            footer: {
              selectText: 'to select',
              navigateText: 'to navigate',
              closeText: 'to close',
            },
          },
        },
      },
    },
  },
  locales: {
    root: {
      ...en,
    },
    zh: {
      ...zh,
      themeConfig: {
        ...zh.themeConfig,
        search: {
          provider: 'local',
          options: {
            detailedView: true,
            translations: {
              button: {
                buttonText: '搜索',
                buttonAriaLabel: '搜索文档',
              },
              modal: {
                displayDetails: '显示详情',
                resetButtonTitle: '清除搜索',
                backButtonTitle: '返回',
                noResultsText: '未找到相关结果',
                footer: {
                  selectText: '选择',
                  navigateText: '切换',
                  closeText: '关闭',
                },
              },
            },
          },
        },
      },
    },
  },
  markdown: {
    lineNumbers: true,
  },
  vite: {
    plugins: [],
  },
})

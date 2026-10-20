import { DefaultTheme } from 'vitepress'

export const zh: DefaultTheme.Config = {
  label: '中文',
  lang: 'zh-CN',
  title: 'Simba',
  description: 'JVM 选主与分布式锁库',
  themeConfig: {
    nav: [
      { text: '指南', link: '/zh/guide/' },
      { text: 'API', link: '/zh/api/' },
      { text: '架构', link: '/zh/architecture/' },
      { text: '参与贡献', link: '/zh/contributing/' },
      {
        text: '版本',
        items: [
          { text: '升级', link: '/zh/guide/upgrading' },
          { text: '从 4.x 迁移', link: '/zh/guide/migration' },
          { text: '发布说明', link: 'https://github.com/Ahoo-Wang/Simba/releases' },
        ],
      },
    ],
    sidebar: {
      '/zh/guide/': [
        {
          text: '入门',
          items: [
            { text: '为什么选择 Simba', link: '/zh/guide/' },
            { text: '快速开始', link: '/zh/guide/quick-start' },
            { text: 'Simba 如何工作', link: '/zh/guide/concepts' },
          ],
        },
        {
          text: '使用 Simba',
          items: [
            { text: '选举与 Leader 任务', link: '/zh/guide/leader-work' },
            { text: '锁', link: '/zh/guide/locks' },
            { text: 'Spring Boot', link: '/zh/guide/configuration' },
            { text: '测试', link: '/zh/guide/testing' },
          ],
        },
        {
          text: '生产运行',
          items: [
            { text: 'Fencing 与故障', link: '/zh/guide/correctness' },
            { text: '后端', link: '/zh/guide/backends' },
            { text: '可观测性', link: '/zh/guide/observability' },
          ],
        },
        {
          text: '版本',
          items: [
            { text: '升级', link: '/zh/guide/upgrading' },
            { text: '从 4.x 迁移', link: '/zh/guide/migration' },
          ],
        },
      ],
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Ahoo-Wang/Simba' },
    ],
    footer: {
      message: '基于 Apache License 2.0 发布。',
      copyright: 'Copyright 2021-present Ahoo Wang',
    },
    editLink: {
      pattern: 'https://github.com/Ahoo-Wang/Simba/edit/main/wiki/:path',
      text: '在 GitHub 上编辑此页面',
    },
    outline: {
      label: '页面导航',
    },
    lastUpdated: {
      text: '最后更新于',
    },
    docFooter: {
      prev: '上一页',
      next: '下一页',
    },
  },
}

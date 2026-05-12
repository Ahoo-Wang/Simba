import { DefaultTheme } from 'vitepress'

export const zh: DefaultTheme.Config = {
  label: '中文',
  lang: 'zh-CN',
  title: 'Simba',
  description: 'JVM 分布式互斥锁库',
  themeConfig: {
    nav: [
      { text: '指南', link: '/zh/guide/' },
      { text: '架构', link: '/zh/architecture/' },
      { text: 'API', link: '/zh/api/' },
      { text: '模块', link: '/zh/modules/' },
      { text: '入门指南', link: '/zh/onboarding/' },
      {
        text: 'v3.0',
        items: [
          { text: '贡献指南', link: '/zh/guide/contributing' },
        ],
      },
    ],
    sidebar: {
      '/zh/guide/': [
        {
          text: '快速开始',
          items: [
            { text: '介绍', link: '/zh/guide/' },
            { text: '快速上手', link: '/zh/guide/quick-start' },
            { text: '配置', link: '/zh/guide/configuration' },
          ],
        },
      ],
      '/zh/architecture/': [
        {
          text: '架构',
          items: [
            { text: '概览', link: '/zh/architecture/' },
            { text: '核心抽象', link: '/zh/architecture/core-abstractions' },
            { text: '竞争机制', link: '/zh/architecture/contention-mechanics' },
            { text: '后端实现', link: '/zh/architecture/backends' },
          ],
        },
      ],
      '/zh/api/': [
        {
          text: 'API 参考',
          items: [
            { text: '概览', link: '/zh/api/' },
            { text: '核心接口', link: '/zh/api/core-interfaces' },
            { text: '锁 API', link: '/zh/api/locker-api' },
            { text: '调度器 API', link: '/zh/api/scheduler-api' },
          ],
        },
      ],
      '/zh/modules/': [
        {
          text: '模块',
          items: [
            { text: '概览', link: '/zh/modules/' },
            { text: 'simba-core', link: '/zh/modules/simba-core' },
            { text: 'simba-jdbc', link: '/zh/modules/simba-jdbc' },
            { text: 'simba-spring-redis', link: '/zh/modules/simba-spring-redis' },
            { text: 'simba-zookeeper', link: '/zh/modules/simba-zookeeper' },
            { text: 'simba-spring-boot-starter', link: '/zh/modules/simba-spring-boot-starter' },
            { text: 'simba-test', link: '/zh/modules/simba-test' },
          ],
        },
      ],
      '/zh/testing/': [
        {
          text: '测试',
          items: [
            { text: '概览', link: '/zh/testing/' },
            { text: '单元测试', link: '/zh/testing/unit-testing' },
            { text: '集成测试', link: '/zh/testing/integration-testing' },
            { text: 'TCK 兼容性测试', link: '/zh/testing/tck' },
          ],
        },
      ],
      '/zh/onboarding/': [
        {
          text: '入门指南',
          collapsed: false,
          items: [
            { text: '贡献者指南', link: '/zh/onboarding/contributor' },
            { text: '高级工程师指南', link: '/zh/onboarding/staff-engineer' },
            { text: '管理层指南', link: '/zh/onboarding/executive' },
            { text: '产品经理指南', link: '/zh/onboarding/product-manager' },
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

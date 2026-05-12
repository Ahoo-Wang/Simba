import { DefaultTheme } from 'vitepress'

export const en: DefaultTheme.Config = {
  label: 'English',
  lang: 'en',
  title: 'Simba',
  description: 'Distributed Mutex Library for the JVM',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'API', link: '/api/' },
      { text: 'Modules', link: '/modules/' },
      { text: 'Onboarding', link: '/onboarding/' },
      {
        text: 'v3.0',
        items: [
          { text: 'Contributing', link: '/guide/contributing' },
        ],
      },
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Quick Start', link: '/guide/quick-start' },
            { text: 'Configuration', link: '/guide/configuration' },
          ],
        },
      ],
      '/architecture/': [
        {
          text: 'Architecture',
          items: [
            { text: 'Overview', link: '/architecture/' },
            { text: 'Core Abstractions', link: '/architecture/core-abstractions' },
            { text: 'Contention Mechanics', link: '/architecture/contention-mechanics' },
            { text: 'Backend Implementations', link: '/architecture/backends' },
          ],
        },
      ],
      '/api/': [
        {
          text: 'API Reference',
          items: [
            { text: 'Overview', link: '/api/' },
            { text: 'Core Interfaces', link: '/api/core-interfaces' },
            { text: 'Locker API', link: '/api/locker-api' },
            { text: 'Scheduler API', link: '/api/scheduler-api' },
          ],
        },
      ],
      '/modules/': [
        {
          text: 'Modules',
          items: [
            { text: 'Overview', link: '/modules/' },
            { text: 'simba-core', link: '/modules/simba-core' },
            { text: 'simba-jdbc', link: '/modules/simba-jdbc' },
            { text: 'simba-spring-redis', link: '/modules/simba-spring-redis' },
            { text: 'simba-zookeeper', link: '/modules/simba-zookeeper' },
            { text: 'simba-spring-boot-starter', link: '/modules/simba-spring-boot-starter' },
            { text: 'simba-test', link: '/modules/simba-test' },
          ],
        },
      ],
      '/testing/': [
        {
          text: 'Testing',
          items: [
            { text: 'Overview', link: '/testing/' },
            { text: 'Unit Testing', link: '/testing/unit-testing' },
            { text: 'Integration Testing', link: '/testing/integration-testing' },
            { text: 'TCK', link: '/testing/tck' },
          ],
        },
      ],
      '/onboarding/': [
        {
          text: 'Onboarding',
          collapsed: false,
          items: [
            { text: 'Contributor Guide', link: '/onboarding/contributor' },
            { text: 'Staff Engineer Guide', link: '/onboarding/staff-engineer' },
            { text: 'Executive Guide', link: '/onboarding/executive' },
            { text: 'Product Manager Guide', link: '/onboarding/product-manager' },
          ],
        },
      ],
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Ahoo-Wang/Simba' },
    ],
    footer: {
      message: 'Released under the Apache License 2.0.',
      copyright: 'Copyright 2021-present Ahoo Wang',
    },
    editLink: {
      pattern: 'https://github.com/Ahoo-Wang/Simba/edit/main/wiki/:path',
      text: 'Edit this page on GitHub',
    },
  },
}

import { DefaultTheme } from 'vitepress'

export const en: DefaultTheme.Config = {
  label: 'English',
  lang: 'en',
  title: 'Simba',
  description: 'Leader election and distributed locks for the JVM',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'API', link: '/api/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'Contributing', link: '/contributing/' },
      {
        text: 'Releases',
        items: [
          { text: 'Upgrading', link: '/guide/upgrading' },
          { text: 'Migrating from 4.x', link: '/guide/migration' },
          { text: 'Release Notes', link: 'https://github.com/Ahoo-Wang/Simba/releases' },
        ],
      },
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Guide',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Quick Start', link: '/guide/quick-start' },
            { text: 'Concepts and Correctness', link: '/guide/correctness' },
            { text: 'Backends', link: '/guide/backends' },
            { text: 'Spring Boot', link: '/guide/configuration' },
            { text: 'Leader Work and Partitions', link: '/guide/leader-work' },
            { text: 'Locks', link: '/guide/locks' },
            { text: 'Observability', link: '/guide/observability' },
            { text: 'Testing', link: '/guide/testing' },
            { text: 'Upgrading', link: '/guide/upgrading' },
            { text: 'Migrating from 4.x', link: '/guide/migration' },
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

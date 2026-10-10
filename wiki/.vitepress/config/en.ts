import { DefaultTheme } from 'vitepress'

export const en: DefaultTheme.Config = {
  label: 'English',
  lang: 'en',
  title: 'Simba',
  description: 'Leader election and distributed mutex for the JVM',
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
            { text: 'Backends', link: '/guide/backends' },
            { text: 'Correctness', link: '/guide/correctness' },
            { text: 'Configuration', link: '/guide/configuration' },
            { text: 'Observability', link: '/guide/observability' },
            { text: 'Upgrading', link: '/guide/upgrading' },
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

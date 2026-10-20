<!--
 Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<!--
  Home page animation: one 8-second loop that tells Simba's whole contract.
  0–30%:  node A leads (token 7) and keeps its lease alive.
  30–45%: A pauses (GC); without keep-alives the lease runs out.
  45–65%: node C wins the lease with token 8 and writes; the resource records 8.
  70–90%: A wakes up and sends its stale write with token 7; the resource rejects it.
  With "reduce motion" the animation stays on its first frame.
-->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const svg = ref<SVGSVGElement>()
let media: MediaQueryList | undefined

const applyMotionPreference = () => {
  if (!svg.value) return
  if (media?.matches) {
    svg.value.pauseAnimations()
    svg.value.setCurrentTime(0)
  } else {
    svg.value.unpauseAnimations()
  }
}

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', applyMotionPreference)
  applyMotionPreference()
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', applyMotionPreference)
})

// Node A leads until it pauses (30%); node C leads from 52%. The highlight is animated with SMIL so that one clock
// drives the whole loop and pauseAnimations() freezes all of it.
const nodes = [
  { x: 80, name: 'A', highlight: { cls: 'sb-lead-old', keyTimes: '0;0.3', values: '1;0' } },
  { x: 200, name: 'B', highlight: undefined },
  { x: 320, name: 'C', highlight: { cls: 'sb-lead-new', keyTimes: '0;0.52', values: '0;1' } },
]
const keepAlives = [0, 0.1, 0.2]
</script>

<template>
  <svg
    ref="svg"
    class="simba-hero"
    viewBox="0 0 400 380"
    role="img"
    aria-label="Node A leads with token 7 and keeps its lease alive. A pauses, its lease runs out, and node C wins the lease with token 8 and writes. When A wakes up, its stale write with token 7 is rejected by the resource, which has already seen token 8."
  >
    <defs>
      <linearGradient id="sb-brand" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#6d5dfc" />
        <stop offset="1" stop-color="#00d4aa" />
      </linearGradient>
      <filter id="sb-glow" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="3" result="blur" />
        <feMerge>
          <feMergeNode in="blur" />
          <feMergeNode in="SourceGraphic" />
        </feMerge>
      </filter>
      <path id="sb-a-lease" d="M80 118 L176 186" />
      <path id="sb-c-lease" d="M320 118 L224 186" />
      <path id="sb-c-res" d="M320 118 Q356 250 252 300" />
      <path id="sb-a-res" d="M80 118 Q44 250 148 300" />
    </defs>

    <!-- links -->
    <g class="sb-links">
      <line x1="80" y1="118" x2="176" y2="186" />
      <line x1="200" y1="118" x2="200" y2="186" />
      <line x1="320" y1="118" x2="224" y2="186" />
      <use href="#sb-c-res" />
      <use href="#sb-a-res" />
    </g>

    <!-- nodes -->
    <g v-for="n in nodes" :key="n.name" class="sb-node">
      <animate
        v-if="n.name === 'A'"
        attributeName="opacity"
        dur="8s"
        repeatCount="indefinite"
        calcMode="discrete"
        keyTimes="0;0.3;0.7"
        values="1;0.45;1"
      />
      <rect class="sb-node-box" :x="n.x - 52" y="40" width="104" height="78" rx="14" />
      <rect
        v-if="n.highlight"
        :class="['sb-node-highlight', n.highlight.cls]"
        :x="n.x - 52"
        y="40"
        width="104"
        height="78"
        rx="14"
        :opacity="n.highlight.values.split(';')[0]"
      >
        <animate
          attributeName="opacity"
          dur="8s"
          repeatCount="indefinite"
          calcMode="discrete"
          :keyTimes="n.highlight.keyTimes"
          :values="n.highlight.values"
        />
      </rect>
      <text class="sb-label" :x="n.x" y="64">Node {{ n.name }}</text>
    </g>

    <!-- A's term, then "paused" -->
    <g class="sb-badge">
      <rect x="44" y="78" width="72" height="24" rx="12" />
      <text x="80" y="94">token 7</text>
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.8" values="1;0" />
    </g>
    <text class="sb-paused" x="80" y="32">
      paused
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.3;0.7" values="0;1;0" />
    </text>
    <text class="sb-candidate" x="200" y="96">candidate</text>
    <text class="sb-candidate" x="320" y="96">
      candidate
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.52" values="1;0" />
    </text>

    <!-- C's term -->
    <g class="sb-badge sb-badge-new" opacity="0">
      <rect x="284" y="78" width="72" height="24" rx="12" />
      <text x="320" y="94">token 8</text>
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.52" values="0;1" />
    </g>

    <!-- the lease, with its remaining time -->
    <rect class="sb-lease" x="120" y="186" width="160" height="52" rx="12" />
    <text class="sb-lease-label" x="200" y="208">Lease · report</text>
    <rect class="sb-meter-bg" x="140" y="218" width="120" height="8" rx="4" />
    <rect class="sb-meter" x="140" y="218" width="120" height="8" rx="4">
      <animate
        attributeName="width"
        dur="8s"
        repeatCount="indefinite"
        keyTimes="0;0.08;0.1;0.18;0.2;0.28;0.3;0.45;0.52;0.6;0.62;0.7;0.72;0.8;0.82;0.9;0.92;1"
        values="120;100;120;100;120;100;120;0;120;100;120;100;120;100;120;100;120;120"
      />
    </rect>

    <!-- the protected resource -->
    <g class="sb-resource">
      <ellipse cx="200" cy="300" rx="56" ry="11" />
      <path d="M144 300 L144 340 A56 11 0 0 0 256 340 L256 300" />
      <ellipse class="sb-resource-top" cx="200" cy="300" rx="56" ry="11" />
      <text class="sb-label" x="200" y="330">
        highest = 7
        <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.65" values="1;0" />
      </text>
      <text class="sb-label sb-accepted" x="200" y="330" opacity="0">
        highest = 8
        <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.65" values="0;1" />
      </text>
    </g>
    <text class="sb-verdict sb-verdict-ok" x="200" y="282" opacity="0">
      ✓ token 8 accepted
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.65;0.78" values="0;1;0" />
    </text>
    <text class="sb-verdict sb-verdict-reject" x="200" y="282" opacity="0">
      ✗ token 7 rejected
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.8;0.96" values="0;1;0" />
    </text>

    <!-- keep-alives from A -->
    <circle v-for="t in keepAlives" :key="t" class="sb-dot sb-keepalive" r="4.5" filter="url(#sb-glow)">
      <animateMotion
        dur="8s"
        repeatCount="indefinite"
        :keyTimes="`0;${t};${t + 0.08};1`"
        keyPoints="0;0;1;1"
        calcMode="linear"
      >
        <mpath href="#sb-a-lease" />
      </animateMotion>
      <animate
        attributeName="opacity"
        dur="8s"
        repeatCount="indefinite"
        calcMode="discrete"
        :keyTimes="`0;${t};${t + 0.08}`"
        values="0;1;0"
      />
    </circle>

    <!-- C acquires the expired lease -->
    <circle class="sb-dot sb-acquire" r="5" filter="url(#sb-glow)">
      <animateMotion dur="8s" repeatCount="indefinite" keyTimes="0;0.45;0.52;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#sb-c-lease" />
      </animateMotion>
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.45;0.52" values="0;1;0" />
    </circle>

    <!-- C writes with token 8 -->
    <circle class="sb-dot sb-write-new" r="5" filter="url(#sb-glow)">
      <animateMotion dur="8s" repeatCount="indefinite" keyTimes="0;0.55;0.65;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#sb-c-res" />
      </animateMotion>
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.55;0.65" values="0;1;0" />
    </circle>

    <!-- A wakes up and sends its stale write with token 7 -->
    <circle class="sb-dot sb-write-stale" r="5" filter="url(#sb-glow)">
      <animateMotion dur="8s" repeatCount="indefinite" keyTimes="0;0.7;0.8;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#sb-a-res" />
      </animateMotion>
      <animate attributeName="opacity" dur="8s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.7;0.8" values="0;1;0" />
    </circle>

    <!-- legend -->
    <g class="sb-legend">
      <circle class="sb-keepalive" cx="40" cy="368" r="4" />
      <text x="50" y="372">keep-alive</text>
      <circle class="sb-write-new" cx="150" cy="368" r="4" />
      <text x="160" y="372">fenced write</text>
      <circle class="sb-write-stale" cx="268" cy="368" r="4" />
      <text x="278" y="372">stale write</text>
    </g>
  </svg>
</template>

<style scoped>
.simba-hero {
  position: relative;
  width: 100%;
  max-width: 400px;
  height: auto;
  font-family: var(--vp-font-family-base);
}

.sb-links line,
.sb-links use {
  fill: none;
  stroke: var(--vp-c-divider);
  stroke-width: 1.5;
  stroke-dasharray: 4 4;
}

.sb-node-box {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-border);
  stroke-width: 1.5;
}

.sb-node-highlight {
  fill: none;
  stroke-width: 1.5;
}

.sb-lead-old {
  stroke: var(--vp-c-brand-1);
}

.sb-lead-new {
  stroke: #00d4aa;
}

.sb-label {
  fill: var(--vp-c-text-2);
  font-size: 12px;
  text-anchor: middle;
}

.sb-node .sb-label {
  fill: var(--vp-c-text-1);
  font-weight: 600;
}

.sb-badge rect {
  fill: var(--vp-c-brand-soft);
  stroke: var(--vp-c-brand-1);
  stroke-width: 1.2;
}

.sb-badge text {
  fill: var(--vp-c-text-1);
  font-size: 11px;
  font-weight: 600;
  text-anchor: middle;
}

.sb-badge-new rect {
  fill: rgba(0, 212, 170, 0.18);
  stroke: #00d4aa;
}

.sb-candidate {
  fill: var(--vp-c-text-3);
  font-size: 11px;
  text-anchor: middle;
}

.sb-paused {
  fill: #f0b429;
  font-size: 11px;
  font-weight: 600;
  text-anchor: middle;
  opacity: 0;
}

.sb-lease {
  fill: url(#sb-brand);
}

.sb-lease-label {
  fill: #fff;
  font-size: 13px;
  font-weight: 700;
  text-anchor: middle;
}

.sb-meter-bg {
  fill: rgba(255, 255, 255, 0.25);
}

.sb-meter {
  fill: #fff;
}

.sb-resource path,
.sb-resource ellipse {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-border);
  stroke-width: 1.5;
}

.sb-resource .sb-resource-top {
  fill: var(--vp-c-bg-alt);
}

.sb-accepted {
  fill: #00d4aa;
  font-weight: 600;
}

.sb-verdict {
  font-size: 12px;
  font-weight: 700;
  text-anchor: middle;
}

.sb-verdict-ok {
  fill: #00d4aa;
}

.sb-verdict-reject {
  fill: #f85149;
}

.sb-dot {
  opacity: 0;
}

.sb-keepalive,
.sb-acquire {
  fill: var(--vp-c-brand-1);
}

.sb-acquire {
  fill: #00d4aa;
}

.sb-write-new {
  fill: #00d4aa;
}

.sb-write-stale {
  fill: #f85149;
}

.sb-legend text {
  fill: var(--vp-c-text-2);
  font-size: 11px;
}
</style>

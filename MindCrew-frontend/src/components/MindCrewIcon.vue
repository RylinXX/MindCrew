<template>
  <!--
    MindCrew 品牌图标
    设计语言：文档符号 + 神经网络节点 + 向量检索线条
    代表：文档智能 × AI 知识检索
  -->
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <!-- 文档主体 -->
    <rect x="6" y="3" width="16" height="20" rx="2.5" :fill="color" :fill-opacity="0.15" :stroke="color" stroke-width="1.4"/>
    <!-- 折角 -->
    <path :d="`M18 3 L22 7 L18 7 Z`" :fill="color" :fill-opacity="0.35" />
    <path d="M18 3 L18 7 L22 7" :stroke="color" stroke-width="1.4" stroke-linecap="round"/>
    <!-- 文档线条 -->
    <line x1="9.5" y1="12" x2="18.5" y2="12" :stroke="color" stroke-width="1.4" stroke-linecap="round" :stroke-opacity="0.7"/>
    <line x1="9.5" y1="15.5" x2="17" y2="15.5" :stroke="color" stroke-width="1.4" stroke-linecap="round" :stroke-opacity="0.5"/>
    <line x1="9.5" y1="19" x2="14" y2="19" :stroke="color" stroke-width="1.4" stroke-linecap="round" :stroke-opacity="0.35"/>
    <!-- AI 神经节点 -->
    <circle cx="26" cy="10" r="3" :fill="accentColor" :fill-opacity="0.9"/>
    <circle cx="26" cy="19" r="2" :fill="accentColor" :fill-opacity="0.6"/>
    <circle cx="26" cy="26" r="1.5" :fill="accentColor" :fill-opacity="0.4"/>
    <!-- 连接线 -->
    <line x1="22" y1="12" x2="24" y2="10.8" :stroke="accentColor" stroke-width="1" :stroke-opacity="0.5" stroke-linecap="round"/>
    <line x1="26" y1="13" x2="26" y2="17" :stroke="accentColor" stroke-width="1" :stroke-opacity="0.4" stroke-linecap="round"/>
    <line x1="26" y1="21" x2="26" y2="24" :stroke="accentColor" stroke-width="1" :stroke-opacity="0.3" stroke-linecap="round"/>
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  size?: number | string
  color?: string
  accentColor?: string
}>(), {
  size: 32,
  color: '#ffffff',
  accentColor: '#38bdf8',
})
</script>

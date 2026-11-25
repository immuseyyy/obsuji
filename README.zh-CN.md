# Ob速记

English: [README.md](README.md)

Obsidian 的伴生速记应用（Android）。只做快速记录入口，整理与回看归 Obsidian 本体。

- 全屏编辑器 + 控制中心磁贴唤起的浮窗小编窗
- 发送时把条目按模板写进库内目标笔记，附件发送时才复制进附件文件夹
- 录音胶囊：停止进正文待发，发送直写笔记
- 系统分享入口：其他 App 分享文字/图片进浮窗
- 数据存应用专属存储，草稿、历史（200 条）、待发附件登记都在本地

## 结构

- `app/src/main/java/` 壳层（Kotlin）：WebView 容器、全屏/浮窗两个 Activity、原生桥、磁贴
- `app/src/main/assets/editor.html` 编辑器本体（单文件）
- `manual-build/` 手工出包链（aapt2 + kotlinc + d8 + apksigner），不走 Gradle
- `icon-master.svg` 图标母版

## 出包

跑 `manual-build/build-apk.sh`。签名钥匙不在本仓库内，单独保管；没有钥匙打出的包无法覆盖升级现有安装。

## 许可证

MIT，见 [LICENSE](LICENSE)。

## AI 协助说明

本项目是在人的主导下由 AI 助手（Muse）协助开发的：目标、功能取舍和最终决定由仓库所有者定，全部真机测试由所有者完成，AI 按其指示编写代码与文档。写在这里，是把这项工作的来源交代清楚。

# MT_Plugin

这是一个基于上游项目二次开发的 MT 管理器插件分支，当前版本重点增强了 AI 交互体验与代码处理能力。

## 项目来源

- Upstream（原项目）：https://github.com/kggzs/MT_Plugin
- 当前仓库（本分支）：https://github.com/jinge9108/MT_Plugin

> 本仓库保留对原项目的致谢，详细信息见 `CREDITS.md`。

## 你新增的核心功能

- 持续对话：支持连续上下文问答，不再每次都从零开始。
- 隐藏按钮：可快速隐藏 AI 对话/操作入口，减少界面干扰。
- 停止按钮：在流式输出过程中可主动中止请求。
- 可修改代码：分析结果支持直接用于代码调整与编辑流程。

## 使用说明

在 MT 管理器文本编辑器中可使用以下能力：

- AI 代码分析（全文/选中）
- 持续对话与中断控制
- 快速插入与辅助工具

> 具体菜单入口请以插件内界面为准。

## 本地构建 `.mtp`

```bash
cd /storage/emulated/0/AndroidIDEProjects/MT_Plugin-master
./gradlew packageReleaseMtp
```

输出目录：

- `build/outputs/mt-plugin/`

## GitHub 自动构建与发布

本仓库已配置 GitHub Actions：

- 推送到 `main`：自动构建 `.mtp` 并上传 Artifact
- 推送标签 `v*`（例如 `v2.0.1`）：自动创建 Release 并附带 `.mtp`

查看页面：

- Actions: https://github.com/jinge9108/MT_Plugin/actions
- Releases: https://github.com/jinge9108/MT_Plugin/releases

## 标签发布示例

```bash
git tag v2.0.2
git push origin v2.0.2
```

## 免责声明

本项目为二次开发版本。若上游项目包含许可证要求，请在分发时遵守其许可证条款（如保留版权声明、许可证文本等）。

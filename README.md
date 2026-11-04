# MallProject

基于 [macrozheng/mall](https://github.com/macrozheng/mall) 的个人学习与二次开发项目，用于学习 Java 后端开发、权限认证、缓存、事务，以及 Git 与 Codex 协作流程。

- 本仓库维护者：[HongYu Liu](https://github.com/lhy114)
- 本仓库地址：[lhy114/MallProject](https://github.com/lhy114/MallProject)
- 原项目作者：[macrozheng](https://github.com/macrozheng)
- 原项目地址：[macrozheng/mall](https://github.com/macrozheng/mall)

本仓库保留原项目提交历史，并包含维护者在 MyBatis/MyBatis-Plus、后台业务、异常处理和项目文档等方面的学习修改。具体变更以 Git 提交记录为准。本仓库由 lhy114 独立维护，与原作者官方项目无隶属关系。

## 项目结构

|目录|用途|
|---|---|
|`mall-admin`|后台管理接口|
|`mall-portal`|商城接口|
|`mall-search`|商品搜索服务|
|`mall-common`|公共工具、返回结构与异常处理|
|`mall-security`|认证与授权|
|`mall-mbg`|数据模型及生成的数据访问代码|
|`learning`|学习记录与开发实践|

## 开发与学习

使用 Java 17 和 Maven。当前本机开发方式为：基础组件与前端运行在 Docker，后端在 IDEA 中启动。完整本机部署目录位于本仓库之外，克隆本仓库不会自动获得该部署环境。

- [项目协作约束](AGENTS.md)
- [后台功能说明](ADMIN_FUNCTIONS.md)
- [Git 与 GitHub 迁移学习记录](learning/01-git-github-migration.md)

## 来源与许可证

本项目基于原作者的开源成果进行学习和修改，不声明对原项目拥有原创作者身份。原项目版权声明保留如下：

Copyright (c) 2018-2026 macrozheng

本仓库的学习修改与新增文档由HongYu Liu维护。

Copyright (c) 2026 HongYu Liu，仅限本人贡献部分。

本仓库沿用 [Apache License 2.0](LICENSE)。保留原有许可证、版权和相关声明；原项目名称及标识的权利归相应权利人所有。

本 README 已由 lhy114 于 2026-11-04 基于本仓库学习用途重写。原项目的完整介绍、演示与教程请访问[上游仓库](https://github.com/macrozheng/mall)。

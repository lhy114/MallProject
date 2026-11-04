# 01：将 mallProject 迁移到自己的 GitHub

## 目标和当前进度

将本地已有项目及学习修改推送到自己的 GitHub，保留原作者提交历史、LICENSE 与版权说明。

2026-11-04 检查结果：
- 项目目录：`/Users/liuhongyu/IdeaProjects/mallProject/`。
- 当前分支：`feature/mybatis-plus-pilot`。
- 原远程 origin：`https://github.com/macrozheng/mall.git`。
- 最近提交：`9b311ef`，将注册的 mybatis generator 的 register 功能升级为 mybatis plus。
- 存在已暂存、未暂存和未跟踪文件；不能直接丢弃或覆盖。
- 本机没有 `gh` 命令，采用 GitHub 网页创建仓库。
- 用户已创建空仓库 `https://github.com/lhy114/MallProject.git`；已将原 origin 改名为 upstream，将自己的仓库设为 origin。

## 一、先理解几个概念

- 工作区：当前磁盘上的文件。
- 暂存区：准备进入下一次提交的文件内容。`git add` 后再编辑文件，会同时存在已暂存和未暂存的版本。
- 本地仓库：`.git` 中保存的提交历史；`git commit` 只在本地创建版本。
- 远程仓库：GitHub 上的仓库；`git push` 才将提交传到远程。
- origin、upstream：远程地址的本地别名。通常 origin 指自己的仓库，upstream 指原作者仓库；别名本身不授予权限。
- 分支：指向提交的可移动引用。更换 origin 不会自动更名当前分支，也不会修改源码。

## 二、协同迁移步骤

### 1. 检查本地状态（已完成）

```bash
cd /Users/liuhongyu/IdeaProjects/mallProject
git remote -v
git branch --show-current
git status --short
git log -5 --oneline
git diff --stat
git diff --cached --stat
```

`M` 表示修改，`D` 表示删除，`??` 表示未跟踪，`AM` 表示新增文件已暂存后又发生修改。短状态的第一列代表暂存区，第二列代表工作区。

### 2. 用户在 GitHub 创建空仓库（待完成）

登录自己的 GitHub，选择 New repository，填写名称（例如 mallProject），选择公开或私有。
不要初始化 README、.gitignore 或 License，以免产生与本地不相连的初始提交。
将新仓库 HTTPS 地址发给 Codex。不要发送密码或访问令牌。

本次是在自己的账号创建独立仓库。也可以选择 GitHub Fork 保留平台上的派生关系，但不要同时混用两套初始化步骤。

### 3. 调整远程地址（待完成）

以下 YOUR_USERNAME 是占位符，执行前替换成实际账号；目前尚未执行：

```bash
git remote rename origin upstream
git remote add origin https://github.com/YOUR_USERNAME/mallProject.git
git remote -v
```

预期：origin 指向自己的仓库，upstream 指向 macrozheng/mall。以后显式写 `git push origin ...`，避免误选目标。
`git remote rename` 也可能使原本的分支跟踪配置继续指向 upstream；首次向自己的仓库推送时用 `-u` 建立新的跟踪关系。

### 4. 整理并提交当前成果（待完成）

先检查差异与未跟踪文件，确认配置中没有个人密钥、真实账号令牌和不该公开的数据；保留 LICENSE。
历史提交也会随分支上传，仅检查当前工作区并不能排除历史中曾出现的敏感信息。
原项目已存在部分暂存内容，提交前必须同时审阅 `git diff` 与 `git diff --cached`。
本次文档整理中删除的旧参考文档属于预期改动。

以下是明确选择文件的命令示例，并非要求一次提交所有业务改动：

```bash
git diff
git diff --cached
git add AGENTS.md learning/ .gitignore
git diff --cached --stat
git diff --cached
git commit -m "docs: add project collaboration and Git learning guide"
```

注意：`git commit` 会包含暂存区中的所有内容，包括此前已暂存的业务改动。实际提交前应整理暂存区，按业务改动和文档分批提交；不要盲目复制上面的提交命令。
未提交的修改不会被 push 上传。迁移整个当前项目时，需确认所有应保留的修改和新文件都进入提交。

### 5. 推送自己的仓库（待完成）

保留现有分支名称的方式：

```bash
git push -u origin feature/mybatis-plus-pilot
```

`-u` 建立跟踪关系，之后在此分支可使用 `git push`。
是否将此分支设为 GitHub 默认分支，或另外建立 main，在了解实际远程状态后决定，无需为迁移强行更名。
HTTPS 认证如需要登录，在本机凭据工具或浏览器完成；GitHub 账号密码不能直接作为 Git HTTPS 密码，不要将令牌写入远程 URL、文档或聊天。

### 6. 验证迁移（待完成）

```bash
git remote -v
git branch -vv
git status
git rev-parse HEAD
git ls-remote origin refs/heads/feature/mybatis-plus-pilot
```

本地 HEAD 与远程对应分支提交 SHA 应一致。在自己的 GitHub 网页核对文件、学习文档、许可证与默认分支。
只推送当前分支不会自动上传其他本地分支和标签；按需选择，不使用 `--mirror` 或强制推送。

## 三、日常 Git 命令速查

|命令|用途|
|---|---|
|`git status`|查看当前分支、暂存区和工作区状态|
|`git diff`|查看尚未暂存的修改|
|`git diff --cached`|查看将进入下一次提交的内容|
|`git diff --stat`|查看改动文件与行数摘要|
|`git add 文件路径`|暂存指定文件的当前内容|
|`git restore --staged 文件路径`|取消暂存，保留工作区内容；操作前了解已有暂存版本|
|`git commit -m "说明"`|将暂存区保存为本地提交|
|`git log --oneline --graph -10`|查看最近提交关系|
|`git show 提交号`|查看某次提交的内容|
|`git switch -c codex/功能名`|从当前提交创建并切换分支；已有未提交修改可能随工作区保留|
|`git switch 分支名`|切换已有分支；先检查未提交改动|
|`git remote -v`|查看远程地址|
|`git fetch origin`|获取远程提交和引用，不自动合并工作区|
|`git branch -vv`|查看本地分支及跟踪关系|
|`git push -u origin 分支名`|首次推送并建立跟踪关系|
|`git push`|向已配置的目标推送提交|
|`git pull --ff-only`|获取并仅允许快进更新，历史分叉时停止|

## 四、常见误区

- `git add` 不会上传，`git commit` 也不会上传；上传需要 `git push`。
- 更改 origin 不需要删除 `.git` 或重新执行 `git init`，否则可能损失现有历史和配置。
- `.gitignore` 不会自动停止跟踪已经提交过的文件。
- 不随意使用 `git reset --hard`、`git clean -fd`、`git push --force`，这些可能丢失工作或覆盖远程历史。
- push 被拒绝时先检查仓库地址、认证、权限和远程历史，不用强推掩盖问题。
- 上游更新采用先 `git fetch upstream`、检查差异、再决定合并的流程，避免直接覆盖自己的学习改动。

## 五、学习验收

1. 能解释工作区、暂存区、本地提交和远程仓库的区别。
2. 能说明 origin 与 upstream 分别指向哪里。
3. 能用 `git diff --cached` 说清本次提交的文件内容。
4. 能验证本地提交与自己 GitHub 上的分支一致。
5. 能解释为什么未提交文件不会随 push 上传。

## 六、本次实际操作记录

- 新仓库：`https://github.com/lhy114/MallProject.git`，首次查询无远程引用，为空仓库。
- 已执行 `git remote rename origin upstream` 和 `git remote add origin https://github.com/lhy114/MallProject.git`。
- 保留当前 `feature/mybatis-plus-pilot` 分支和原作者历史，不强制更名或重写历史。
- 已检查当前文件中明显的令牌及私钥特征，未命中；这不是对完整历史的敏感信息审计。
- 将已有业务修改保存为一次学习成果快照，再单独提交文档清理与协作规范。快照提交不代表业务功能已测试通过。
- 本次仅迁移 Git 项目，不运行 Java 功能测试；Docker 部署目录在项目外，不包含在此次推送中。
- 下一步：推送当前分支并核对远程 SHA；以末尾验证记录为准。

### 首次推送结果：等待本机认证

2026-11-04 已创建本地提交：
- `005d797`：保存现有业务学习成果快照。
- `71386c6`：整理文档与 GitHub 迁移指南。

首次执行 `git push -u origin feature/mybatis-plus-pilot` 失败：
`fatal: could not read Username for 'https://github.com': Device not configured`。
这表示当前终端没有可用的 HTTPS 凭据，不是代码冲突。远程上传尚未完成。

推荐用户在 IDEA 中使用 Git → Push，确认目标是自己的 origin 与当前分支；若要求认证，通过 IDEA 的 GitHub 登录流程在本机完成。不要把令牌发到聊天中。
若 IDEA 需要添加账号，在 Settings → Version Control → GitHub 中添加自己的账号，然后重试 Push。界面名称可能随 IDEA 版本不同。
推送完成后告知 Codex，由 Codex 重新检查远程 SHA 和分支跟踪关系，并补记最终结果。

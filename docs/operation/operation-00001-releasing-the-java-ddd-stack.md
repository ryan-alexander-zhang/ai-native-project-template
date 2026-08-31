---
id: operation-00001-releasing-the-java-ddd-stack
type: operation
status: active
---

# 发布 Java DDD 栈

`lang/java/ddd` 分支对外发布两个构件，都进 GitHub Packages：

| 构件 | 坐标 | 由谁发 |
| --- | --- | --- |
| building-block 库 | `com.aipersimmon.ddd:*` | `.github/workflows/publish-library.yml` |
| 脚手架 archetype | `com.ryan.persimmon:persimmon-scaffold-archetype` | `.github/workflows/publish-archetype.yml` |

两者**同号发布**：一个 tag 同时触发两个 workflow。

## 触发方式：推 tag

两个 workflow 都是 `on: push: tags: ['lang/java/ddd/v*']`。

**不是** `release: published`，**也不是** `workflow_dispatch`——这两种事件 GitHub 只从**默认分支**读取
workflow 文件，而 `main` 是语言无关的模板骨架，根本没有 `aipersimmon-ddd/`。放过去的 workflow 无事可做。
`push` 事件（含 tag）读的是**被推送那个 commit 树里**的 workflow 文件，所以它们能待在代码所在的分支上。

### tag 命名：`lang/java/ddd/vX.Y.Z`

tag 名镜像分支名，加 `/vX.Y.Z`。前缀不是装饰：**tag 是仓库全局的，分支不是**。`refs/tags/v0.1.0`
在一整个仓库里只能花一次，一个裸的 `v0.1.0` 会让第一个发布的技术栈占掉这个号，将来 `lang/go/*` 上的
脚手架被迫和它共用一条版本序列。

打错 tag 名的后果是**静默的**：tag 建出来了，Actions 页面什么都不会发生。发完务必确认 workflow 真的跑了。

## 前置条件

1. **CI 是绿的。** 两个 publish workflow 不看 CI 脸色，且都带 `-DskipTests`——CI 红着发出去的东西，
   没有任何一次绿色构建背书过。
2. **工作区干净。** `git status` 应为空。
3. 你有推送权限。deploy 用的是 workflow 里的 `GITHUB_TOKEN`，不需要你配任何密钥。

## 步骤

用 `scripts/release.sh`：

```bash
scripts/release.sh 0.1.0 0.2.0-SNAPSHOT     # 改版本、提交、打 tag、再开发版。不推送
PUSH=1 scripts/release.sh 0.1.0 0.2.0-SNAPSHOT   # ...并推送，这一步才会发布
```

不推送时它会打印怎么 review、以及怎么把它做的一切撤销（tag 和两个 commit 全在本地）。

脚本存在的理由主要是 **tag 名**：整条发布链挂在它匹配 `lang/java/ddd/v*` 上，而打错名字的 tag
会正常建出来、静默不触发任何 workflow。脚本从版本号拼出来，敲不错。

它另外拦掉这些：不在 `lang/java/ddd` 分支、工作区不干净、拿 `-SNAPSHOT` 当发布版、下个开发版忘了带
`-SNAPSHOT`、tag 已存在（本地或远端）。这些检查全部在任何改动发生**之前**退出。

下面是它逐步做的事，手动执行也是这一套。

### 1. 把库的版本改成发布版

```bash
cd aipersimmon-ddd
mvn versions:set -DnewVersion=0.1.0
```

改的是这个 reactor 的 44 个 pom（parent 的 `<version>`、BOM 的 `<version>`、42 个子模块的
`<parent><version>`）。每个 pom 旁边留一份 `pom.xml.versionsBackup`，已被 `.gitignore` 挡掉。

**它只改文件，不碰 git。** commit、tag、push 全部由你手动执行——见下文「为什么不用 maven-release-plugin」。

反悔：`mvn versions:revert`（用备份还原）。满意后清理备份：`mvn versions:commit`。

### 2. 检查、提交、打 tag、推送

```bash
cd ..
git diff --stat                      # 应该正好 44 个 pom
git commit -am "release 0.1.0"
git tag lang/java/ddd/v0.1.0         # ← 名字必须完全匹配，包括那个 v
git push --follow-tags
```

`--follow-tags` 会把 commit 和 tag 一起推。tag 抵达远端的那一刻，两个 publish workflow 开始跑。

### 3. 盯着它跑完

```bash
gh run list --limit 5
```

应该看到 `Publish library` 和 `Publish archetype` 两条。**看不到就是 tag 名不对**——检查
`git tag -l 'lang/java/ddd/*'`，删掉错的重打。

### 4. 回到开发版本

```bash
cd aipersimmon-ddd
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
cd .. && git commit -am "back to development" && git push
```

这一步不能省。分支停在发布版本号上，下一次提交产出的就还是 `0.1.0`。

## scaffold 的两个版本引用不用你管

版本号在库 reactor 之外还活着两处：

```
aipersimmon-ddd-scaffold/multi-module/pom.xml         <aipersimmon-ddd.version>
aipersimmon-ddd-scaffold/multi-module/archetype.properties   archetype.version
```

`versions:set` 管不到它们（不同 reactor + 不是 pom）。**但发布时不需要你手动改**：
`publish-archetype.yml` 里有一步 `Stamp the released version into the scaffold`，从 tag 反推版本号，
在 CI 的 workspace 里改掉这两个文件，不提交。

之所以必须在 CI 里改而不是靠 `-D` 覆盖：`archetype:create-from-project` 是把 scaffold 的 pom
**原文**拷进 `archetype-resources/` 当模板的，消费方生成出来的项目直接继承那个文件里的版本号。
`-D` 是覆盖有效模型，覆盖不了被拷贝的文本。（另外实测 `-Darchetype.version` 根本盖不过
`archetype.properties`。）

仓库日常保持指向 `-SNAPSHOT`，这正是在同一棵树里开发 scaffold 所需要的。

## 为什么不用 maven-release-plugin

配过，又撤掉了。它把上面第 1、2、4 步合成一条命令，代价是：

- **它自己 commit、tag 并 push。** 一个构建插件自动往你的分支推两个 commit，出问题时
  `release:rollback` 只能撤本地的，推出去的撤不回。
- **搬进 CI 也不解决问题，反而更糟。** 用 `GITHUB_TOKEN` 推出去的 tag **不会触发其它 workflow**
  （GitHub 防递归的既定行为），于是两个 publish workflow 静默不跑。绕过要引入 PAT 或 GitHub App
  token——为了少敲两行 git，换来一个能改仓库的长期凭据。
- 它的**唯一**实质优势是 tag 名由 `tagNameFormat` 生成、不会敲错。这一点靠本文档写死命令来挡。

它留下的痕迹只有 `aipersimmon-ddd/pom.xml` 里那段说明「为什么这里是 versions-maven-plugin 而不是它」
的注释。

## 版本策略

当前是 `0.1.0-SNAPSHOT`。**发布前必须先执行第 1 步**——直接给 `0.1.0-SNAPSHOT` 打
`lang/java/ddd/v0.1.0` 的 tag，会发出去一个版本号和 tag 对不上、且能被后续构建覆盖的构件。

`versions-maven-plugin` 在 `aipersimmon-ddd/pom.xml` 的 `<pluginManagement>` 里钉了版本，
两年后的一次发布会和今天用同样的方式改写 poms。

## 排查

| 症状 | 原因 |
| --- | --- |
| 推了 tag，Actions 页面无反应 | tag 名不匹配 `lang/java/ddd/v*`。`git tag -l` 核对 |
| `Derive the archetype` 失败在 BOM 解析 | 库没能以该版本装进 runner 的 `~/.m2`，看上一步的日志 |
| 构建挂在 `aipersimmon-ddd-parent` 的 PMD，报找不到 `quality-config` | 缺自举步骤。三个 workflow 各有一步 `Install the shared quality config`，且必须排在任何 `install` / `deploy` 之前 |
| 消费方 generate 出来的项目引用 `-SNAPSHOT` | stamp 那一步没生效，看 `Stamp the released version into the scaffold` 的日志 |

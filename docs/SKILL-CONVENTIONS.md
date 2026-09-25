# 写站点技能（skill）的约定

## 技能放在哪

技能文档都放在仓库的 **`.agents/skills/`** 下 —— 这是 DSH 的**项目级技能根**（DSH 按
`<项目根>/.dsh/skills` → `<项目根>/.agents/skills` → `customSkillDirs` → `~/.dsh/skills` →
`~/.agents/skills` 的顺序扫描），而仓库根有 `.git`，所以**在仓库里启动 dsh 会话就能直接发现这些技能**，
不用再往 `.dsh/skills` 复制一份。发现规则只有两条：

- 只认 `<根>/<技能名>/SKILL.md`（或 `<根>/<技能名>.md`）这一层，**不递归**；
- frontmatter 必须有 `name` 与 `description`，且 `name` 是 kebab-case 且与目录名一致。

换了装法（`git clone` 到别处用、装到用户级目录）不影响构建：`SkillDocConsistencyTest` 依次在
`.agents/skills/`、`.dsh/skills/`、老的 `skills/` 与仓库根找主技能文档，能找到哪个就用哪个。

`.agents/skills/` 下有两类文档：

| 文档 | 作用 | 谁维护 |
| --- | --- | --- |
| `.agents/skills/deepseek-browser-use/SKILL.md` | **主技能**：服务端能力的唯一权威清单（命令表覆盖、端点、frontmatter、协议） | 跟着服务端一起改 |
| `.agents/skills/<站点名>/SKILL.md` | **站点操作手册**：某个站点上「必须这么点」的经验（例如 `wecom-mail-domain`） | 跑完一次真实任务后补 |

构建时会跑 `SkillDocConsistencyTest`（`playwright-server/src/test/java/nexus/io/ai/browser/docs/`）校验两者与服务端的一致性。

### 主技能是「一个入口 + 若干分册」

主技能文档装不下全部细节时，拆成 `SKILL.md` 入口加同目录 `references/*.md` 分册 —— 入口每次触发技能都会整体进上下文，所以**只放必读内容**，细节按需再读：

| 文件 | 放什么 |
| --- | --- |
| `SKILL.md` | 省 token 铁律、症状 → 命令索引表、读页面、全部命令的一行速查、交互循环、最常踩的坑 |
| `references/*.md` | 按主题分册（协议、命令全表、批量与 JS、人机协同、读页面、浏览器、坑、客户端），用 read 工具按需读 |

两条维护要求：

1. **只查 `SKILL.md` 的那几条测试，决定了新方法必须写进 `SKILL.md`**：`everyCommandIsDocumented` 只读主技能，新加一个方法时名字要出现在 `SKILL.md` 里（加在「命令速查」那一节最省事），光写进分册不算数。
2. **分册里的命令名不会被检查**（`allSkillDocs()` 只找名为 `SKILL.md` 的文件），所以分册写错命令名不会有人报错 —— 要自己照着 `CommandTable` 核对。

## 最重要的一条：单反引号 vs 双反引号

**测试把单反引号包起来的东西当作命令名检查**：

```
这个方法不存在 → `no_such_command`     ← 会被检查：命令表里没有它 → 测试失败
这是页面里的名字 → ``no_such_command``  ← 不会被检查
```

为什么要这样分：站点操作手册**必然**会大量提到 snake_case 的**非命令标识符**，而检查只能靠词形猜。没有这条约定时，每写一个站点 skill 都要把二十多处标识符改成「加点号 / 加 `#` / 加对象前缀」的写法——**让文档迁就测试**，而且这条规则没有任何地方写明，下一个人还会踩。

| 类别 | 例子 | 写法 |
| --- | --- | --- |
| Vue / 表单字段 | ``subject_name``、``socialcredit_code``、``business_license_stuff`` | 双反引号 |
| CSS 类名 | ``mall_invoice_dialog_container``、``qui_dialog``、``form_err`` | 双反引号（也可以写成 `.xxx` 的点号形式） |
| HTML id | ``dsh_up_0`` | 双反引号（也可以写成 `#dsh_up_0`） |
| URL 查询参数 | ``order_id`` | 双反引号 |
| 接口响应字段 | ``has_cname_record`` | 双反引号 |
| **「这些方法不存在」的反例** | ``get_frames()``、``switch_frame()`` | 双反引号 |
| **真要调的命令** | `click_element_by_index`、`get_browser_state` | 单反引号 |

> 反例那一类最容易被误判：文档里明确写「这个方法不存在」时，测试反而会因为「它不在命令表里」而报错，等于**把「记录一个已知缺失的能力」判成了错误**。用双反引号就对了。

一份文档里要声明很多个时，也可以用 frontmatter 一次列出来（测试会把它并入白名单）：

```yaml
---
name: some-site
description: 某个站点的操作手册，讲清楚必须怎么点、哪些坑绕不开
nonCommands: [subject_name, dsh_up_0, has_cname_record]
---
```

两种写法等效，**推荐双反引号**：零配置、词法可判、不需要维护列表，视觉上也分得清「这是命令」与「这是页面里的名字」。

## 其余约定

1. **命令名必须真实存在**：`.agents/skills/` 下每一份 SKILL.md 里的单反引号 snake_case 名字都会对着命令表查一遍。写错一个（例如把 `get_tabs` 写成 ``list_tabs``），模型照着发请求就会拿到「不支持的方法」。不知道有哪些命令时先 `list_methods`。
2. **`recipes/*.json` 里的命令名同样会被检查**：配方写错命令名要到 `run_recipe` 运行时才报错，所以构建阶段就过一遍。
3. **主技能必须覆盖全部命令**：命令表里的每个方法都要在 `.agents/skills/deepseek-browser-use/SKILL.md` 里出现（`everyCommandIsDocumented`）。
4. **不写本机绝对路径**：`E:\` / `D:\` 这类路径会让文档换台机器就失效。
5. **不写旧名字**：``get_dom_text``、`/api/v1/playwright`、``PlaywrightController``、``com.litongjava``、``project-nexus`` 都已被拉黑。
6. **症状导向**：写新坑的时候，尽量在主技能开头的「症状 → 命令」索引表里也加一行——那张表就是为了把「翻 1000 行找答案」变成「查一行表」。

## 站点 skill 建议的结构

按「能不能一次跑通」组织，而不是按命令顺序罗列：

1. **站点特征**：URL、技术栈（Vue 2 / ant-design / 跨域 iframe…）、登录态在哪份 profile 里；
2. **必须这么做的步骤**：每一步给出可直接复制的请求体；
3. **坑**：这个站点特有的假成功（点了返回 `ok` 但页面不动）、必须直调的组件方法、一次性 token 之类的；
4. **怎么确认这一步真的成了**：`data.changed` / `get_form_state` / `diff_dom_text` / 某个必然出现的文本。

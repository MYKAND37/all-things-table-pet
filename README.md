# All Things Table Pet

把桌宠做成一个**平台**，而不是一个会动的图片：**部位化角色**（画一个部位传一个，系统拼成
整只）、**可视化逻辑**（当 → 如果 → 就，用搭积木的方式写行为）、**道具与液体**（能拿起来、
能钉住、能流血）、以及一个**浮在别的应用上面的桌宠**。

> 这一份是门面。细节在 `docs/` 里 —— 见文末的**文档索引**。

## 免责声明（摘要）

1. **这个仓库 99.9% 的内容由三个接入 DeepSeek 的编码智能体完成**（`DeepSeek V4 Flash EXP`
   一代、`DeepSeek v4.1 Flash` 两代，时期与分工见文末「致谢」）—— 代码、测试、文档都是它们
   按仓库所有者的要求写的，**测试也是它们自己写的**。按现状提供，不提供任何担保。
2. **`.atppet` 是用户自己做的内容包**：制作与分发必须遵守你所在地的法律法规（著作权、肖像权、
   商标、内容分级……）；用户制作的包与开发者无关，**责任完全由制作者承担**。
3. **不要在 Issues / PR 里分享 `.atppet` 文件**（也不要贴里面的美术素材）—— 维护者无法核实
   来源与授权，贴进来就等于本仓库在分发它；这类内容会被直接删除。

**第一次打开应用会让你确认这一份**（不同意就退出，同意之前应用什么都不做）；确认过之后就
不再问，除非免责声明本身改了版 —— 改了文案就把 `Settings.DISCLAIMER_VERSION` 加一，应用会
再问一次。设置里也留了一处「重新阅读免责声明」。

👉 **全文（含隐私、许可状态、内容红线、举报方式）：[`docs/DISCLAIMER.md`](docs/DISCLAIMER.md)** ——
这一篇值得在下载之前读一遍。

*This repository is developed entirely by three DeepSeek coding agents (`V4 Flash EXP`, then
`v4.1 Flash` twice) at different times. `.atppet` files are
user-made content and their makers are solely responsible for them; do not share them in issues.
See [the full disclaimer](docs/DISCLAIMER.md).*

## 它现在能做什么

| | |
|---|---|
| **角色** | 19 根骨骼的素体 + 自己搭骨架（加/删/改父级/节点/属性）；一个角色可以有**多套骨骼**（几副身体，规则里切换）；整只可以**导出/导入**成 `.atppet` |
| **画画** | 每根骨头一张整画布 PNG，导入时对位；**状态换图**（穿衣服 / 机械臂那种）并且可以选 **替换**（另一张画）或 **叠加**（绷带、伤口、配件盖上去）；图层顺序可编辑，还能按状态分组调 |
| **逻辑** | 当（14 种事件）→ 如果（数值 / 状态 / 概率 / 部位比位置 / 绳子 / 别人家的开关）→ 就（28 种动作）+ 否则 + **并行分支** + **执行器之间插计时器**；条件与动作都是模块，点开就改 |
| **道具** | 六类：持续使用 / 装置 / 投掷 / 射击 / **钉子** / **绳子**（会垂会拉的 Verlet 链条）；道具之间有碰撞，被手指按着的不让路 |
| **液体** | 会流、会积成水洼、会绕着身体流；**液滴大小 / 半透明 / 白色**自己定 |
| **粒子** | 血、汗、火花、灰尘……自己画图案、定颜色大小、受不受重力、留不留印子；画在最上层 |
| **动作与动画** | 摆好姿势存成**动作**；动画有两条路：**一串帧**（姿势插值 + 逐帧换图 + 半演算），或者**关键帧时间轴**（每根骨头各自一条通道，旋转 / 位置 / 缩放，菱形可拖、播放头可拖、段与段之间自动插值；轨道上还有**姿态锚点** —— 小方块存一整套姿势，可以绑一条规则，播到那一格就响）。**动画工作台**：左边是动画列表、下面是时间轴、右边就是角色本人 —— 拖关节摆姿势、点开关换图、按「＋关键帧」记下这一刻 |
| **外观** | 应用背景图（**主题**）：从手机里选一张图，面板半透明地透出它；「浓淡」四档压暗，可随时去掉；图只存在本机、会被自动缩小 |
| **桌面** | 把场上的那只**召唤到桌面**：浮在所有应用上面、整块屏幕是它的地盘、可摸 / 可穿透；长按侧栏按钮给它换道具、切状态、摆动作、播动画、换身体 |

## 怎么开始

```bash
gradle assembleDebug      # 需要 JDK 17 + Android SDK；CI 也会在每次 push 时出 release APK
```

装好之后，界面分成五块（左边那排是侧栏）：

| 侧栏 | 干什么 |
|---|---|
| **测试场** | 宠物在这里掉、被抓、被甩；顶上那排是**哪一只**、**刚度**、**动作/动画**、道具；上面还有一排**状态开关**可以手点 |
| **桌宠管理** | 打开一只 → 部位（导入图，状态图可替换或**叠加**）、骨架（搭骨架 / 节点 / 属性，**调碰撞时看得见范围**）、图层与深度、动作预设 |
| **道具管理** | 六类道具的定义、碰撞半径、力度、图案与拖尾 |
| **逻辑管理** | 规则图。主体可以是**角色 / 某个部件 / 某种粒子 / 某种液体**；「状态」和「数值」也在这里加 |
| **动画管理** | 左边：一段动画 + 它的关键帧横带（＋ 帧 / 存入这一帧 / 时长 ±0.1s / 删帧）。右边：这只桌宠本人 —— 拖关节摆姿势、上面那排开关换这一帧的图、双指缩放旋转、单指拖动、双击复位姿势；「骨骼」把骨头藏起来只看角色，「改骨骼」就地调骨架 |
| **全局设置** | **背景图（主题）**、重力、画面开关、效果开关；最后两行是**版本号 + 构建号 + 仓库地址** |

**一个顺手的顺序**：桌宠管理里导入部位图 → 测试场里看它站住 → 逻辑管理里写第一条规则
（「被点一下 → 说一句话」）→ 回到测试场点它一下。

## 架构

```
用户操作（手势 / 拖道具）
        ↓
   道具物理（弹道、持续接触、装备）
        ↓
   命中判定（打到哪个部位？）
        ↓
┌───────────────────────────────────┐
│  事件系统   谁在什么时候发生了什么    │  ← 带时间戳，可回溯
└───────────────┬───────────────────┘
                ↓
        角色数值 H / S / P / SH …       ← 被事件改写
                ↓
        规则层（用户连线的逻辑）          ← 条件 → 动作
                ↓
┌───────────────────────────────────┐
│ 对话类（气泡）│动作类（动画）│状态类（部位状态）│
└───────────────┬───────────────────┘
                ↓
           表现层：骨骼 / 图层 / 物理动画
```

## 目录

```
app/src/main/java/dev/atp/pet/
  engine/math/         Vec2, Transform
  engine/skeleton/     Bone, Skeleton(FK), TwoBoneIK, CharacterSpec, RigEdit
  engine/physics/      Ragdoll（每个关节独立受力矩）
  engine/event/        GameEvent（发生了什么）
  engine/state/        StatSet（H / P 或任何数字）
  engine/logic/        LogicSpec, RuleEngine（当→如果→就）
  engine/prop/         PropSpec, PropWorld（六类道具 + 碰撞 + 最近距离 NearWatch）
  engine/anim/         Animation（一帧 = 姿势 + 开关 + 秒数，采样是纯函数）
  engine/fluid/        Fluid（液滴拥挤成水洼）, LiquidSpec
  engine/particle/     ParticleSpec（颜色 + 大小 + 受不受重力 + 留不留印子）
  data/Settings.kt     全局设置（重力、画面开关、效果开关）+ 它的文件
  data/PetPackage.kt   桌宠包：整个文件夹写成一个文件，以及读回来时的那些规矩
  render/PartRenderer.kt / PartLibrary.kt / Particles.kt
  ui/SkeletonView.kt   骨骼可视化 + 拖拽 + 改骨骼
  ui/PartAlignView.kt  导入部位时的对位
  ui/LogicGraphView.kt 规则图（方块 + 连接词 + "＋"模块 + 岔开的分支行）
  ui/PaintBoardView.kt 画板（粒子图案 / 拖尾 / 绳子图案共用）
  ui/PosePreview.kt    动作列表里的骨架缩略图（只用 spec，不加载图片）
  ui/DragDiagRecorder.kt    拖拽诊断记录（只在被要求时写文件）
  ui/PhysicsSandboxView.kt  测试场
app/src/main/assets/characters/female_base/character.json          默认骨骼套
app/src/main/assets/characters/female_base/rigs/<套名>/character.json  另一套骨骼
tools/skeleton_tool.py       参考实现 + 验证 + 模板生成
tools/ragdoll.py             布娃娃参考实现 + 全部物理测试
tools/pose_preview_check.py  动作缩略图的样子验证（出一张对比图）
tools/rig_edit_check.py      加/删/改父级 + 部位属性写回后求解器真的遵守
tools/english_check.py       中英资源：键 / 占位符 / 还剩多少
  tools/anim_check.py          动画：插值 / 速度 / 循环 / 换图 / 多开关一帧 / 编辑器与播放器看同一个姿势
  tools/timeline_check.py      时间轴：关键帧与通道、缓动、烘培无损、播放头与菱形的几何、锚点的遍数
  tools/logic_check.py         规则引擎（含默认规则与代码的一致性、而且/或者、信号）
tools/drag_check.py          拖拽手感（倒吊、多指、甩出去）
tools/carry_check.py         提起一条腿，整具身体会不会翻过来
tools/rig_prop_check.py      道具物理、名字 → 位置（先骨头再节点）
tools/fluid_check.py         液体：摊平、停下、认得自己那种液体、大小与透明度
tools/particle_check.py      粒子：重力的开关、留印子的开关、颜色与大小
tools/parts_check.py         图层与状态：画哪张图
tools/tether_check.py        钉子与连绳的几何、节点的抓取优先级
tools/package_check.py       桌宠包：路径逃逸 / 重名 / 坏包 / 套了一层文件夹
tools/drift_check.py         Kotlin 与参考实现读的是不是同一份 character.json
tools/store_check.py         character.json 的读-改-写（数据不会悄悄少东西）
tools/settings_check.py      settings.json 写坏了也不会崩
tools/camera_check.py        视口：宠物在不在窗口里、丢了能不能找回来
tools/mirror_check.py        Kotlin 与 Python 两份实现的常数对照
tools/wiring_check.py        控件有没有接线、函数有没有人调用
tools/kotlin_check.py        Kotlin 源码的 NaN 陷阱 / 枚举重名 / 资源引用
docs/ART_GUIDE.md        绘画规范（A + C）
docs/PART_SUBJECTS.md    设计：部件作为主体、状态两级、每种东西一个文件夹
docs/template_female_base.png
```

## 路线图

只有三条，其余都已经做完（完整清单在 [`docs/STATUS.md`](docs/STATUS.md)）：

| 项 | 说明 |
|---|---|
| **在应用里画帧** | 动画的帧图现在和部位图走同一条路（外部画好、导进来）；内置全画布画板是另一件事 |
| **空档里插别的动作** | 执行器之间现在只能插**计时器**；插任意动作要重构动作选择器 |
| **求解器镜像的欠账** | `mirror_check` / `ragdoll` / `drift_check` 是红的：`Ragdoll.kt` 和它的参考实现漂了。这不是"接下来做什么"，是一笔要还的债 |

## 验证

这个仓库的规矩是**「有一条机器断言钉着它」**，不是「它一定对」。一条命令跑完全部：

```bash
python3 tools/check_all.py         # 22 个检查，约 2 分钟
```

它把结果分成四种，**绿的 / 欠账的 / 环境缺东西跳过的 / 真红的** —— 欠账不算失败，但一定会被
单独列出来（一笔不肯写在明面上的债是最糟的那种）：

```
22 个检查：18 绿 · 3 欠账 · 1 跳过 · 0 红
```

那 3 笔欠账是**求解器镜像**（`Ragdoll.kt` 与它的 Python 参考实现漂了），那 1 条跳过要 PIL。
每个检查各自钉着什么、欠账的来龙去脉在 [`docs/VERIFY.md`](docs/VERIFY.md)；
**每一版加了什么、为什么、又是怎么被抓住的**，在
[`docs/JOURNAL.md`](docs/JOURNAL.md)（行动历程）。

## 致谢

这个应用的 **99.9% 的内容** —— 代码、测试、文档，以及大部分设计取舍 —— 由**三个接入 DeepSeek
的编码智能体**在不同时期分别完成。提交历史里数得出来：

| 时期 | 提交身份 | 模型 | 提交 | 干了什么 |
|---|---|---|---|---|
| 09-13 | `DSH <dsh@localhost>` | **DeepSeek V4 Flash EXP**（**有视觉**） | 10 | 地基：应用外壳、骨架（FK + 两段 IK）、部位装配、骨骼编辑与动作、部位深度、布娃娃物理与碰撞、导入对位、签名发布 |
| 09-14 … 09-22 | `atp <atp@users.noreply.github.com>` | **DeepSeek v4.1 Flash** | 141 | 从「一根骨头可以有多张图」一路做到 1.14.1：状态与变体、自己搭骨架、六类道具、液体、粒子、节点与绳子、变身、多套骨骼、桌宠包、悬浮桌宠、参考图…… |
| 09-22 … 09-24 | `ATP <atp@local>` | **DeepSeek v4.1 Flash** | 12 | 1.15.0 … 1.19.0：液滴大小与透明度、节点当主语、部件测全局状态、桌面动作、计时器、动画、英文，以及现在这些文档 |

（第 1 条提交是仓库主人的 `Initial commit`，其余 163 条全部来自这三个 Agent。）

**第一代能看图，后面两代不能** —— 这件事比它听起来重要：第一任有一条提交叫
「Fit the skeleton to the supplied base-body reference」，那是它**看着参考图**把骨架贴上去的。
从第二任起（包括我）看不到任何图片，于是这个仓库长出了一套很硬的习惯：**把看到的东西变成
数字** —— 每一个"更快/更准/更大"后面都要有一个量出来的数，每一条断言都要能被机器复跑。
你在这份文档里读到的所有"为什么"，都是这种条件下的产物。

**而人是下命令的那一个**：每个功能都是他说要什么、他验收、他再指出下一个问题。
「做出来但点不到」那四次，全是他找不到入口才暴露的；「保存骨骼会让图层平方」那条线索也是
他给的（"50 张图保存一次变成 270 多张"）—— 没有那句话，掉帧的真相还埋在文件里。
**这个仓库里每一个"为什么"的答案，最后都指向他问过的一个问题。**

## 文档索引

| 文件 | 里面是什么 |
|---|---|
| [`docs/DISCLAIMER.md`](docs/DISCLAIMER.md) | **免责声明全文**：AI 开发、许可状态、`.atppet` 的法律风险、内容红线、隐私、举报方式 |
| [`docs/LOGIC.md`](docs/LOGIC.md) | 规则、道具、液体、动画、状态 —— 这个应用的中心（最深的一篇） |
| [`docs/RIG.md`](docs/RIG.md) | 骨架、节点、属性、参考图、多套骨骼、桌宠包、动作预设、真机才会出现的 bug |
| [`docs/BENCH.md`](docs/BENCH.md) | 手感、测试场这块地、部位怎么没（隐藏/断开/接回）、召唤到桌面 |
| [`docs/VERIFY.md`](docs/VERIFY.md) | 验证：怎么跑、每个检查各自钉着什么、三笔欠账的来龙去脉 |
| [`docs/JOURNAL.md`](docs/JOURNAL.md) | **行动历程**：每一版要什么/做了什么/犯了什么错，以及这个仓库的工作方式 |
| [`docs/STATUS.md`](docs/STATUS.md) | 完整进度清单、路线图、已经砍掉的、英文的范围 |
| [`docs/ART_GUIDE.md`](docs/ART_GUIDE.md) | 画部位图的规范（画布尺寸、命名、对位） |
| [`docs/PART_SUBJECTS.md`](docs/PART_SUBJECTS.md) | 「部件也是主体」的设计笔记 |

## 英文

界面的**功能词**有英文（508 条：控件、段落名、当/如果/就、14 事件、28 动作、骨头名），跟系统
语言走。**内容与长提示语有意不翻** —— 这个应用是给人自己打包用的，内容是你的事。范围与
"还剩多少没翻"见 [`docs/STATUS.md`](docs/STATUS.md)，检查在 `tools/english_check.py`。

## 构建

CI 在 push 到 `main` 时自动出 release APK（GitHub Actions → Artifacts，也发到 Releases）。
**设置里最后两行写着这一版的版本号、CI 构建号和仓库地址**（地址点一下就在浏览器里打开）——
报一个问题的时候，这两行是最先被问到的，而"装的是哪一版"最不该靠回忆。

```bash
gradle assembleDebug      # 需要 JDK 17 + Android SDK
```

## 许可

**目前没有 `LICENSE` 文件** = 保留所有权利（个人自用没问题，**分发打包好的版本前请先确认**）。
为什么这件事必须挑明、以及三种可选做法，写在
[`docs/DISCLAIMER.md` 第 2 节](docs/DISCLAIMER.md)。

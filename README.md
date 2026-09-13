# All Things Table Pet

把桌宠做成一个**平台**，而不是一个会动的图片。

## 这个项目想做什么

| 方向 | 用户能做什么 |
|---|---|
| **部件化角色** | 不画整只角色，而是**画一个部位传一个**（一只手臂、一条腿），系统拼装成角色 |
| **可视化逻辑** | 不写代码，用搭积木的方式定义「**触发事件 → 判断条件 → 做出反应**」 |
| **道具系统** | 自定义道具（持续使用 / 装置 / 投掷 / 射击），并让道具和角色产生互动 |

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

## 当前进度

- [x] 应用外壳：侧栏菜单 + 磨砂内容区
- [x] **通用骨骼系统**：正向运动学 + 两段式 IK（拖拽，保持肘部侧）
- [x] **6.5 头身女性骨架**：30 根骨骼，含 11 根弹簧骨骼（头发 / 裙子）
- [x] **绘画模板 + 命名规范**（A + C 方案）
- [x] **测试场**：手机上直接拖关节试 IK
- [ ] 图层与遮挡换序的执行部分
- [ ] 弹簧物理（参数已定义，求解器未写）
- [ ] 部件装配（读取 `parts/` 并挂到骨骼上）
- [ ] 道具四类
- [ ] 事件系统 + 数值系统
- [ ] 可视化逻辑编辑器

## 目录

```
app/src/main/java/dev/atp/pet/
  engine/math/         Vec2, Transform
  engine/skeleton/     Bone, Skeleton(FK), TwoBoneIK, CharacterSpec
  ui/SkeletonView.kt   骨骼可视化 + 拖拽
app/src/main/assets/characters/female_6_5/character.json
tools/skeleton_tool.py   参考实现 + 验证 + 模板生成
docs/ART_GUIDE.md        绘画规范（A + C）
docs/template_female_6_5.png
```

## 关于验证

这台机器**编译不了 APK**（没有 JDK / Android SDK），所以：

- **数学**由 `tools/skeleton_tool.py` 在本地验证——它是 Kotlin 引擎的参考实现，
  含往返测试（随机合法姿势 → 取末端 → IK 反解 → 比对）和真实拖拽测试。
- **编译**由 GitHub Actions 验证。

```bash
python3 tools/skeleton_tool.py \
  --spec app/src/main/assets/characters/female_6_5/character.json \
  --verify --out docs/template_female_6_5.png
```

改动骨骼数学时**务必先跑这个**——它抓到过两个真实的符号错误，那两个错误在静止姿势下
完全看不出来，只有拖拽才会暴露。

## 构建

CI 在 push 到 `main` 时自动出 debug APK（GitHub Actions → Artifacts）。

```bash
gradle assembleDebug      # 需要 JDK 17 + Android SDK
```

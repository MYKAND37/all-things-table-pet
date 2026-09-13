import json

BT = chr(96)
def code(s):
    return BT + s + BT
def fence(s):
    return BT * 3 + s

spec = json.load(open('app/src/main/assets/characters/female_base/character.json', encoding='utf-8'))
W = spec['canvas']['width']; H = spec['canvas']['height']
HH = spec['headHeight']

ZH = {
    'hip': '胯（根骨骼，整个角色的位置由它决定）',
    'spine': '腰', 'chest': '胸', 'neck': '脖子',
    'head': '头（转动轴在颈根）',
    'shoulder_L': '左肩 / 锁骨', 'shoulder_R': '右肩 / 锁骨',
    'upperarm_L': '左上臂', 'upperarm_R': '右上臂',
    'forearm_L': '左前臂', 'forearm_R': '右前臂',
    'hand_L': '左手', 'hand_R': '右手',
    'thigh_L': '左大腿', 'thigh_R': '右大腿',
    'shin_L': '左小腿', 'shin_R': '右小腿',
    'foot_L': '左脚', 'foot_R': '右脚',
    'hair_back_1': '后发·根段（弹簧）', 'hair_back_2': '后发·中段（弹簧）',
    'hair_back_3': '后发·末段（弹簧）',
    'hair_side_L_1': '左侧发·上段（弹簧）', 'hair_side_L_2': '左侧发·下段（弹簧）',
    'hair_side_R_1': '右侧发·上段（弹簧）', 'hair_side_R_2': '右侧发·下段（弹簧）',
    'skirt_front_L': '裙子前左片（弹簧）', 'skirt_front_R': '裙子前右片（弹簧）',
    'skirt_side_L': '裙子左侧片（弹簧）', 'skirt_side_R': '裙子右侧片（弹簧）',
}
CORE = ('hip', 'spine', 'chest', 'neck', 'head')

def blen(b):
    dx = b['tail'][0] - b['head'][0]
    dy = b['tail'][1] - b['head'][1]
    return (dx * dx + dy * dy) ** 0.5

out = []
w = out.append

w('# 角色绘画规范 · female_base')
w('')
w('这份文档就是 **A + C 方案**：模板对齐（A）加命名约定（C）。')
w('你按模板画，按名字导出，系统自动装配，**不需要任何手动对齐**。')
w('')
w('---')
w('')
w('## 一、画布')
w('')
w('| 项 | 值 |')
w('|---|---|')
w('| 画布尺寸 | **%d × %d**（宽 × 高） |' % (W, H))
w('| 头高 | %d px |' % HH)
w('| 身高 | %d px（%.2f 头身） |'
  % (spec['proportions'].get('totalHeightPx', round(HH * 6.12)),
     spec['proportions'].get('headsTall', 6.12)))
w('| 头顶 y | %d px |' % spec['headTopY'])
w('| 中线 x | %d px |' % spec['centreX'])
w('')
w('模板图：[' + 'template_female_base.png' + '](template_female_base.png)')
w('')
w('## 二、导出规则（三条，必须全遵守）')
w('')
w('1. **每个部位单独一层**，画在模板之上。')
w('2. **导出时必须是整张画布尺寸**（%d × %d），不要裁到内容边界。' % (W, H))
w('   位置信息就藏在「整张画布」里——裁了就全错位。')
w('3. **文件名 = 骨骼名**，例如画左上臂就存成 ' + code('upperarm_L.png') + '。')
w('')
w('> **缺文件不会报错。** 某个部位还没画，那根骨骼就不画东西，骨架照常工作。')
w('> 所以可以先画一部分、装上试效果，再慢慢补。')
w('')
w('> 为什么坚持「整张画布」：这是让**位置自动对齐**的唯一办法。系统按骨骼名把图挂上去，')
w('> 图本身已经在正确位置上，不需要任何标定步骤。导入时程序会自动裁掉透明边并记住偏移，')
w('> 所以存储上不会浪费。')
w('')
w('## 三、骨骼清单（文件名对照表）')
w('')
w('共 **%d** 根骨骼。坐标是关节位置，单位 px。' % len(spec['bones']))
w('')

def table(subset, title):
    w('### ' + title)
    w('')
    w('| 文件名 | 中文 | 关节位置 | 长度 |')
    w('|---|---|---|---:|')
    for b in spec['bones']:
        if subset(b):
            w('| ' + code(b['name'] + '.png') + ' | %s | (%g, %g) | %.0f |'
              % (ZH.get(b['name'], ''), b['head'][0], b['head'][1], blen(b)))
    w('')

table(lambda b: b['name'] in CORE, '主干')
table(lambda b: b['name'].startswith(('shoulder', 'upperarm', 'forearm', 'hand')), '手臂')
table(lambda b: b['name'].startswith(('thigh', 'shin', 'foot')), '腿')

w('### 头发 / 裙子（弹簧骨骼）')
w('')
w('这些由物理驱动——角色一动，它们自己会飘、会甩。')
w('**按静止状态画就行**，不用考虑飘起来的样子。')
w('')
table(lambda b: b.get('spring'), '头发 / 裙子（弹簧骨骼）')

w('## 四、比例参考')
w('')
w('模板上的横线就是下面这些。H 表示头高（%d px）。' % HH)
w('')
w('| 标记 | 位置 | y 坐标 |')
w('|---|---|---:|')
for lm in spec['proportions']['landmarks']:
    w('| %s | %.2f H | %d |' % (lm['key'], lm['head'], lm['y']))
w('')
w('| 宽度 | px |')
w('|---|---:|')
for k, v in spec['proportions']['widths'].items():
    w('| %s | %d |' % (k, v))
w('')
w('## 五、图层顺序')
w('')
w('从后往前（数字大的盖住小的）：')
w('')
w('| z | 部位 |')
w('|---:|---|')
for l in sorted(spec['layers'], key=lambda x: x['z']):
    w('| %d | ' % l['z'] + code(l['bone']) + ' |')
w('')
w('另外还有两条**动态换序**规则：手抬到胸口以上时，那条手臂会自动翻到头和头发的**后面**。')
w('这样「把手放到脑后」会自然被头挡住，不需要额外处理。')
w('')
w('## 六、画完之后放哪')
w('')
w(fence(''))
w('characters/female_base/')
w('  character.json      <- 已经有了，不用动')
w('  parts/')
w('    head.png')
w('    upperarm_L.png')
w('    forearm_L.png')
w('    ...')
w(fence(''))
w('')
w('放进 App 的 **测试场** 就能看到角色动起来——手指可以拖关节。')
w('')

open('docs/ART_GUIDE.md', 'w', encoding='utf-8').write('\n'.join(out))
print('ART_GUIDE.md written:', len(out), 'lines')

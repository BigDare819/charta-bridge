# Charta 1.21.1 Fabric — 本地部署 & 附属开发环境

> 目标：把 [lucaargolo/charta](https://github.com/lucaargolo/charta) 的 1.21.1 Fabric 版部署到本地，
> 并搭一个能编译、能进游戏、复用 Charta 动画/UI/材质的附属工程。
> 路径一律以 `D:\deepseekharness` 为根。

附属工程有两个：`charta-bridge`（定约桥牌，参考实现）、`charta-BigA`（打大A，当前在改）。
第 5/6 节的 API 和坑对任何 Charta 附属都适用，示例包名写的是 bridge。

---

## 1. 部署结果

| 内容 | 路径 |
| --- | --- |
| Charta 源码 | `charta\`（`main`，1.2.5，MC 1.21.1） |
| Fabric 构建产物 | `charta\fabric\build\libs\charta-fabric-1.21.1-1.2.5.jar` |
| 本地 Maven 仓库 | `C:\Users\BigMer\.m2\repository\dev\lucaargolo\charta-fabric-1.21.1\1.2.5\` |
| 附属工程 | `charta-bridge\`、`charta-BigA\` |

Charta 上游是 common / fabric / neoforge 多加载器工程。只要 Fabric，但 `:common` 是 Fabric 编译的一部分，
必须先跑通（已跑通，可以不管 NeoForge）。已发布到 Maven Local：

```
dev.lucaargolo:charta-fabric-1.21.1:1.2.5
dev.lucaargolo:charta-common-1.21.1:1.2.5
```

发布出来的 jar 是 intermediary 重映射过的（`net.minecraft.class_xxxx`），附属用任意 mappings 都能依赖。

## 2. 环境

| 项 | 值 |
| --- | --- |
| JDK | 21（Gradle 自动选 `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot`） |
| Gradle | 8.14，用仓库自带 wrapper |
| 代理 | Clash for Windows `127.0.0.1:7890`，写在 `C:\Users\BigMer\.gradle\gradle.properties` |
| 发布 key | `CURSEFORGE_API_KEY` / `MODRINTH_TOKEN`（dummy，但**必须存在**，见坑 1） |

> ⚠️ **梯子必须开着。** JVM 不读 Windows 系统代理，Gradle 只认上面那份显式 proxy 配置
> （另外配了 180s 连接/读取超时、6 次重试）。Clash 一退，构建就在 `maven.neoforged.net` /
> `downloadAssets` 上以 `Connection refused: 127.0.0.1:7890` 失败。重启 Clash 后原样重跑，Gradle 断点续传。

## 3. 常用命令

```powershell
cd D:\deepseekharness\charta
.\gradlew.bat :fabric:build                 # 编译 Charta（约 5 分钟，首次更久）
.\gradlew.bat :fabric:publishToMavenLocal   # 改了 Charta 自身就要重装本地 Maven
.\gradlew.bat :fabric:runClient             # 开发客户端（runs\client 下）

cd D:\deepseekharness\charta-BigA           # 附属；桥牌是 ..\charta-bridge
.\gradlew.bat build                         # 产出 build\libs\*.jar
.\gradlew.bat deploy                        # build + 复制进 PCL2 mods，并清掉旧同名 jar
.\gradlew.bat runClient                     # 开发客户端，Charta 作为依赖一起加载
```

桥牌有个免手动开局的调试牌桌：`$env:BRIDGE_DEBUG_SCREEN=1` 再 `runClient`，进世界直接拍一张自动对局
的牌桌（机器人走真人同一条构造路径），不用先备牌组/桌布/多方块牌桌/椅子。dev client 已带
`--quickPlaySingleplayer "New World"`，会直接进 `runs/client/saves/New World`；看输出里的
`debug: trick n/13 ...` 心跳，13 墩跑完无异常即通过。

## 4. 注意事项（务必遵守）

### 4.1 改完**不要自己启动游戏**验证，只要 `deploy`

- **作者自己开 PCL2 进游戏看。** 部署完报一声"已部署"就行，`runClient` 不是必需的。
- 只有"编译能过、但只有进游戏才知道"的问题（mixin 注入失败、注册顺序、启动崩溃、渲染 NPE）才值得跑一次；
  **真跑了，跑完把 `runs\` 删掉**（4.2）。
- `build` 才刷新 `build/libs` 的 jar，`deploy` 才有东西可复制；只跑 `runClient` **不产出 jar**（坑 7）。
- 构建期能验证的就别开游戏：`javap` 看类、解 jar 看 refmap、脚本按公式列盒子、
  `ConvertFrom-Json` 校验语言文件——比启动一次快得多，也不留垃圾。

### 4.2 自测留下的 `runs\` 要删

`gradlew runClient` 会在 `<工程>\runs\client\` 现搭一份**完整独立实例**（saves / logs / config /
options.txt / downloads，十几 MB），和作者 PCL2 那份毫无关系：

```powershell
Remove-Item -Recurse -Force D:\deepseekharness\charta-BigA\runs
```

- 可再生，删了下次 `runClient` 自己重建；里面只有自测世界和日志。想留自测世界就只留 `runs\client\saves`。
- **别删** `~\.gradle\caches\fabric-loom`（资产/库共用，删了要重下几百 MB）。

### 4.3 部署目标

两个工程的 `deploy` 默认落到 `D:/PCL2/.minecraft/versions/1.21.1-Fabric 0.19.5/mods`，
可用 `-Ppcl2ModsDir=...` 覆盖。`deploy` 会先删掉同前缀旧 jar，所以**别手工 copy**——
新旧名字交替时容易留两份，游戏会同时加载（坑 7、坑 20）。

---

## 5. 附属开发速查

### 5.1 只要两处注册

```java
Registry.register(Games.getRegistry(), id("bridge"), BridgeGame::new);          // charta:game_type
Registry.register(BuiltInRegistries.MENU, id("bridge"),                          // 原版菜单表
    new ExtendedScreenHandlerType<>(BridgeMenu::new, AbstractCardMenu.Definition.STREAM_CODEC));
```

Charta 的 `TableScreen` 遍历 `Games.getRegistry()` 现场生成按钮，注册完游戏自动出现在「选择游戏」里。

⚠️ 注册 id 的 **path 会被拿去拼三处资源路径**（`gameId.toLanguageKey()`）：

| 用途 | 由 id `bridge:contract_bridge` 推出 |
| --- | --- |
| 按钮/界面标题 | 语言键 `bridge.contract_bridge`（**不是** `game_type.xxx`） |
| 按钮贴图 | `assets/bridge/textures/gui/game/contract_bridge.png` |
| 玩法说明页 | 语言键 `charta_md:bridge.how_to_play_contract_bridge` |

说明页文件必须放 `assets/<ns>/lang/charta_md/<locale>/*.md`——`MarkdownResource` 把路径写死成
`lang/charta_md`，不跟命名空间走。

### 5.2 必须实现的类

| 类 | 作用 | 父类 |
| --- | --- | --- |
| `BridgeGame` | 规则、发牌、回合、结算 | `Game<G, M>`（`createMenu` `getDeckPredicate` `getCardPredicate` `canPlay` `startGame` `runGame` `endGame` `getOptions`） |
| `BridgeMenu` | 声明牌槽 | `AbstractCardMenu<G, M>` |
| `BridgeScreen` | 画背景 + 文字 | `GameScreen<G, M>`（继承它就白拿整套动画/拖拽/历史面板） |

白拿的部分：`AbstractCardWidget`/`CardSlotWidget` 动画、`CardContainerSynchronizer` 牌表同步、
`ContainerData` 回合同步、`GameOption` 选项控件、数据驱动的牌面/花色/牌背资源、`ModSounds` 音效。

### 5.3 API 备忘

```java
new PlaySlot(this, new LinkedList<>(), x, y, z, angle, drawSlot);  // TABLE 是 160×160 平面，z 是离桌高度
dealCards(dealPile, player, 1);                                    // 自动进 hand + censoredHand
addSlot(...);                                                      // index 递增，Menu 里 g -> g.getSlot(i)
table(Component...);  play(player, Component...);  player.sendTitle(...);
new AutoPlayer(intelligence);                                      // shouldCompute() == true，会自动出牌
```

---

## 6. 现有工程结构（以 charta-bridge 为参考）

- `BridgeMod.java` — 两处注册 + `ensureChartaInitialised()` 顺序兜底。
- `BridgeClient.java` — `MenuScreens.register`。
- `game/BridgeGame.java` — 4 人 2v2：发牌、跟花色校验、13 墩、成对计分。座位用 `getSeat()`（坑 9）。
  **未实现**：叫牌、明手、定约分（早期版本，后已补齐，见变更记录）。
- `game/BridgeBot.java` — `AutoPlayer` 子类。
- `game/BridgeMenu.java` — 只决定牌槽的顺序（`HAND_ORDER`）和类型（`seatSlot`），坐标全交给 `BridgeLayout`。
- `client/BridgeLayout.java` — 所有可动元素的唯一坐标来源。`bounds()` 一律返回屏幕坐标，
  三种 `CardSlot.Type` 的私有约定只在 `setSlot` 里翻译一次；`defaults(Element)` 就是出厂布局，
  配置文件只是覆盖层（坑 48）。
- `client/BridgeScreen.java` — 叫牌弹窗、出牌/结算绘制、**F9 编辑模式**（拖动/滚轮缩放/`R` 复位 +
  右下角实时鼠标坐标与指针下元素）。`renderTopBar` 被覆写成空，那 28px 让给北家手牌。
- `mixin/CardSlotGeometry.java` — 让 `final` 槽坐标准写，并加一份可改的声明尺寸。
- `mixin/CardSlotWidgetColumnStep.java` — 竖排手牌那个写死的 10px 步进（坑 47）。
- `mixin/CardSlotWidgetMetrics.java` / `GameScreenMetrics.java` — 把静态的 `getWidth/getHeight`
  重定向到实例声明尺寸（坑 19）。

选项面板入口：牌桌「选择游戏」里 **Shift+左键** 点游戏按钮 → 齿轮 → 「空座位由电脑补齐」。
改包名/模组 id 要同步 `gradle.properties` 的 `mod_id`/`group`、Java 包路径、贴图和语言键。

---

## 7. 踩过的坑（别再踩）

坑号是稳定的，代码注释里的"见坑 N"就以这里为准。

1. **`CURSEFORGE_API_KEY` 是配置期强校验** — 模板的 `cursegradle` 在 configuration 阶段就要 key，
   没设的话**任何** gradle 任务都跑不起来（`apiKey not set for project ...`）。已设 dummy。
2. **`fabric.mod.json` 版本谓词不支持 maven 区间** ⚠️ — `[1.21, 1.21.2)` 一个版本都匹配不上：
   加载器先按空格切、找不到 `>=/<=/>/</~` 就当 `EQUAL`，整串丢给 parser 解析不了、退化成 StringVersion，
   于是变成"版本 == 字面量 `[1.21,1.21.2)`"，永远为假（表现是 `HARD_DEP_INCOMPATIBLE_PRESELECTED` 或直接拒载）。
   空格有没有都一样。唯一写法是**空格分隔的运算符**（会 AND）：`>=1.21 <1.21.2`；`~1.21.1` 等价
   `>=1.21.1 <1.22.0`（会放进 1.21.2+，而 Charta 1.2.5 是按 1.21.1 编的）。别猜，拿真加载器调
   `VersionPredicateParser.parse(p).test(...)` 验一遍（30 行，不用开游戏）。
3. **`depends: charta` 不足以保证加载顺序** ⚠️ — Fabric 不保证附属 entrypoint 排在依赖后，且跨进程不稳定。
   踩中崩在 `ChartaMod.instance` is null → `Games.<clinit>`。更阴的是 `Games` 静态初始化一旦抛异常
   就永久失效（后续 `NoClassDefFoundError`），try/catch 重试没用。
   而且**两个注册只能在 `onInitialize()` 里做**：main 阶段一结束，所有注册表（含 `charta:game_type`）
   就冻结，推到 lifecycle 事件只会拿到 `Registry is already frozen`。
   解法（已在 `BridgeMod`）：开头 `ensureChartaInitialised()`，`getInstance() == null` 就
   `new FabricChartaMod()`——构造函数只置 `instance` 和 packetManager，真注册在 `init()` 里、只跑一次。
   注意 `AbstractCardMenu.Definition.STREAM_CODEC` 同样踩雷（`Definition` → `Deck` → `Suits` → `ChartaMod.registry`）。
4. **`ModMenuTypes.REGISTRY` 不能事后 `register()`** — 它在 `init()` 里已经遍历注册过，之后再 register
   只进 entries map。附属要用原版 `BuiltInRegistries.MENU`；只有 `Games.getRegistry()` 这种裸注册表能直接 register。
5. **Charta 的 POM 会拖 JEI / REI** — 附属 repositories 要加 `maven.blamejared.com` 和 `maven.shedaniel.me`。
6. **别在开着 `runClient` 时构建另一个工程** — 共用 `~/.gradle/caches/fabric-loom`，会撞锁
   （`ACQUIRED_PREVIOUS_OWNER_DISOWNED`）和删文件失败。
7. **`build/libs` 的 jar 只有 `gradlew build` 才刷新** — 只跑 `runClient` 不会生成 jar，直接复制会部署旧包。
8. 同坑 3 的后半（注册表冻结）。
9. **`CardPlayer.equals` 会被 `containsAll` 反向调用** ⚠️ — `CardTableBlockEntity.serverTick` 靠
   `seatedPlayers.containsAll(game.getPlayers())` 维持对局，机器人不在 `seatedPlayers` 里，
   朴素 `equals` 会让对局开局第一 tick 就被判负解散。`contains` 走 `indexOf` → 调**参数**的 `equals`，
   所以 `BridgeBot.equals` 返回 `!(other instanceof BridgeBot)` 就能骗过去。
   代价是 `indexOf(bot)` 返回第一个真人的座位：Charta 内置 `ContainerData`（当前玩家）会画错，
   解法是 `BridgeMenu` 再 `addDataSlots` 一个自己的座位槽（id 排后面天然覆盖）；自己的轮转一律用 `getSeat()`。
10. **`getCensoredHand` 是懒创建的** — `dealCards` 先给真手牌 add、再给 censored add，censored 若还没创建
    会按"已发 1 张"初始化再加 1 张 → 暗牌多一张。`startGame()` 里先 `getCensoredHand(p).clear()` 把时机提前。
11. **`PREVIEW` 槽用绝对屏幕坐标** — `GameScreen` 对其它类型都加 `topPos`，唯独 `PREVIEW`/`HORIZONTAL` 不加。
    要在牌桌中间放暗牌预览就得自己在 Menu 里补 `topPos`；专用服务端没有 `Minecraft`，用 `try/catch(Throwable)` 兜底。
12. **真人玩家的 `CardPlayer` 也是 `AutoPlayer` 匿名子类** — 判断机器人看 `getEntity() == null`，别看 `instanceof`。
13. **模糊的关键不是"糊"，是糊完之后那层均匀的霜** ⚠️ — `GameScreen.render` 第一行已经整屏糊过了
    （`renderBackground` 被覆写成空），而 `processBlurEffect` 有 `blurriness >= 1` 门槛、默认才 5px。
    想明显只能 (a) 再跑一遍 pass 叠加半径 + (b) 糊完铺一层整屏半透明霜。霜必须铺满整屏（不是 scissor 到弹窗）。
    两层都别重：`SCRIM = 0x6B101014` + 弹窗再压 `BACKDROP = 0x59101014`；反面教材是面板贴图本身就是
    30~45% 黑、再叠 `renderTransparentBackground` 的 75% 黑和 `0xC8` 背板，世界只剩 4%。额外 blur pass
    必须在 `super.render()` **之前**。
14. **`GameScreen` 给每种 `CardSlot.Type` 套的坐标变换都不一样** ⚠️ — 命中检测自己会减 `leftPos`/`topPos`，
    两种类型又额外加减一遍来抵消，等于命中框和绘制点必须逐像素对齐：

    | 类型 | 实际绘制点 | `slot.y` 要写 |
    | --- | --- | --- |
    | `PREVIEW` | `slot.y`（绝对） | 屏幕 y |
    | `HORIZONTAL` | `slot.y + 屏幕高 - 卡高` | `屏幕y - 屏幕高 + 卡高`（负的底边偏移，原版是 `-5`） |
    | 其它 | `slot.y + topPos` | `屏幕y - panelTop` |

    `slot.x` 例外，三种都是 `leftPos + slot.x`。⚠️ HORIZONTAL 的 `bounds[1]` 因此是负数，标签/黄框要另用
    `handTop()` 换算。症状：手牌看得见但点不中/拖不动。
15. **给 Charta 类加 mixin 的两条互斥规则** ⚠️ — mixin 包里的类被那个 config "占有"，普通代码直接引用会
    `IllegalClassLoadError ... owned by bridge.mixins.json`；而 Mixin AP 不允许 interface mixin 指向 class。
    做法：访问器接口放普通包（`client/CardSlotAccess.java`），mixin 类 `implements` 它、`@Accessor` 声明在 mixin 里。
    写 `final` 字段的 `@Accessor` **必须加 `@Mutable`**，否则编译启动都过、一进对局就 `IllegalAccessError`
    （生成的 setter 不是构造器，`PUTFIELD` 写 final 非法）。`@Shadow` 只能读。
16. **`CardSlot.x/y` 是 `final`，宽度根本没有字段** — 宽度是 `CardSlot.getWidth(Type)` 静态算的，
    想运行时拖动/缩放只能上 mixin（坑 15）。槽的声明宽度同时是扇牌疏密的唯一旋钮（≤20 时 13 张挤成一坨）。
17. **铭牌/明手高亮必须和牌槽锚点共用常量** — 一边用绝对槽坐标、一边写 `leftPos + x`，改一边忘一边就会飘。
18. **`PREVIEW` 槽的可见宽度只有标称宽度的一半** ⚠️ — `CardSlotWidget` 扇牌时会钳制 `leftOffset`，
    牌一超宽就整叠压回槽宽以内。手牌画在 `renderBg` 之后，和面板重叠会盖住面板里的表格。
19. **`CardSlot.getWidth/getHeight` 是静态的，"缩放牌槽"曾是空操作** ⚠️ — widget 和 `isHoveringPrecise`
    调的都是静态重载，往实例上写宽度没人读（F9 拖动有用、滚轮对手牌毫无反应）。
    修法是在**两个**调用点 `@Redirect` 到实例尺寸，只改一边就是"牌按新宽度摊开、点击框还是老宽度"。
    `render` 里那句 `getWidth(slot) == CardImage.WIDTH*1.5f` 故意不动。
20. **`remap` 该不该关，取决于"有没有覆写原版方法"** ⚠️ — 发布 jar 是 remap 过的：覆写原版方法的成员
    变成 intermediary 名（`renderWidget` → `method_48579`），Charta 自己的成员保持原名。
    所以覆写原版 → 留 `remap = true`；自己的成员 → `remap = false`（不然 AP 会报
    `Unable to locate obfuscation mapping`，加 `remap = false` 就跳过解析）。同名重载要写全描述符或
    `require = 0`。验证：构建后看 jar 内 `*-refmap.json` 该有的有、不该有的没有。
    症状：注入失败 → `GameScreen.init()` 炸 → `slotWidgets` 空表 → `IndexOutOfBoundsException: length 0`。
21. **对手手牌别用 `PREVIEW` 槽** — 它是 1/3 缩放，13 张挤在 41px 里只剩一坨。
    北/南用 `HORIZONTAL`，西/东用 `VERTICAL`，并把槽的声明尺寸设成盒子尺寸。
22. **想让某个牌槽"自己别画"，最省事的是让它永远 `isEmpty()`** — `render` 画槽内牌的条件就是
    `!slot.getSlot().isEmpty()`，覆写 `isEmpty() -> true` 后原版画法整体退出，
    而命中检测、`canInsertCard`、镜像同步（比对 `cards` 列表）、`insertCards`（`addAll`）全部不受影响。
23. **`isEmpty()` 撒谎的副作用：`insertCards` 永远插到下标 0** — 与 `addLast` 相反，客户端按坐标定位会错位；
    解法是用 `currentTrick` 整体重写一遍墩堆，且必须用**可变**列表（`setCards` 会换掉 `cards` 字段）。
24. **自己画牌要复刻 `AbstractCardWidget` 的 4/3 外扩和四个 shader uniform** — 不先
    `getCardInset().accept(0f)` / `getCardFov().accept(30f)` / `XRot,YRot = 0`，就会套用上一帧最后一张
    hover 过的牌留下的抬起值。
25. **`Game.createPlayerHand` 是 `protected`，可以换成自己的 `GameSlot` 子类** — 手牌槽是
    `hands.computeIfAbsent` 现造的，`getIndex()` 恒为 -1，要认主人得把 `CardPlayer` 一起存。
    权限门必须做在 `GameSlot` 上（`CardSlot.canInsertCard/insertCards` 是 final），且只在服务端被问。
26. **手牌排序必须在服务端改真实那条 list** — 点击包传的是扇中下标、服务端按下标取牌，
    所以要 `getPlayerHand(p).getCards()` 原地 `sort`，不能 `setCards` 换列表。
27. **bitmap 字体的 `height` 是屏幕像素，格子边长由 `chars` 网格反推** — `load()` 里
    `scale = height / cellH`。要原像素 1:1 就让 `height` = 贴图格高。`ascent` 决定底边
    （`bottom = ascent`、`top = ascent - height`），改 ascent 就是挪字形、居中算式必须跟着走；
    `ascent > height` 会被 validate 判错。**`advance` 是算出来的**（`(int)(0.5 + 墨宽*scale) + 1`），
    字形控制不了它，只能靠"在格子里站哪儿"定。墨高不同的两个字共用的是**中心**不是高度。
28. **像素画别缩，原样贴** — 缩到 8px 会把 1px 描边断成点，缩到 10px 作者仍看得出变形。
    最终做法：格子开到最大素材尺寸（13x12 = 11x12 + 左右各 1px 边距），原像素拷贝、各自居中，
    代价是黑桃/梅花比红心/方片高——那本来就是作者画的比例。别用系统字体的 ♠♥♦♣ 糊弄。
    验证：逐格比 alpha 掩码，必须 `mask-mismatches=0`、`maxChannelDev=0`。
    生成脚本 `tools/make-suit-font.ps1`（**28b 游程分配**：真要缩时不能点采样，要按连续同状态游程
    比例分配输出像素、余量补水最缺的那段；`[int]` 是四舍六入五成双，模拟 Java `(int)` 要写 `[math]::Floor`）。
29. **深色素材贴深色底要在生成时就提亮，别靠 tint** — `Deck.getSuitColor` 只把比中灰暗的颜色乘 2.5，
    纯黑乘完还是黑，而且它按牌面平均色算、和素材颜色对不上。做法：生成脚本把各通道等比提到最亮通道 `0xE6`，
    之后不再 tint。深底上的方位字母要用深色 + 关阴影。
30. **`Ranks.ordinal()` 是 A=1 到 K=13，"自然序"里 A 最小** — `Rank.compareTo` 就是 ordinal 相减，
    直接 `comparing(rank).reversed()` 会把 K 排最前。`Ranks.STANDARD` 是注册顺序不是大小顺序。
31. **一次性演出要从服务端状态沿触发并自管生命周期** — 赢家是上升沿：只在"牌摊着没收走"的窗口里同步，
    和 `trickPile.clear()` 一起清。客户端用 `Util.getMillis()` 计时（帧率无关）。动画要明显短于
    `TRICK_LINGER`（1250ms）；清状态的条件挂"牌堆变空"而不是计时（连赢两墩时"赢家变了吗"判断不出来）。
32. **"A 最大"有好几个入口** — 修了排序还得改赢墩判定里的 `rank().compareTo(...)`，
    否则牌桌上 Q 会压 A。凡是"比大小"都走 `rankOrder`；`highCardPoints` 里的 A=1 是**故意**的点值表。
33. **"轮到谁"用 `SYNC_TRICK_WINNER < 0` 当"这墩还活着"** — 别再造第二个条件。颜色用
    `getPlayerAtSeat(seat).getColor()`（和铭牌同色），脉冲只动 alpha。
34. **面板 `baseHeight` 按"最下面那排的底边 + 边距"算** — 只有 dx/dy/scale 会存配置文件，宽高不存，
    所以改默认值立刻生效、不用升 VERSION。内容坐标用的是菜单里的 `PANEL_WIDTH/HEIGHT`，和面板尺寸两边要一起改。
35. **记录类表格要序号就做成网格，序号按参与者数分组，可视窗口钉在最新一条上** — 一轮 = 参与者数手，
    一列就该是一整轮。钉末尾（不是开头）才对：表头数字是真实轮数，新叫品不会把在看的东西挪走。
    几何要自上而下推导，别写死 y。
36. **鼠标悬停掉帧是 Charta hover 的 O(n²)** — `GameScreen.render` 和 `CardSlotWidget.renderWidget`
    都在"画每个 renderable 之前先把 hover 重画一遍"，外层一个 renderable 又是整手牌，四家 13 张时
    悬停一张 ≈ 1400 张牌的绘制。修法：`@Redirect` 把循环里那次重定向成空实现（循环后那次在最上层，
    几何/shader/混合完全一致，是纯删除）。选择器要 `ordinal = 0`，handler 要 `static`。
    副作用只是悬停切换晚一帧。
37. **要"任何窗口尺寸都完整显示"就做固定设计帧** ⚠️ — 不要在几百个坐标上乘比例，而是让屏幕本身变成
    640x360（= 1920x1080 开界面尺寸 3 的原尺寸）：`BridgeFrame` 取两轴比例较小值等比缩放 + 居中，
    `init()` 在 `super.init()` 之前改写 `width/height`，`render()` 把原版 pass 包进 pose 并在入口换算鼠标，
    四个 `mouse*` 覆盖同样换算（`dragX/dragY` 是长度要除以 scale）。
    三件 pose 带不动的：`enableScissor` 不认 pose（传窗口像素）、`Gui` 自己那份聊天（重定向成空、pop 后自己画）、
    `containerTick` 自己读 `MouseHandler.xpos()`（重定向，否则悬停倾角放大 1/scale 倍）。
    新 mixin 要 `instanceof BridgeScreen` 守卫，因为 `GameScreen` 是 Charta 所有游戏共用的基类。
38. **方位别写成 `seat - localSeat`** — 那样四个人的画面互为镜像，谁看自己都在南。
    座位索引直接当方位（0 北 / 1 东 / 2 南 / 3 西，正好是游戏顺时针），屏幕不随视角旋转，只有措辞跟着走。
    （后来发现"座位就是方位"也不对，见坑 42。）
39. **`getCensoredHand` 返回真手牌时 `HandSlot.postUpdate` 会把真手牌刷成牌背** ⚠️ — 它每 tick 把
    censored 槽填成空白牌，而明手那份直接返回真手牌 GameSlot，于是明手自己看全是牌背、花色判定全瞎。
    修法是把 `shouldUpdate` 谓词写对：`game -> !game.isDummy(this.getCardPlayer())`。
40. **明手的牌归庄家** — Charta 默认任何手牌随便拿，另外两家碰巧被 censored 槽锁着，明手就是唯一的洞。
    `canHandleHand` 一条写完：`player == owner && !isDummy(owner)`，否则 `isDummy(owner) && 庄家`。
41. **自定义字体里的中文会变方框** ⚠️ — `include/default` 不含 CJK，要 reference 整个 `minecraft:default`
    （provider 先声明先赢，自家 bitmap 必须排前面）。另外 `Component.copy()` 再 `append` 会继承根的
    Style（含 `withFont`），要换成 `Component.empty().append(...)`。别翻 client jar 找 unifont——
    它是空的，真字体由资源索引提供。
42. **座位索引不是方位** ⚠️ — `getOrderedPlayers()` 会 shuffle 起点，整圈被随机旋转。
    正确读法是椅子 `FACING` 指向桌子，取反方向即玩家方位；机器人和空位用已知座位的顺时针延续补全。
    `compass(seat)` / `seatAtCompass()` 是唯一出口，客户端服务端各自算（同名单同椅子，结果必然一致）。
43. **这个 shell 是 Windows PowerShell 5.1** ⚠️ — 没有 `` `u{XXXX} ``（用 `[string][char]0x9225`）；
    `Get-Content -Raw | Set-Content` 会按 ANSI 往返毁掉 UTF-8，一律走
    `[System.IO.File]::ReadAllText/WriteAllText(..., UTF8Encoding($false))`（绝对路径，`cd` 不影响 .NET 的 CWD）。
    已在 Gradle 侧钉死 `compileJava.options.encoding` / `processResources.filteringCharset`。
44. **只打客户端类的 mixin 必须放进 `client` 块** — `fabric.mod.json` 的 environment 是 `*`，
    `mixins` 数组两边都加载，专用服务端会去解析客户端类而硬失败（Charta 自己就是这么分的）。
45. **紧凑整数编码上的取模会出负数** — `PASS/DOUBLE/REDOUBLE` 是 0/1/2，`(0-3) % 5 == -3`
    （`glyph(-3)` 走 default 又正好是 NT，肉眼看不出来）。非叫品码要短路。
46. **要发布补的是元数据不是功能** — authors / contact / icon / LICENSE / README、
    `depends` 用范围断言、jar 内 `META-INF/LICENSE_*`、英文语言文件别留中文。
47. **竖排手牌的"每张露出一截"有个写死的 10px 上限** ⚠️ — `CardSlotWidget.renderWidget` 竖排分支是
    `topOffset = min(10, (高-52.5)/(张数-1))`，光加长盒子卡片间距一点不变。
    用 `@ModifyConstant(floatValue = 10f, ordinal = 1)` 把 10 换成一张牌的高度。
    ⚠️ 两个一样的 `ldc 10.0f`，不加 ordinal 会把横排的余量一起改掉。
48. **"按我现在的布局当默认"要先看清 `(int)` 截断吃掉了什么** — `bounds()` 是
    `anchor + (int) dy`，先截断再相加，存档里的分数永远存在又永远不起作用。
    落成默认值要按截断后的整数写：`pile dy=+27`、`contract dy=-156`、`turn dy=+17`、
    `progress (-158, 19→20)`、`tricks (177, 9)`，其余 10 个元素 0。
    实现是让 `defaults(Element)` 直接存默认值、`reset()` 复位到它，`VERSION` 跟着升。
    副作用：默认值不再是"锚点原位"，以后改 `anchor()` 常量必须先升 `VERSION`。
49. **手牌"拿起→跟着鼠标→放下"整条交互不要自己写** ⚠️ — Charta 自带这条路，疯狂的8 / 接龙都在用，
    自己写必然在右键、Esc、扇面重排上处处对不齐。正确做法是**让游戏自己的 `CardSlot` 当盒子**：
    出牌区那个 `GameSlot` 覆写 `removeAll()` 返回 `false`（一次拿一张，坑 53），出牌区的 `canInsertCard` / `canRemoveCard`
    按回合门禁（坑 52），不是按“这块是谁的”，其余全部交给 `GameScreen.mouseClicked` →
    `CardContainerSlotClickPayload` → `AbstractCardMenu` 的 `carriedCards`，屏幕侧一行代码都不用动。
    几个必须知道的细节：
    * **右键不用管** — `GameScreen.mouseClicked` 根本不看 `button`，左右键天然同义。
    * **Esc 不用管** — `AbstractCardMenu.removed()` 会把还在光标上的牌退回手牌。
    * **拿起的牌是真的离开手牌了** — 因此一切 `hand.contains(card)` 判定都必须同时看
      `carriedCards` 和暂存区（Charta 自己的 `Game.getFullHand` 就是加了这个 carried 分支）。
    * **想不让某个格子自己画**，覆写该 `GameSlot` 的 `isEmpty()` 返回 `true`（`GameScreen.render`
      只给非空格子建 widget）。但此时 `canRemoveCard` 里**不能再用 `isEmpty()`**，要改用 `size() > 0`。
    * 槽位类型别用 `DEFAULT`：`GameScreen.render` 有个 `getWidth(slot) == CardImage.WIDTH * 1.5f`
      的判定，会给它盖一张 38x53 的落点边框。自己画的格子用 `SMALL`，再靠 `daa$setSize` 定实际大小。
50. **`syncValue(index)` 之类的手写区间分发，上界必须显式写** ⚠️ — 「连续一段 + 后面几个单独值」时
    写成 `if (index >= BASE) { ... }` 会把后面那几个单独值一起吞掉并返回兜底值，而且**不报错**。
    桥牌那边表现为轮次永远 1、过牌标签永远不出现、结算横幅永远走 default；改成
    `index >= BASE && index < NEXT` 即可。加了新常量后一定要回头数一遍都被谁拦下了。
51. **真人玩家的 `CardPlayer` 就是 `AutoPlayer`，闲置会自动出牌** ⚠️ — 任何"把牌从手牌挪到别处暂存"
    的设计都必须处理它。`AutoPlayer.tick` 在轮到该玩家且 `play` 未完成时，等
    `Mth.lerp(intelligence, 50, 20) + rand(-5, 40)` tick 后直接 `game.getBestPlay(this)` 并出牌。
    踩到的两个后果：
    * `getBestPlay` 默认从 `getPlayerHand` 里挑，**暂存区里的牌不在手牌里**，于是它既看不见那排牌、
      又会按"缺了几张"的手牌去算。要么让它优先返回暂存区（打大A 的做法），要么整个关掉自动出牌。
    * 出牌成功后的善后**不能 `zone.clear()`**，只能按牌一张张移除。否则自动出牌一旦从手牌出，
      暂存区那一排会被整片清掉而牌凭空消失（手牌没回来、也没打出去）。
    另外 `finished[seat]` 判的是 `getPlayerHand(actor).isEmpty()`：把整手牌摆到暂存区时手牌就已经空了，
    所以 `getBestPlay` 必须优先吃暂存区，否则会出现"空手 + 一排牌 + 自动过牌"的僵局。

---

52. **牌池同时当"暂存区"和"台面展示"时，靠回合分，不要靠牌内容分** ⚠️ — 打大A 的出牌区既要当"摆牌暂存"、
    又要当"上家打出的牌摆在那儿"，两者是同一块 `GameSlot`。想按内容区分（有没有打过、上一手是什么）必然
    要再存一份计数，且要在每个入口同步。做法是**按回合门禁**：`ZoneSlot.canInsertCard/canRemoveCard` 一律
    `player == owner && owner == game.currentPlayer && phase == PLAY`。这样"轮到自己"就等价于"这是正在摆的排"，
    别人的回合它就自动降级成只读展示（hover 动画照旧，因为 widget 不看 canRemoveCard）。
    好处是**清扫变得无条件**：`beginTurn(next)` 里直接 `stageOf(next).clear()`，不用判断哪张是打出的、
    哪张是摆着没出的。这条也顺带回答了"牌要留多久"——留到**那个人下次轮到他**，而不是"下一手一出就清"，所以
    下家能读上家打了什么，出完牌的人最后那手会留到本局结束（他不会再轮到）。
53. **"一次取一张"的全部实现就是 `GameSlot.removeAll()` 返回 `false`** — `CardSlot.removeCards(index)`
    分两支：`removeAll()` 为真走整堆（`index == -1` 时 `stream().toList()` + `clear()`），为假走
    `slot.remove(index % size)` 并返回 `List.of(card)`。所以想让"点牌 → 一张回到光标"就只覆写这一个方法，
    别自己算索引。`index` 由 `CardSlotWidget.getHoveredId()` 给，是**槽内卡片下标**；指针在槽里但没压到牌
    时是 `-1`，此时 `removeLast()`、`insertCards` 追加，正好是想要的语义。
54. **`hoveredCardSlot` 是"指针在哪个槽的框里"，`hoveredCardId` 才是"槽里哪张牌"** — 前者由
    `GameScreen.render` 里 `isHoveringPrecise(slot, ...)` 的循环给出，而且**取最后一个命中的槽**，
    所以 `cardSlots` 的添加顺序就是重叠时的优先级。要让出牌区压过手牌就**后 add**（本模组：槽 0 牌堆、
    1..5 手牌、6..10 五个出牌区）。反过来，如果出牌区先 add，重叠处点下去会变成抓手牌。
55. **`HORIZONTAL` 槽的横向扇面会自己居中，但槽高必须留出标题带** ⚠️ — `CardSlotWidget.renderWidget` 的
    横向分支：`leftOffset = 卡宽 + max(0, 声明宽 - 卡宽)`，再按 `leftExcess/(n-1)` 回缩，最后超过
    `卡宽*1.1` 才截断并居中。实测 n=1 居中、n=2..3 截断居中、n≥4 恰好铺满整个声明宽（8 张时步长 13.5），
    所以声明宽 132 装得下十张。**但声明高同时也是 hover 框高、卡片却按 widget 高（52.5）挂在框底**，
    于是 `box[1] + box[3] - 52.5` 才是牌顶：标题带要有 11px 才不压牌。`HORIZONTAL` 的 `slot.y` 写
    `屏幕y - 屏幕高 + 框高`（见坑 14），别手推。
56. **`GameScreen` 的落点边框只在 `getWidth(slot) == CardImage.WIDTH * 1.5f` 时画** — 即正好 37.5 的
    `DEFAULT` 宽。所以宽出牌区（132）不会吃到那层 38x53 的预设边框，屏幕自己画的框就是唯一的框；
    槽类型用 `HORIZONTAL` 而不是 `DEFAULT` 也是为了这个。
57. **"两副牌"要发给**牌桌**，不是要求玩家准备** ⚠️ — 需求是"打大A是两副扑克"，但玩家手上多半只有
    Charta 自带的一副 52 张。做法是 `DaaGame` 构造里 `doubleDeck()`：把 `gameDeck` 每张 `copy()` 再 add
    一遍。**必须能识别已经翻倍过的牌组**，否则 108 张的会被发成 216：判据用
    `new HashSet<>(gameDeck).size() < gameDeck.size()`（`Card.equals` 含 `flipped`，而父构造器已把全场
    统一 flip，所以同点同花必然相等），成立就跳过。⚠️ 这个分支是本逻辑唯一的静默失败点（判错了就是 216 张，
    只有实际开一局才看得出），所以模组自带的 `data/daa/decks/big_a.json` 就直接写成 108 张（54×2）
    而不是 54 张，让跳过分支每次开桌都真的跑一遍。同步地，`getDeckPredicate` 的门槛是**一副**（≥52 +
    四花色 + 有标准点数或王），`getDeckSize()` 必须读 `gameDeck.size()` 而不是 `getDeck().getCards().size()`
    （后者是玩家给的那半副，发牌进度条会走一半就满）。
58. **强度表永远不要拿 `rank().ordinal()` 当强度用**（坑 30 在打大A 上的复现）⚠️ — BigA 的
    `DaaCards.standardValue` 对 4..K 直接 `return card.rank().ordinal()`，于是 K 拿到 13 = 3 的档位、
    Q 拿到 12 = 2 的档位、J 拿到 11 = A 的档位，而 A/2/3 是另外写死的 11/12/13。症状有两层：**看得见的**
    是手牌排序（同档挨着，扇面读起来是 `3 K 2 Q A J`，像没排过）；**看不见的**是 `Combo` 用
    `groupValue` 当分组键和比较键，一对 J 会和一对 A 平手、一对 K 会和一对 3 平手。做法：普通点数整体
    下移三位（`rank().ordinal() - 3`，4→1 … K→10），A/2/3 = 11/12/13，小王/大王/大A = 14/15/16，
    得到**无重号的 1..16 连续阶梯**。自查：16 张代表牌两两 `value` 必须互不相同（有重号就是这一条）。
59. **牌序要在"牌到手的那一刻"排，不是在整个发牌结束时排一次** ⚠️ — 发牌是
    `scheduledActions` 里一张一张跑的（每张一个 `dealCards(dealPile, receiver, 1)`），只在 `beginPlay`
    开头排一次的话，玩家在整个发牌过程（108 张 ×3 tick ≈ 16 秒）看到的是乱序，最后"啪"地归位，正好是
    "发牌的时候乱，左边还不是最大"的观感。回手同理：`getPlayerHand(p).addAll(...)` 是**追加到列表尾**，
    牌会停在错的位置。做法：`dealCards` 之后立刻 `sortHand(receiver)`；所有回手（清空/取回/四收）统一走
    `returnToHand(player, cards)`（`addAll` + `sortHand` + `syncCensored`）。注意排序必须原地改服务端那条
    真实 list（坑 26），客户端由槽同步自动跟；发牌期间改顺序会让手牌槽每张牌重发一次，108 张的量级无所谓。

60. **大A 的花色是"第一个亮A 的人说了算"，不是发牌前抽出来的** ⚠️ — 原实现发牌前随机抽一门再公布，
    于是"亮A / 反A"根本无从谈起（用户原话："这才是大A规则,你改一下"）。做法：`trump` 初值 `null`，
    发牌窗口就是亮A窗口；`callBigA(player, claim)` 复用 `DaaActionPayload.CALL` 的 `index` 当 claim
    （`0..3` = 亮单A 该花色、`DOUBLE` = 亮双A、`ROB_BASE + suit` = 反A、`REVEAL` = 明打），服务端按
    阶段+座位+是否持有验证；**兜底（全程没人亮 → 红桃A）放在 `beginPlay`**，那边已经是"牌都发完了"。
    客户端 `SYNC_TRUMP` 返回 -1 时显示"大A：还没亮"，不要再画一个抽好的花色。
61. **反A 改判的是花色，不是"去抢那个已经亮出来的花色"** ⚠️ — 两副牌每门只有两张 A，亮单A 的人自己
    手里就捏着一张，所以**别人不可能同时拿着同一门的两张 A**：若照字面写成"反A = 抢已亮花色"，条件
    永远不成立。斗棋规则的原话是"手中有相同花色的一对A牌的玩家有反A权利……该花色A为大A，并且1家打4家"
    —— 即反A 者用自己的对子**改判大A**。落地：`robBigA()` 里 `trump = 反的那门`、`mainASeat = 反A者`、
    `subASeat = -1`（没队友 ⇒ 天然 1v4，不用另写分支）、`doubleNamed = true`（一局只反一次）。
62. **亮A 是把一张 A 摊开亮出来——那张牌会先离开手牌** ⚠️ — 最自然的手感是直接点手里那张 A（而不是
    按按钮），但 Charta 的点击会先把牌提到光标上，**然后** claim 才到服务端。所以服务端数 A 必须走
    `getFullHand()`（手牌+光标），只数 `getPlayerHand()` 会把"两张 A 都在手上"数成一张、反A 被静默拒掉。
    客户端同理：`DaaMenu.localCards()` 要把 `getCarriedCards()` 一起算，否则点完 A 按钮立刻变灰、判定
    和请求打架。表现是"点了反A 的 A 没反应"，很难查。
63. **同一门两张 A 在手上时，"亮单A"和"亮双A"是两种不同权利** ⚠️ — 亮双A 明示两张，`doubleNamed = true`，
    **跳过反A**（斗棋："如果有玩家在亮A过程中，已经选择亮双A，则直接跳过反A过程"）；亮单A 则另一张还藏着，
    **保留被反的可能**。所以别把 `countAces(seat, suit) >= 2` 直接当成"已经亮双A"——亮双A 是 claim 的一种
    （`DaaActionPayload.DOUBLE`），按钮发它、点单张 A 发的是花色号。
64. **反A / 明打 是发完牌之后的事，`CALL_WINDOW` 得留得下** ⚠️ — 规则里反A 在"所有牌发完、定A了之后"，
    本移植版把亮A/反A/明打都放在发牌窗口内（同一 `Phase.DEALING`），因此 `CALL_WINDOW` 从 40 tick 提到
    60 tick：玩家要在这段时间里读完二十来张牌并决定。窗口内出牌区仍是只读（`ZoneSlot` 门禁在
    `Phase.PLAY`），所以这三件事是唯一"发牌时能干的事"。
65. **窗口要能被"说话"顶开** ⚠️ — 只加长 `CALL_WINDOW` 还不够：最后一次亮A 落在窗口最后一 tick 时，
    别人还没机会反A。`Charta Game.tick()` 是 `if(!isGameReady){ if(!scheduledActions.isEmpty())
    removeFirst().run(); else { isGameReady = true; runGame(); } }`——**队列非空就还是发牌窗口**，所以在
    `callBigA()` 成功时往 `scheduledActions` 追加 `CALL_REACTION`(20) 个 `botCallScan` 就等于"把窗口顶开
    一拍"。链是有界的（每人最多说一次，最多 5 个人）；反过来，如果队列空了 `beginPlay` 会在同一 tick 跑、
    阶段立刻变 `PLAY`，之后的 claim 直接被阶段判断拒掉，不会出现"队列没了的挂起追加"。同时把机器人的
    反A/明打等待缩半（`BOT_CALL_SPAN/2`），因为那是应答，不是自己拿主意。
66. **`GameSlot` 的插入是"追加"，没有插位** ⚠️ — 发牌时每张都 `sortHand` 所以看着永远是有序的，但玩家
    手动拿起一张再放回手牌走的是 `GameSlot.add`，只能落在列表尾，扇面就出现一张"跑到最左边/最右边"的
    异类。做法是在服务端加 `sortHandNow(player)`（`SORT` 动作 + `整理` 按钮 + `R`），因为顺序是真实
    list 的顺序，客户端只是副本。顺带两个 API 陷阱：`GameSlot` 同时有 `remove(int)`（返回 `Card`）和
    `remove(Card)`（返回 `boolean`），`remove(card)` 会挑到 boolean 那个，所以 `if (slot.remove(card))`
    是合法的，别按 `LinkedList#remove` 的直觉去写成取牌；`GameSlot.getCards()` 返回 `Iterable`，
    要用 `stream()`/`forEach`，不能直接当 `List`。
67. **真人玩家也有一台 `AutoPlayer` 在替他出牌，回合计时必须把它按住** ⚠️ — `LivingEntityMixin` 里
    真实玩家的 `CardPlayer` 就是一个匿名 `AutoPlayer`，`Game.tick()` 的 else 分支
    `getPlayers().forEach(p -> p.tick(this))` 会走到 `AutoPlayer.tick`，它的随机耐心一到就
    `game.getBestPlay(this)` 自己把牌打出去（本工程还有 `AutoPlayerPace` 把人类的等待拉到 10-16 秒）。
    所以"出牌时长"如果只是自己计时，会被它抢跑。做法：`DaaGame.tick()` **整个覆写** `Game.tick()`，
    在计时生效且当前是真人时**跳过这个座位的 tick**（其余座位照常 tick），自己递减 `turnTicks`，
    归零调 `timeoutTurn()`（先出摆好的那排 → 再问 `getBestPlay` → 都没有就 `submitPass`）。两个必查点：
    ① `Game.tick()` **不是 final**，覆写时要把 `!isGameReady` 那条发牌队列分支原样补回去，否则牌发到
    一半就停；② `awaitingPlay` 为 false 时不能强行提交，`awaitPlay()` 是唯一把 `turnTicks` 重新上满的
    地方（被拒的非法出牌也走它，等于"退回重来给满时间"）。
68. **`GameOption.Number` 底层是 byte** ⚠️ — `toByte` 就是 `value.byteValue()`、`fromByte` 是
    `(int) value`（带符号 byte），所以数值上限是 **127**；`出牌时长` 封在 120 秒，写 180 会翻成负数，
    表现是"计时一开局就到点"。另外 `setRawOptions` 是按**下标**灌的，新选项必须加在 `getOptions()`
    末尾，老存档少一个字节时它会保持默认值（`if (i < options.length)`），加在中间会把别人的值串位。
69. **公告栏不用新开同步位，但要画在 `super.render()` 之后** ⚠️ — "谁叫了A"这条信息已经全在镜像字段里：
    `SYNC_MAIN_A`（谁定的）、`SYNC_ROBBED`（是反A 还是亮A）、`SYNC_DOUBLE`（是不是亮双A）、
    `SYNC_SUB_A`（明打，它只在 `subARevealed` 时返回座位，所以"明打"能单独认出来）、`SYNC_TRUMP`
    （哪门）——一条同步都不必加。渲染位置才是坑：`GameScreen.render` 在 `super.render()` 里画各 zone 的
    牌和 z=100 的光标牌，`renderBg` 阶段画的公告栏会被它们盖住；要放在 `super.render(...)` 之后、
    `frame.pop()` 之前（仍在 640x360 设计帧的 pose 里，坐标才对）。F8 面板同理。
70. **wire 上只有一个 int，"地址"就必须就是那张牌** ⚠️ — F8 后门只发 `(action, index)`，所以 index 编成
    `rank * suits + suit`（末尾两格给大王/小王），服务端 `DaaGame.debugCard` 和客户端
    `DaaScreen.cheatCard` 必须各自反解得一模一样，任何一边改了顺序都是"点A给K"。造牌用
    `Card.create(suit, rank)`（`flipped=false`，直接进手牌不用再 flip）；拿它去查
    `Deck.getCardTranslatableKey` 也是通的（`Card.equals` 比 components，而牌组里的牌就是同一套
    组件）。键位上 F8/F9 都判在 `keyPressed` 最前面，面板打开时 `Esc` 要先吃掉（关面板），否则会连桌子
    一起关掉。
71. **往 `Element` 里加新元素不必升 `VERSION`** — 老布局文件里没有新键，`load()` 只灌存在的键，
    `dx/dy` 缺省为 0、`scale()` 把 0 当 1，正好落到新写的锚点，所以只有"搬动已有元素的默认位置"才需要
    升版本。`CALL_BANNER` 的锚点（200,85，225x40）是需求里给的绝对坐标，它和两侧出牌区（左 ends 228、
    右 starts 412）各有约 28px 重叠，这是故意的：公告栏是 overlay，读字优先。
72. **真七 / 真八是"任意点数的七张 / 八张"，不是只有 4** ⚠️ — 老代码把 `TRUE_SEVEN` / `TRUE_EIGHT` 写在
    `DaaCards.isFour(card)` 的分支里，于是 8 张 K 掉进兜底的 `BIG_BOMB`，界面上显示成"大蛋子"。两副牌
    之后任何一个点数都可能有 8 张，所以判定只有一句"同点数且张数是 7 / 8"，和 4 无关；顺手把只会在这里
    出现的 `BIG_BOMB` 整个删掉，`Kind` 从 17 级收到 16 级。
73. **把牌型梯子直接写成 `enum` 的声明顺序** — `Kind` 从强到弱声明，`tier` 就是 `ordinal()`，`beats` 于是
    退化成一次比较，不会出现"改了梯子忘了改某个 map"的错位。代价是**新增/删除牌型会移动所有 ordinal**：
    `DaaMenu` 的 `SYNC_TABLE_KIND` 存 `kind.ordinal() + 1`、读回时按 `Kind.values()[index-1]` 取，所以
    两边天然跟着走；但如果哪天改成写死数字，删掉 `BIG_BOMB` 这种改动就会静默串味。
74. **单牌的 `key` 必须走 `DaaCards.value(card, trump)`** ⚠️ — 同点数分支原本统一用 `groupValue`，于是单
    大A 的 key 只有 11（A 的档位），压不住 key 15 的单大王，牌桌上表现为"大A 被大王管了"。凡是"一张牌单
    独出"的地方都要用带 trump 的 `value`：`SINGLE` 和 `SINGLE` 的 joker 分支都是。
75. **从同步字段重建 `Combo` 一定要用 `Combo.synced(kind, size, key)`** ⚠️ — 线上只传 kind/size/key，
    `bigA` 和 `joker` 两个 flag 是从 key 反推的（key 落在 `V_BIG_A` / `V_SMALL_JOKER..` 就是）。客户端
    `DaaScreen.tableCombo()` 原本自己 `new Combo(...)` 把两个 flag 留成 false，于是**客户端预览允许用
    蛋子 压单王**，服务端其实会拒 —— 本地预览和服务端校验是两套代码时，这类 flag 漏一个就是"点了没反应"。
76. **大小王混着的一对是合法的** ⚠️ — `counts.size() == 2` 不能直接判非法：两个王的点数不同（
    `groupValue` 一个 15 一个 14），但 `大王+小王` 是合法的 `DOUBLE_JOKER`，靠"有几张大王"排在小王对和
    大王对之间。所以 `of()` 里先整段判"是不是全是王"，再进同点数分支。
77. **龙链是 `A 2 3 … Q K`，A 是分界线且不能落在中间** ⚠️ — "以A为分界线，无法跨越A"翻译成代码就是
    窗口里 A 的位置必须落在两端：`windowStart` 里 `aceAt = (13 - start) % 13`，只有 `aceAt == 0` 或
    `aceAt == span - 1` 才算合法窗口，于是 `Q K A`（起点 11）和 `K A`（起点 12）成立而 `K A 2` 不成立。
    同理"最小"是 `A 2 3`——A 在这条链上是**最小**的，和强度梯子上"大A最大"正好相反，两套顺序必须分开存
    （`ladderIndex` vs `value`）。`candidates()` 里对应地跳过 `span == 3 && start == 12`。
78. **4 收牌是一张表，不是"比大小"** ⚠️ — `isRecycle` 三个条件缺一不可：目标是同点数炸弹（
    `isRankBomb`，王炸不算）、`target.size() == played.size() + 1`、张张都是 4。单张 4 什么都收不了
    （`played.size() < 2`），所以"4取4"也成立（4张4 收 5张4）。它和 `beats` 是并列关系，`playableOn`
    才是二者取或；忘了这条会让 4 变成"最小的一张废牌"。
79. **规则可以脱离游戏离线自测** — `gradlew probeRules` 编译并运行 `tools/rule-probe`：它 `tryDetectVersion`
    + `Bootstrap.bootStrap()`，再用 `sun.misc.Unsafe.allocateInstance` 造一个 `ChartaMod` 桩（`getPlatform()`
    返回 `"Probe"`，`loadPlatformClass` 就会去找 `registry.ProbeModRegistry`）绕过 Fabric，最后断言整张
    规则表。两个坑：① 桩的构造函数要 new 出 packet manager，所以用 `allocateInstance` 跳过它，只反射写
    `ChartaMod.instance`；② 每个 registry 必须 `init()` 一遍 —— `MinecraftEntry.get()` 在 `set()` 之前
    **每次调用都重新 `supplier.get()`**，而未注册的 `DataComponentType` 是按引用比较的，不 init 的话
    `Card.rank()` 永远读回 BLANK，全部牌都变成同一张。

80. **一张牌的"脸"是牌组给的，不是牌本身给的** ⚠️ — Charta 渲染一张牌走的是
    `CardSlotWidget.getCardTexture` → `parent.getDeck().getCardTexture(card, glow)`，也就是拿这张牌去
    **牌组的 cards 列表**里按值找 `CardDefinition`；找不到就返回 `ChartaMod.MISSING_CARD` ——
    `CardImageUtils.EMPTY_CARD`，那块品红/黑的四象限棋盘。**Charta 自己 67 个牌组一张 joker 都没有**
    （`data/charta/decks/**` 全是 52 张），但它的卡面图里**有** `standard/{spades,clubs}_joker`，于是
    "大小王"只要不是来自本附属/`free` 那两个自带 joker 的牌组，就一定画成紫黑块：图片在、牌组的定义不
    在。修法是 `DeckJokerFaces` 往 `Deck.getCardTexture` / `getCardTranslatableKey` 各挂一个
    `@At("RETURN")` + `cancellable` 的注入，**只在 miss 时**把 joker 换成 Charta 自己的 joker 图
    （`DaaCards.BIG_JOKER_FACE`/`SMALL_JOKER_FACE`），牌组自己定义了 joker 就完全不动。两个附带坑：
    ① 目标方法是**模组方法**，`@Inject(remap = false)` + 裸方法名才编得过，否则 mixin AP 直接报
    `Unable to locate obfuscation mapping`（写全 descriptor 也一样）；
    ② 牌组没 joker 时 `DaaGame.doubleDeck()` 得先把大小王补进 `gameDeck`（`Card.create` 是
    `flipped=false`，而超类构造函数刚把整副牌翻面、`dealCards` 再翻回来，所以补进去的牌要手动
    `flip()` 一次），否则 52 张的包是 104 张、单牌梯子上最重要的两张根本不在牌桌上。
    离线验证：`gradlew probeDeck` 会拿一个 charta 原生牌组断言"它没有 joker、补进来的大小王能落到
    Charta 自带的 joker 图上"。

81. **术语：3 张相同是「蛋子」，4~6 张相同是「大蛋子」，都不是「炸」** ⚠️ — 口语里"炸"只是对这个
    家族的统称，界面上必须写蛋子 / 大蛋子。枚举常量跟着改名（`Kind.EGG`、`BIG_EGG_FOUR/FIVE/SIX`，
    顺序与 ordinal 一个都不能动，`tier` 就是 ordinal），lang 里三种大蛋子共用"大蛋子"一个名字，
    **张数靠出牌区已有的 `message.daa.zone_count`（`大蛋子 ×5`，只在超过 3 张时才加）区分**，所以 5 张
    和 4 张不会看起来一样。文档和 probe 的用例描述一起改，别只改 lang。

82. **大小王按"颜色"分，不是按花色名** ⚠️ — 一副真扑克的两张王唯一的区别就是**红/黑**，Charta 的卡面
    也是每种花色各一张 joker 图（`standard/{spades,hearts,clubs,diamonds}_joker`）。所以 **红桃/方块 =
    大王，黑桃/梅花 = 小王**，`DaaCards.isBigJoker`/`isSmallJoker` 只判"花色是不是红的"，不认具体花色；
    `BIG_JOKER_FACE` 用 `hearts_joker`（红）、`SMALL_JOKER_FACE` 用 `spades_joker`（黑）。凡是造王的地方
    都得跟着走：`DaaGame.JOKER_SUITS`、`debugCard`/`cheatCard` 的 52/53 号格（52 = 红王、53 = 黑王，两边
    必须镜像，坑 70）、`tools/rule-probe` 里造 `bigJoker`/`smallJoker` 的两行、以及
    `data/daa/decks/*.json` 里那两条 joker 定义的 suit/texture。**最容易错的是把 spades 当大王** ——
    Charta 的 spade joker 图是黑的，用错了大王就画成黑王。

83. **给 `GameOption<T>` 写自定义控件有两个包可见的坎** ⚠️ — ① `GameOption.consumer` 没有修饰符，是
    `dev.lucaargolo.charta.common.game.api.game` 的**包私有字段**：在 `dev.bridge.game` 里写
    `this.consumer = ...` 编不过（"consumer 在 GameOption 中不是公共的"）。要么把子类挪进那个包，要么
    完全别碰它 —— 走 `setValue(v)` / `set(v)`，基类自己会 `consumer.accept`。用按钮做控件时正确的做法是
    覆盖 `GameOption.Widget#render`（那是个**类**，不是泛型方法所在的位置），在每帧把
    `button.setMessage(...)` 刷一遍，这样"外部改了值"（选项屏的复位按钮）也能跟着动。
    ② 别用 `OnlyIn`：Charta 源码里 `import net.neoforged.api.distmarker.OnlyIn`，但附属的编译类路径上
    **没有** `net.neoforged.api.distmarker`，加上就是两个"程序包不存在"。
    ③ 自定义 option 必须**追加在选项列表末尾**：`setRawOptions` 是按位置写 `byte[]` 的，插到中间会让
    老存档的三个布尔值整体错位。

84. **"自己那一边在最下方"：手牌 + 铂牌 + 墩堆格子三个必须一起转，方位字母/聊天名不能转** ⚠️ — 四方位
    牌桌有两种"方位"：世界方位（谁坐哪张椅子、叫什么方向，`BridgeGame#compass`）和绘制方位
    （`viewCompass(seat, viewerSeat)`，把观察者放到南）。踩过三次，每次反馈一个漏掉的元素：
    ① 全用 viewCompass → **座位跟着人转**（「不要每个玩家看自己都是南玩家」）；② 只转手牌 → 手牌到下面
    而名字还挂原来的椅子上（「你名牌没转啊」）；③ 手牌+铂牌转了、墩堆没转 → 自己打出的牌跑到对面格
    （「出牌区也没转」）。
    正确切法一句话：**位置跟人走 = {手牌、铂牌、该家在墩堆里的格子}；命名不跟人走 = {方位字母、拍卖行、
    聊天、铂牌文案}**。所以 `BridgeScreen` 里 `compass()` 只给命名用，`handCompass()` 给手牌/铂牌/墩堆格；
    铂牌的**框用 handCompass、文案用 compass**；墩堆格子的字母必须靠 `seatAtView(cell, localSeat)`
    反查座位再取 `compass`（格子是屏幕位置，字母是世界方向，直接 `PILE_LETTERS[cell]` 会在旋转后指错人）。
    `BridgeMenu.seats()` 按本地座位轮转 `HAND_ORDER` 后本地玩家永远是槽 0、viewCompass 永远把槽 0 送回南；
    服务端/客户端都从同一个 `getCardPlayer()` 座位算，点击载荷不错位。

85. **庄家/明手挂到对面一边：叫牌里的"座位"只能有一个来源** ⚠️ — `Auction` 只存 call code，每个 call 的
    座位是**由位置推出来的**（`seatOf(i) = dealer + i`），客户端正是靠这个身份用一串裸 code 重建同一场
    叫牌。而 `Auction.call(call, seat)` 早期**只查 `isLegal`、从不校验 seat 是不是 `nextSeat()`**：PASS 永远
    "合法"，定约也只比大小 —— 于是座位是**被记住的，不是被验证的**。只要有一次回调迟到或重复（
    `CompletableFuture` 的 `thenAccept` 没有任何"这次是不是还轮到我"的保证），这次 call 就记到别人座位上，
    它后面**每一个** call 的位置身份都错一位；而搭档是奇偶判断（`floorMod(seat, 2)`），错一位 = 后面每个
    call 的敌我整体翻面 → 庄家落到对方那一边，症状就是「明明不是我叫的庄，却是我主打、队友明手」。
    闭环要四处一起改：① `awaitCall()` 在**注册时**捕获 `bidderSeat` 交给 `applyCall`（`awaitCard` 同理捕获
    `actorSeat`），回调里不再读活字段；② `applyCall` 的座位**取自 `auction.nextSeat()`**，和 `declarer()`
    读回来的是同一个表达式；③ `Auction.call` 真的校验 `seat == nextSeat()` 并拒收；④ 加 `turnToken`
    —— 每次注册自增、`startGame` 再自增，回调先比对 token，**这轮已经结束的回调整个丢掉**（不碰 play future、
    不碰 `awaitingPlay`、不碰叫牌）。原因是 `thenAccept` 不保证"一轮只触发一次"：同一个 future 上挂两个
    监听者会跑两遍循环，迟到的完成也会再跑一遍；而同一 future 挂两个监听者时 token 让**最新那次注册**生效，
    等于天然去重。`BridgeGame` 也改用 `setCurrentPlayer(auction.nextSeat())`（人不满 4 时 `seatFrom`
    按人数取模，跟 4 座循环对不上）。
    `.scratch/auction` 里补了"奇偶不变量"和"错座位被拒收"两组断言（35 项）。
    顺带一条经验：**"被记了一手没叫过的牌"只能对着 call 列表数座位**，桌面上那行叫牌记录是转过、缩写、
    还可能滚出视野的。所以 `applyCall` 留了一句 `call <call> from seat <n> (<name>)` 的 INFO，叫牌结束再补一句
    `auction closed: dealer=… declarer=… dummy=… calls=[座位:叫品 …]`。

86. **双明手解算要"快照发牌 + 后台线程 + 回到主线程播报"三件套** ⚠️ — 双明手（双方都按最优打）的结果是    对**发牌**解出来的，不是对打到最后的那几张残牌，所以必须在 `beginAuction()`（牌刚好发完、还没人出牌）
    把四家手牌按 `bit = suit*13 + (rank-2)` 存成一份 `long` 快照（`rememberDeal()`），因为**打完之后四只手牌
    全空了**，`getPlayerHand` 里什么都问不出来。三个配套的点：
    * **`rank` 要把 A 挪到 14**：注册表里 A 的 `ordinal()` 是 1、2 是 2，直接用 ordinal 会让 A 变成最小的牌
      （和坑 30/58 一个性质，只是换了个地方）。
    * **必须离开服务端线程**：一次全副解算 1~9 秒（朴素 alpha-beta，没有 DDS 那种 quick-trick 引擎），而且
      `BridgeDoubleDummy` 的**全部状态都是 static**（含 256MB 置换表，见坑 88），所以只能挂在**单线程**
      `ExecutorService` 上串行跑，并发两次会互相踩。跑完用 `server.execute(...)` 回到主线程再 `table(...)`。
    * **播报要的是"捕获值"不是"活字段"**：结果回来时牌桌**多半已经不是这一副了**，而且 `isGameOver` 一置真，
      `CardTableBlockEntity.serverTick` 下一 tick 就把 `game.tick()` 停掉 —— 所以"在 `tick()` 里轮询"是错的，
      只能靠 future 回调。合同 / 庄家 / 所需墩数一律在提交任务前读出来交给 lambda，回调里不再碰活字段。
    * **解算要在"叫牌结束"就发车，不是"打完"** ⚠️：播报时机在 `announceResult()`，但**开算时机必须在
      `concludeAuction()`**（`phase = Phase.PLAY` 之后、首攻之前）。将牌、首攻人、庄家方位、所需墩数这四项
      输入在叫牌结束的那一瞬间才全部确定，早于此（`beginAuction()`）将牌还是未知；而把开算放在 `announceResult()`
      就是让全桌盯着已经摊好的牌墩干等 1~9 秒。放对了位置，搜索就藏在**整副牌的 13 墩打牌时间里**跑，
      打完一般早就好了：`collectDoubleDummy()` 先 `isDone()` 直接同步播报，没跑完才 `thenAccept` 挂上去等它，
      这样 `RESULT` 阶段的 80 tick 余韵也不浪费。
    * **"晚回来的那副"不用加 token 去掐**：一开始加了 `doubleDummyToken`、晚回来的答案如果发现牌桌已经开了
      下一副就咽掉。实测这个 token **在线上永远等于 1**：`CardTableBlock` 在 `game.isGameOver()` 之后走的是
      `TableScreenPayload → CardTableBlockEntity.startGame()` → `type.create(...)`，也就是**每副牌都是一个新的
      `BridgeGame` 实例**，`startGame()` 只在实例刚建好时被调一次；上一副的 future 回调持有的是它自己那份捕获值
      和新实例无关，`players` 也是同一批实体，所以晚回来的话照样能发、也该发。只有"同一个实例自己循环开牌"的调
      试牌桌会撞上，而那里咽掉反而丢信息。于是删掉了。
    * **"是否被节点上限截断"要跟着答案一起回来**：`lastWasCapped()` 是 static，下一副的解算会把它覆盖掉，
      所以不能等回调里再去读 `BridgeDoubleDummy.lastWasCapped()`（那时可能已经在跑别人的牌了），要把
      `tricks` 和 `capped` 打包成一个 `record` 由 future 带回（`DoubleDummyAnswer`）。
    另外：`table()` 只发给 `ServerPlayer`，客户端那份镜像上 `findServer()` 为 null，所以**同一段代码在客户端
    跑等于白跑**，要拿 `findServer() == null` 直接 return；选项本身是第 5 个 `GameOption`，**只能加在末尾**
    （`setRawOptions` 按位置灌字节，坑 68/83）。

87. **解算器的"对不对"只有一个判据：a + b == 13** ⚠️ — 同一局面下双方的最优墩数之和必然等于剩余墩数，这是
    零和，也是最省事的自检：任何一处偷偷剪掉了最优线要用的着法，都会以"和不是 13"暴露出来，而不是表现为
    一步看起来有点傻的牌。四条相关经验：
    * **节点上限一破，结果不是"不精确"而是"错"**：`solve()` 命中 `NODE_CAP` 就退回当前上下界，和立刻不是
      13（实测把上限从 20M 提到 400M，两副原本 `sum=11` 的牌都变回 13）。所以上限要按**尾部**定而不是按
      均值，并且 `lastWasCapped()` 让上层能说"这是近似值"。
    * **等价牌可以整批不生成，但"等价"的判据是全场的牌，不是自己手里那几张** ⚠️：两张牌之间**没有别的牌
      还在场**就是同一张牌（rank 唯一 ⇒ 谁也比不过/比得过其中一张就必然对另一张一样），只留这一类里的
      **最高**那张（`collapse(pool, live)`）。这里连踩了两个坑：
      ① 只在**自己那门牌的池子**里找"相邻"（`c+1 ∈ pool`）是**漏**的：手里 5、7 而全场没有 6 时两张本就是
         同一张牌，却两张都生成；
      ② 反过来的"只看自己手里的下一张"（`nxt = pool` 里 c 上面那张）又是**过**的：全场明明有别人的 6
         夹在 5 和 7 之间（5 输给 6、7 赢 6，行为不同），却把 5 折掉了 —— 症状是**答案直接变小**
         （`t2L2` 10 → 7），而且快得可疑。
      正确写法是 `live = 四家手牌 ∪ {当前赢墩的那张牌}`：赢墩那张牌**已经离手、不在 hand0..3 里**，但它
      是每一次跟牌都要拿来比大小的基准，必须算"还在场"，否则 5/6/7 这种"一垫一盖"的两张会被折成一张。
      这一条与 DDS 的做法一致（Haglund & Hein《Search Algorithms for a Bridge Double Dummy Solver》：
      "For cards that are equivalent (e.g. AK), only the card with **highest** rank is generated. Card
      equivalence is reanalyzed **after each trick**"，出处是 Ginsberg 的 partition search）。
      实测 `t4L3` 由 104M 节点 / 28s 降到 89M / 22s，且 40 项零和断言全绿。
    * **走子顺序不能想当然；而且 `-Dxxx` 不带 `=true` 是 false** ⚠️：试过"二手低 / 三手高 / 末家看同伴是否
      已赢"这套 SLOW；这次又试了 killer + history 表（在 TT 命中着法之外，把"上一层剪枝的那张牌"和
      "历史剪枝榜"提到最前），结果 killer 或 history **任一打开都是 6~8 倍回退**（`t2L2` 45M → 287M/395M），
      只有"把置换表里的最佳着法提到最前"是中性（≈无序）。所以最终只留 TT 那一手提。**改顺序必须用
      `.scratch/dd` 的 `Bench`/`Validate` 量，别凭桥牌直觉改**，也别一次开两个变量。
      顺带：`Boolean.getBoolean("dd.noorder")` 对 `-Ddd.noorder`（空值）返回 **false**，写成 `-Ddd.noorder`
      的 A/B 其实两组是同一条代码路径 —— 一度让我以为"改了一点没变"。
    离线工具都留在 `.scratch/dd`：`Main`（手验局面 + 零和 + 计时 + 朴素对拍）、`Brute`（真值）、
    `WindowCheck`（上下界必须夹住真值）、`Validate`（全副×全花色×四家首攻的零和）、`Bench`（节点/耗时）、
    `exp/One`（对单个指定局面跑一次并打印节点/命中次数，改搜索时用它做 A/B）、`exp/Hard`（对最费的局面集
    计时，用来定 `NODE_CAP`）、`ab.sh`（把解算器 + 全部探针 + 一个 exp 目标编起来跑，`-D/-X` 走 JVM）。

88. **置换表开小了是"节点数 × 耗时"双输，而它比任何搜索技巧都值钱** ⚠️ — 解算器最大的一个杠杆不是剪枝、
    不是走子顺序，而是 `TABLE_BITS`（`-Ddd.ttbits`，默认 `24`）。原先随手定的 `21`（2^21 × 16B = 32MB）
    让搜索在最坏局面上要 89M 节点 / 14.9 秒，把它加到 `24`（256MB）变成 20M 节点 / 3.9 秒 —— **快 3.8 倍，
    而且节点数还掉了 4.5 倍**，因为"表小"不只是探针慢，而是**每次冲突都白扔一整棵子树**：
    * 尺度是这样（最坏局面 `One 0 4 3`）：2^21 = 14869ms / 89M 节点，2^22 = 8584ms / 49M，2^23 = 7613ms /
      42M，2^24 = 3891ms / 20M，2^25 = 3866ms / 20M，2^26 = 4027ms，2^27 = 4684ms。**2^24 就是拐点**，
      再大只加内存不加速度（表早就比缓存大几个数量级，探针本来就是一次内存往返，变大只会让 TLB/页更差）。
    * 反过来往下缩要出事：2^20 → 181M 节点 / 26.5s，2^19 → 撞 `NODE_CAP`（300M）且**答案直接变小**
      （10 → 9），2^18 → 答案 7。所以"缩表省内存"不是免费的，会一直缩到破掉 a+b=13（坑 87）。
    * **做 A/B 时先只动这一个旋钮**：`./ab.sh One 0 4 3 300000000 -Ddd.ttbits=NN`。另外表是
      `new long[TABLE_SIZE*2]` 的 `static final`，2^24 就是 256MB，只在**第一次解算**时随类初始化分配 ——
      双明手选项关着的时候这个类根本不会被碰，所以不占。
    * 同一轮里还量掉两个"看着该有用"的东西，都因为**节点数一模一样**而可以放心删：
      ① 把 `keyOf` 里"赢墩那张牌的说明"从"每次出牌在子节点里增量维护 `WIN_KEY`"改回"在 `keyOf` 里现算
      `bitCount(场上的牌 ∩ 该门 ∩ 那张牌以下)`"，14676ms vs 14869ms —— 增量维护是**每张着法** 5 个分支
      + 1 次写数组，而现算是**每个节点** 1 次 bitCount，节点数一样时后者更划算；
      ② 把置换表从两个并行数组 `TABLE_KEY[] + TABLE_VALUE[]` 改成一个 `TABLE[]` 交错存 `[key, meta]`
      （条目数与总字节数都不变，2^21 / 32MB），17044ms → 14723ms：并行数组下一次探针要读**两条不相干
      的缓存行**，交错后 key 和 value 落在同一条上。

89. **`runClient` 要"无人值守"得自己开世界，而且别用标题界面当判据** ⚠️ — 调试牌桌靠
    `BRIDGE_DEBUG_SCREEN=1` 一启动就摊牌，但它挂在 `if (minecraft.player == null || minecraft.level == null)
    return;` 上，**没有世界就整段不跑**，而且失败的样子和"崩了"一模一样（日志停在 `Charta: Loaded 20
    markdowns` 之后再无输出）。两个坑叠在一起：
    * `build.gradle` 里 `programArgs('--quickPlaySingleplayer', 'New World')` 的**空格会被拆**：loom 把
      programArgs 拼成一条命令串，JVM 再按空格切，于是游戏收到的是 `--quickPlaySingleplayer New World`
      三个参数，levelId 取到 `"New"`，存档却叫 `New World` → 查不到 → 停在标题界面（`QuickPlay` 那条
      "World not found" 还是 WARN，容易被刷掉）。改成不带空格的 `'New'`。
    * 就算名字对上了，`--quickPlaySingleplayer` **只在存档已存在时生效**（首次没有就是没有），所以
      `BridgeDebug.joinWorld()` 得自己 `createWorldOpenFlows().openWorld(...)`。**判据千万别写
      `screen instanceof TitleScreen`**：启动后那几秒 screen 其实是 `GenericMessageScreen`（"Loading..."）
      外加一个 `LoadingOverlay`，`instanceof TitleScreen` 一次都不成立 —— 本轮就在这里白跑了三轮
      `runClient`。正确写法是"等够 N tick（100）且 `minecraft.getOverlay() == null` 才开"，并且**顺手每
      100 tick 打一行 `screen=… overlay=…`**，下一次一眼就能看出卡在哪一层。失败时清掉标志位重试，
      别让它静悄悄地停在菜单上。
    * 自测世界放 `runs/client/saves/New`：`runs/` 被 gitignore，但**想留世界就只留 `saves`**，其余
      （logs/config/downloads/mods）删掉（4.2）。

## 8. 变更记录

- **2026-10-08 桥牌：解算器性能优化，最坏局面 20.2s → 3.9s**（需求「你再优化一下代码，提高性能」）。只动
  `BridgeDoubleDummy` 和它的离线 A/B 工具，**玩法、播报文案、选项布局一概不变**，全部改动都以"节点数是否
  逐位相同"作安全网。按测得收益排序：
  ① **置换表 2^21 → 2^24**（32MB → 256MB）：这是唯一的大头，`exp/One` 的最坏局面 **20197ms / 89M 节点 →
  3891ms / 20M 节点**（5.2 倍），`exp/Hard` 20 局面最坏 21090ms → 8944ms、总耗时 169616ms → 74764ms
  （2.3 倍），总节点 623M → 374M。拐点在 2^24，2^25 起只加内存不加速；往下缩会一路缩到破 a+b=13
  （2^19 就撞上限且答案变小），全部尺度记在坑 88。
  ② **等价牌折叠的"全场 pattern"改成增量维护**（`PATTERN`/`PATTERN_N` + `pushPattern`/`seedPattern`，
  替换每节点重扫四家手牌的 `canonicalPattern()`）：`One` 20251ms → 17044ms，节点数逐位相同。中途写错过
  一次下标（用 `card % 13` 而不是全局位号），症状是节点数塌到 1.2M、答案 10 → 9。
  ③ **置换表两数组合一个交错数组**（`TABLE[]` 存 `[key, meta]`）：17044ms → 14723ms。
  ④ 量掉两个"看着有用"的：`keyOf` 里赢墩牌的说明从"每张着法增量维护"改回"每节点现算 bitCount"
  （14869ms → 14676ms，增量维护是按着法算而现算是按节点算），以及 TT key 从 `lookup`/`store` 各建一次
  改成每节点建一次后传参。两者节点数都逐位相同，所以删得放心。
  用 `.scratch/dd/src/Validate`（3 副 × 5 将牌 × 4 首攻 = 60 项）复核 **bad=0，零和全绿**，
  `gradlew build` 只剩既有的 `@Accessor` 警告。`ab.sh` 是本轮新加的 A/B 驱动（解算器 + 全部探针 + 一个
  exp 目标一次编完跑掉）。
  顺带把调试牌桌修成能**无人值守**跑：`build.gradle` 的 `--quickPlaySingleplayer 'New World'` 里那个空格
  会被 JVM 拆成两个参数，于是 levelId 变成 "New"、查不到就停在标题界面（改成 `'New'`）；而且
  `--quickPlaySingleplayer` 只在存档已存在时才生效，所以 `BridgeDebug.joinWorld()` 自己也开世界 ——
  **判据不能写成 `screen instanceof TitleScreen`**，实测启动后那几秒的 screen 是 `GenericMessageScreen`
  ＋ `LoadingOverlay`，于是改成"等 100 tick 且 `getOverlay() == null` 才开"，并每 100 tick 打一行
  `screen=/overlay=` 方便下次定位。实测 `BRIDGE_DEBUG_SCREEN=1 runClient` 连打 **8/8 副、8 条双明手播报
  （1 条来自 `bridge-double-dummy` 线程＝异步路径，7 条同步），无异常、无 OOM**。

- **2026-10-08 桥牌：双明手选项（默认关闭）+ 打完后把双明手结果发聊天栏**（需求「加入一个选项模式:双明手.
  默认关闭；然后在正常对局后在聊天栏向所有玩家发送双明打结果」；随后又提「不能在对局进行中解算，然后再在
  对局结束后播报吗」）。新增第 5 个游戏选项 `rule.bridge.double_dummy`（默认关，加在选项列表**末尾**，坑
  68/83）。打开后每副牌做一次**双明手复盘**：`rememberDeal()` 在发牌刚发完时把四家手牌存成 `long` 快照
  （打完之后手牌是空的，只能存），`beginDoubleDummy()` 在**叫牌结束（`concludeAuction()`）那一刻**就把
  「发牌 + 将牌 + 首攻人 + 庄家方位」交给 `BridgeDoubleDummy` 在**后台单线程**解出「双方都打最优时主打方
  最多能拿几墩」—— 放在这里而不是 `announceResult()`，是为了让 1~25 秒的搜索藏在整副牌的 13 墩打牌时间里
  跑，而不是让全桌盯着 80 tick 的余韵干等。  `announceResult()` 里 `collectDoubleDummy()` 取答案：`isDone()`
  就直接同步播，没跑完才 `thenAccept` 挂上去（捕获值随回调带走，晚回来照发）。回到主线程
  `table(...)` 把结果播给全桌：「【双明手】定约 X 由 Y 主打：双方都打最优的话主打方最多只能拿 N 墩，定约需要
  M 墩——本该成约 / 本该宕 K 墩（本局实得 Z 墩）」，同时打一行 INFO 日志（调试牌桌上没有 ServerPlayer，
  `table()` 写不到任何地方，日志是唯一能看见的）。`tricks` 与 `capped` 打包成 `DoubleDummyAnswer` 让
  future 带回（`lastWasCapped()` 是 static，回调里再读会被下一副覆盖）。解算不动牌局、不在服务端线程上、
  客户端镜像靠 `BridgeGame.solveOnClient`（只有调试牌桌打开）才跑，全部捕获值而非活字段（坑 86）。
  调试牌桌顺手改成**连打 8 副**（`BridgeDebug.BOARDS`，每副间隔 40 tick）并在选项里**打开双明手**，
  这样"解算没跑完就走异步"和"同一实例再发牌"两条路都能被跑到。顺带把解算器调到能用：`fill()` 的等价牌折叠按**全场还在
  的牌 + 当前赢墩那张牌**判据重写（坑 87，原先只按自己手里相邻，漏折；中途还写歪成"只看自己手里下一张"
  导致答案变小），killer/history 表加上又被量掉、只留 TT 命中着法提前，A 的 `ordinal()` 挪到 14。
  `.scratch/dd/src/Validate.java` 是新加的全副零和校验（4 副 × 5 将牌 × 4 首攻 = 80 项 bad=0），`exp/One`、
  `exp/Hard` 是这次改搜索时用的单局面 A/B 和最坏集计时工具。lang / 两语言说明页 / README 同步，
  版本 1.1.1 → 1.1.2。

- **2026-10-05 桥牌：修掉"不是我叫的庄却是我主打、队友明手"**（反馈「修一下明明不是我叫的庄，但是是我
  主打，队友明手的 bug」）。根因是**叫牌座位可以记错位**（坑 85）：`Auction.call` 不校验说话座位，回调里
  又读活字段，一次错位就让后面每个 call 的敌我奇偶整体翻面，庄家于是落到对面那一边（队友自然成了明手）。
  改成座位只有一个来源 —— `applyCall` 从 `auction.nextSeat()` 取、`awaitCall`/`awaitCard` 注册时捕获、
  `Auction.call` 拒收错座位的 call，再加 `turnToken` 让"这轮已经结束的回调"整个失效（天然去重：同一
  future 上只有最新一次注册生效）；叫牌合法性检查保持不变（`isLegal` 仍是唯一裁决）。每次 call 加 INFO
  日志、叫牌结束加汇总行，方便下次直接对数。版本 1.1.0 → 1.1.1。

- **2026-10-05 桥牌：人机强度可调 + 视角转正 + 防家墩数修好**（反馈「把人机强度提高，然后在设置里可以
  调节人机的强度」「修复防家墩数显示异常的 bug」「在自己名字旁显示自己的大牌点」「每个玩家的界面都是
  自己在屏幕下方」）。① **人机强度**：新增第 4 个游戏选项 `rule.bridge.bot_strength`（简单/普通/困难，
  默认普通，点击循环），落在 `BridgeStrength`；因为 Charta 只有 `Bool`/`Number`，自定义了
  `BridgeStrength.Option`（带名字的循环按钮）——**`GameOption.consumer` 是包私有的**，附属要么把子类
  放进 `dev.lucaargolo.charta.common.game.api.game`，要么只能走 `setValue`，见坑 83。强度跟着
  `BridgeBot` 走（不是查全局选项），客户端按 `getRawOptions()` 重建机器人时也对得上。难度实现：
  `BridgePlay`（普通＝座位感知规则：二手低、三手高、最便宜能赢的牌、将吃只在将牌真能守住时才；
  困难＝对该墩做 rollout，用「最便宜的赢牌」模型把剩下三家的应牌走完再评估），`BridgeBidding` 新增
  形状/快速赢墩/配合点评估与满贯、惩罚性加倍门槛。**简单**分支逐字保持旧行为，旧档读到的字节 0 即简单。
  ② **防家墩数**：真正的 bug 在 `BridgeScreen` —— 进度行先把 `ours/theirs` 按本地座位换过位置，文案却
  仍写死「庄家 x/y 墩，防家 z 墩」，于是**防家看到的两个数字正好互换**。改成视角相对的「我方/对方」，
  并在 `BridgeGame` 收出 `getDefenderTricks()`／`sideTricks()` 加 `players.size() < 4` 的守卫（2 人时
  `+2` 会绕回同一个座位、把同一人的墩数算两遍）。③ **自己的大牌点**：本地铭牌后追加
  `seat.bridge.points`，走 `BridgeMenu#getLocalPoints`（读真手牌：注目牌会经 `getCensoredHand` 拿到别的
  玩家的牌背）。④ **自己的手牌在最下方**：新增 `BridgeGame#viewCompass(seat, viewerSeat)`，把世界方位环
  按观察者旋转到「观察者=南」，`BridgeMenu.seats()` 按本地座位轮转 `HAND_ORDER`，于是本地玩家永远是
  槽 0、永远被画在南边那条大扇上。**位置跟人转、名字不跟人转**（三次反馈修正，见坑 84）：手牌、铂牌、
  墩堆里该家的格子三样一起走 `handCompass`，但方位字母 / 聊天 / 拍卖行仍读世界方位，墩堆格子的字母靠
  `seatAtView` 反查座位再取名。`BridgeScreen` 拆成 `compass()`（只管命名）与 `handCompass()`（手牌/铂牌/
  墩堆格）两套，`BridgeLayout.apply` 只对手牌槽用 `viewCompass`。牌槽索引两边一致（都从同一个本地座位
  算），点击载荷不错位。

- **2026-10-05 打大A：蛋子 / 大蛋子正名 + 大小王按红黑分**（反馈「3个相同的是蛋子，不是炸；4~6个相同的
  是大蛋子，不是炸」「用黑的王当小王，红的当大王」）。① 术语与界面：`3炸/4炸/5炸/6炸` → `蛋子` /
  `大蛋子`（lang、中英说明页、README、probe 用例描述全改），枚举常量改名
  `THREE_BOMB→EGG`、`FOUR/FIVE/SIX_BOMB→BIG_EGG_FOUR/FIVE/SIX`（声明顺序不动，仍是梯子，坑 81）；
  三种大蛋子共用一个名字，靠出牌区已有的 `×张数` 后缀区分。② 大小王改成**红王 = 大王、黑王 = 小王**
  （坑 82）：`isBigJoker`/`isSmallJoker` 只判红黑花色，牌组 JSON 的两条 joker 定义改为
  `hearts_joker` / `spades_joker`，F8 后门的 52/53 号格、`JOKER_SUITS`、两个 probe 里的造牌同步，
  `BIG_JOKER_FACE`/`SMALL_JOKER_FACE` 换成对应颜色的图（红桃 joker / 黑桃 joker）。`probeRules`
  109 条断言、`probeDeck` 两个牌组 0 broken 仍全绿。

- **2026-10-05 打大A：大小王补牌 + 让没有 joker 定义的牌组也画得出大小王**（反馈「charta 原项目不是有
  大小王的材质吗，为什么你这里是紫黑块」）。根因不是材质缺失：Charta 的 `images/card/standard/*_joker`
  一直都在（`Loaded 1215 card images`），是**卡面按牌组定义查**，而 Charta 自带的 52 张牌组一条 joker
  定义都没有 → `Deck.getCardTexture` 落到 `charta:missing_card`（品红/黑棋盘）。① 新增 mixin
  `DeckJokerFaces`（common），只在查不到时给 joker 补 Charta 自己的 joker 图与 `card.daa.bigJoker` /
  `smallJoker` 名字，自带 joker 的牌组（本附属两副、`free`）完全不受影响（坑 80）。② `DaaGame.doubleDeck()`
  在牌组没有 joker 时先补 `大王`+`小王` 再翻倍 —— 52 张的包现在是完整的 108 张，单牌梯子
  （`大A > 大王 > 小王 > 3 …`）在任意牌组上都成立。③ `DaaCards` 增 `BIG_JOKER_FACE`/`SMALL_JOKER_FACE`/
  `jokerFace`。④ `gradlew probeDeck` 增两处断言：本附属两副牌 4 张 joker 全部命中真实卡面（0 broken），
  以及一个 charta 原生牌组"无 joker 定义 → 补进来的大小王落到 charta joker 图"。⑤ 启动日志说明补牌。
  README 同步，`version` → 1.6.0。

- **2026-10-05 打大A：整张牌型表照抄 + 修掉"8张K=大蛋子"**（反馈整段牌型/克制/4收牌规则，以及
  「放8张k显示的不是真8而是大蛋子」）。`Combo.Kind` 重写成需求里那张 16 级梯子（双大A > 四王 > 三王 >
  真八 > 真七 > 假八 > 连珠蛋 > 双王 > 6张大蛋子 > 5张大蛋子 > 4张大蛋子 > 蛋子 > 双龙 > 单龙 > 对子 > 单牌），删掉
  `BIG_BOMB`（坑 72、73）；**真七 / 真八改成任意点数**，这是本次报的 bug；单牌改用 `DaaCards.value`
  取 key，修掉"单大A 被单大王管"（坑 74）；新增 `Combo.synced` 让客户端预览的 flag 不再丢（坑 75）；
  大小王混花色的对子放行（坑 76）。龙链定为 `A 2 3 … Q K` 且 A 不准在中间，`windowStart` 与
  `candidates()` 各自跳过非法窗口（坑 77）；`isRecycle` 落成需求里那张 4 收牌表，单 4 不收、王炸不收
  （坑 78）；三个门槛（单王 / 双龙 = 4张大蛋子，单大A = 5张大蛋子）进 `beats`。新增 `gradlew probeRules` +
  `tools/rule-probe`，109 条断言离线跑完整个规则表（坑 79）。lang（`pattern.daa.three_bomb` … 取代
  `triple`/`big_bomb`）、中英说明页、README 同步，`version` → 1.5.0。

- **2026-10-05 打大A：整理 / F8 后门 / 出牌时长 / 叫A公告栏 / 发牌减速**（反馈「一键整理手牌；F8 拿
  任意牌；发牌降50%；200,85~425,125 醒目显示谁叫A；可设置出牌时长并在信息行和各出牌区倒计时」）。
  ① `DEAL_INTERVAL` 3→6（每张 6 tick，108 张约 32 秒），亮A/反A 窗口 `CALL_WINDOW` 不动，说话仍追加
  20 tick。② `DaaActionPayload` 加 `SORT`/`GIVE`/`TAKE`，`DaaGame.sortHandNow`（整理，`R` 键 + 新
  `BTN_SORT` 按钮，坑 66）、`debugGive`/`debugTake`（按 `rank*suits+suit` 造牌塞/抽手牌，坑 70）。
  ③ 新选项 `TURN_TIME`（默认 30 秒，0 关）＋ `SYNC_TURN_TICKS`；`DaaGame.tick()` 覆写 `Game.tick()`
  接管人类回合，倒计时同步到回合信息行与该玩家出牌区（坑 67、68）。④ 新布局元素 `CALL_BANNER`
  (200,85,225x40)，金红大字显示"谁亮A/反A/明打"，纯读现有镜像字段、发完牌再留 6 秒，画在
  `super.render` 之后（坑 69、71），`VERSION` 仍为 4。⑤ `DaaScreen` 加 F8 牌表 overlay（左键拿/右键
  收回，手牌已有的画金边）、`R` 键整理、`Esc` 先关面板。lang/说明页/README/本节同步。

- **2026-10-05 打大A：亮A / 反A / 兜底——整段规则照抄**（反馈整段"1. 亮A 2. 反A 3. 兜底"规则）。
  `trump` 不再抽：谁先亮 A 谁定花色，`callBigA(player, claim)` 一个入口验证三种 claim（亮单A/
  亮双A/反A/明打，claim 复用 `CALL` 的 index，坑 60-63）；反A 用对子**改判**花色并清 `subASeat` ⇒ 1v4，
  一局只反一次；`beginPlay` 兜底查红桃A（两人各一张 = 2v3，一人两张 = 1v4，都不亮才算兜底，坑 60）。
  新增 `SYNC_ROBBED` / `SYNC_DOUBLE`，`CALL_WINDOW` 40→60、说话时再往队列追加 20 tick（坑 64-65）。UI：`BTN_CALL` 一个按钮按手里
  的牌自动改名（亮A / 亮双A / 反A / 明打），**点手里那张 A 也能亮**（客户端 `claimFor` 读 hovered 手牌槽
  + `hoveredCardId`，坑 62），发牌字幕三行改成"进度 / 大A是否已亮 / 你能说什么"，玩家牌上多一个
  【反A】/【已亮】标签，`DaaMenu` 收紧成 `localAces`/`localPairSuit`/`canName`/`canDouble`/`canRob`/
  `canReveal`/`callAction`。`rule.daa.reveal_sub_a` 语义改成"队友默认明打"，lang/说明页/README 同步。

- **2026-10-05 打大A：发牌即排序，最左最大**（反馈「发牌的时候按照从小到大的顺序排好,左边最大」）。
  修掉强度表直接拿 `rank().ordinal()` 当点数（坑 58，K 会压 A、J/Q 与 A/2 同级），强度现在是 1..16
  无重号的连续阶梯；`DaaCards.sortKey` 换成 `DaaCards.handOrder(trump)`（强弱降序 + 同强度按
  `Suits.STANDARD` 升序，Charta 扇面 index 0 就是最左，所以"降序"= 最左最大）。`DaaGame` 每发一张就
  `sortHand`、回手统一走新的 `returnToHand`（坑 59）；`DaaAi` 里写死的 `< 11` 改成 `< DaaCards.V_ACE`；
  说明页两种语言改成"从第一张起就是排好的，最大的在最左边"。

- **2026-10-05 打大A：两副牌 + 出牌区实体化**（反馈「打大A是两副扑克 / 别人的出牌区也要实体化 / 撤回要一张一张 /
  只有轮到他时他上一次出的牌才清空」）。`DaaGame` 构造里 `doubleDeck()`（坑 57）；`DaaMenu` 把**五个**出牌区
  都做成 `CardSlot`（`ZONE_BASE=6`，`zoneSlot(ring)`，跟在手牌槽后面 add，坑 54），`ZoneSlot` 改覆写
  `removeAll()`（坑 53）并按回合门禁（坑 52）；清扫只留在 `beginTurn()`，`applyPass`/`清空` 走 `unstage()`
  回手，四收/整轮收牌时顺手清对应出牌区。`DaaScreen` 删掉自画的 `drawZoneCards`/`drawCard`/`renderVerdict`，
  牌全交回 Charta widget（坑 24），字幕区高 11px 留白（坑 55），出牌区 132x46 → 132x64、`VERSION` 4、中间状态栏
  收窄让位。新增 `data/daa/decks/big_a.json`（108 张 = 54×2，自带大小王，每张都是两份，走的是 `doubleDeck()` 的
  跳过分支，用现成的 `deck.daa.double` /
  `card.daa.bigJoker` / `card.daa.smallJoker` 键）。

- **2026-10-05 打大A：出牌交互改成照抄 Charta 自己的点击逻辑**（反馈"你别再自己写了"）。手牌槽的
  `GameSlot.removeAll()` 返回 false、新增本地出牌区 `CardSlot`（`ZoneSlot` 只覆写 `canInsertCard` /
  `canRemoveCard` / `isEmpty`），`DaaScreen` 里那份自研的 `dragIndices` / `staged` / 按下-拖动判定
  整段删掉，改由 `CardContainerSlotClickPayload` 承担（坑 49）。同时删掉 `SYNC_SELECT_*` 与
  `DaaActionPayload.TOGGLE`，`PLAY` 改成读暂存区。顺带修掉 `syncValue` 区间吞值（坑 50）与
  `getBestPlay` / `applyPlay` 在真人自动出牌下的丢牌（坑 51）。
- **2026-09-30 四家手牌加长、西东外靠**（反馈"每张牌露出太少"）。横排盒宽 160→300 并按屏幕居中；
  竖排盒高 160→224（先松开坑 47 的 10px 上限），露出量 8.96→14.3px；西/东锚点改成按空条推算的
  `columnLeft()`，左右边距与到扇面的间隙都是 66px。`layout.version` 升到 5 并把作者存档里的
  5 个非零元素搬进 `defaults()` 成为出厂默认（坑 48）。
- **2026-09-30 发布前整理**（0.1.0 → 1.0.0）：补元数据（坑 46）、删死代码、
  `BridgeScreen` 加文案缓存、`BridgeFrame` 按窗口缓存、偏移表换数组、F9 元素名走语言键、
  客户端 mixin 移进 `client` 块（坑 44）、修 mojibake（坑 43）、出牌/说明页措辞改成"坐哪边就是哪边"。
- **2026-09-30 四条修补**：补 `seat.bridge.south`；`renderBottomBar` 改成空（底栏黑带比手牌宽，
  两端像两根空条）；明手只有庄家能动（坑 40）；修明手手牌变牌背（坑 39）。
- **2026-09-30 叫牌结束不再瞬间关面板** — 盯 `phase` 的 AUCTION→PLAY 边沿（没有同步字段，只能看边沿），
  多留 2 秒显示定约、主打人、有局/无局，四家皆过显示流局。
- **2026-09-30 方位改成按真人实际坐的椅子分配**（坑 42），并修结果面板的「加倍」方框（坑 41）。
- **2026-09-30 修掉"多人下每个玩家视角里的南家都是自己"** — 方位改成座位索引（坑 38），
  新增 `SOUTH_PLATE`，屏幕不再随视角旋转。
- **2026-09-30 UI 改成任何窗口尺寸都完整显示** — 固定 640x360 设计帧（坑 37），新增
  `BridgeFrame` 和两条带守卫的 mixin。
- **2026-09-30 修掉鼠标悬停手牌掉帧**（120→35fps，坑 36），绘制从约 1400 张降到 78 张。
- **2026-09-30 叫牌记录改成带轮次表头的网格**（坑 35），面板 150x186 → 168x192；叫牌弹窗加高 170→186（坑 34）。
- **2026-09-30 花色字形反复调**：12 → 9 → 10px（点采样丢尖端，改游程分配）→ **彻底不缩**，
  格子直接开到 13x12 原像素拷贝（坑 28/28b），`SUIT_GLYPH` 相应调整。
- **2026-09-30 轮到谁谁的墩位格子就亮** — 座位色呼吸脉冲 + 描边，用 `SYNC_TRICK_WINNER < 0` 当"这墩还活着"（坑 33）。
- **2026-09-30 修掉赢墩判定把 A 当最小**（坑 32）；手牌顺序改成 A 最大（坑 30）。
- **2026-09-30 新增收墩动画**：四张牌收拢、飞向赢家，约 1.05s，落在原本 25 tick 的停顿里（坑 31）。
- **2026-09-30 四条状态行改成逐行可拖 + 以屏幕底边为基准**（窗口变矮不再掉出屏幕）。
- **2026-09-30 墩堆格子方位字母改深色并关阴影**；花色换手绘像素素材（坑 29）。
- **2026-09-29 墩堆重做**：叫牌阶段隐藏、按四方位分格、牌按首攻者顺时针落格（坑 22/23/24），
  手牌按花色点数排序（坑 26），明手拖不回庄家手里（坑 25），布局版本升 4。
- **2026-09-29 删掉顶栏**（28px 让给北家手牌），四家手牌改绝对盒 + 镜像，北/南 `HORIZONTAL`、
  西/东 `VERTICAL`（坑 21），发现并修掉静态宽度导致的手牌缩放空操作（坑 19/20）。
- **2026-09-29 模糊改成正确顺序**：先整屏糊、再画弹窗；F9 加实时鼠标坐标（坑 13）。
- **2026-09-29 叫牌改成屏幕内模态弹窗**，新增 **F9 布局编辑器**（拖动/滚轮缩放/R 复位，
  存 `config/*-layout.properties`）和 `CardSlotGeometry` mixin（坑 15/16/14）。
- **2026-09-29 补齐定约桥牌**：叫牌盒 → 定约 → 明手摊牌由庄家代打 → 复式计分，
  四家皆过重新发牌；新增 `bridge:suit` 字体提供 ♠♥♦♣ 与 NT。
- **2026-09-29** 玩法改名 Contract Bridge（id `bridge:contract_bridge`），牌桌改四方位，
  新增「空座位由电脑补齐」选项，补 `how_to_play` 说明页。

## 9. 许可

Charta 是 **MPL-2.0**。做附属、改它、发布都行；改了 Charta 自身的文件再分发时需要开源对应文件。

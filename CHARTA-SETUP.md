# Charta 1.21.1 Fabric — 本地部署 & 桥牌附属开发环境

> 目标：把 [lucaargolo/charta](https://github.com/lucaargolo/charta) 的 1.21.1 Fabric 最新版部署到本地，
> 并搭好一个能编译、能进游戏、全套复用 Charta 动画/UI/材质的「桥牌」附属工程。

---

## 1. 部署结果

| 内容 | 路径 |
| --- | --- |
| Charta 源码仓库 | `D:\deepseekharness\charta`（`main` 分支，版本 `1.2.5`，MC `1.21.1`） |
| 构建产物（Fabric） | `D:\deepseekharness\charta\fabric\build\libs\charta-fabric-1.21.1-1.2.5.jar` |
| 本地 Maven 仓库 | `C:\Users\BigMer\.m2\repository\dev\lucaargolo\charta-fabric-1.21.1\1.2.5\` |
| 桥牌附属工程 | `D:\deepseekharness\charta-bridge` |

Charta 上游是 **多加载器（common / fabric / neoforge）** 工程。你只要 Fabric，但 `:common` 模块
是 Fabric 编译的一部分，必须先跑通——已经跑通了，不用再管 NeoForge。

已发布到 Maven Local 的坐标（附属工程就是靠它依赖 Charta 的）：

```
dev.lucaargolo:charta-fabric-1.21.1:1.2.5
dev.lucaargolo:charta-common-1.21.1:1.2.5
```

发布出来的 jar 是 **intermediary 重映射** 过的（`net.minecraft.class_xxxx`），
所以附属工程用任意 mappings（这里和 Charta 一样用 Mojang official + Parchment）都能正常依赖。

---

## 2. 环境前提（已配好）

| 项 | 值 / 位置 |
| --- | --- |
| JDK | 21（Gradle 自动选择 `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot`） |
| Gradle | 8.14，由仓库自带的 wrapper 提供，无需单独安装 |
| 代理 | Clash for Windows，`127.0.0.1:7890` |
| Gradle 代理配置 | `C:\Users\BigMer\.gradle\gradle.properties` |
| 发布用 key | 用户环境变量 `CURSEFORGE_API_KEY` / `MODRINTH_TOKEN`（暂填 dummy） |

`~/.gradle/gradle.properties` 里除了代理，还加了超时/重试：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.org.gradle.internal.http.connectionTimeout=180000
systemProp.org.gradle.internal.http.socketTimeout=180000
systemProp.org.gradle.internal.repository.max.retries=6
systemProp.org.gradle.internal.repository.initial.backoff=500
```

> ⚠️ **梯子必须开着。** JVM 不读 Windows 系统代理，Gradle 走的是上面这几行显式配置；
> 一旦 Clash 退出，构建会在 `maven.neoforged.net` / `downloadAssets` 这类步骤上以
> `Connection refused: 127.0.0.1:7890` 或 `Remote host terminated the handshake` 失败。
> 遇到就重启 Clash 再原样重跑一遍，Gradle 会从断点继续。

---

## 3. 常用命令

### Charta 本体

```powershell
cd D:\deepseekharness\charta

# 编译 Fabric 版（约 5 分钟，首次更久）
.\gradlew.bat :fabric:build

# 开发客户端（等价于启动器进游戏，带热重载）
.\gradlew.bat :fabric:runClient

# 开发服务端
.\gradlew.bat :fabric:runServer

# 改了 Charta 代码后，重新装进本地 Maven 给附属用
.\gradlew.bat :fabric:publishToMavenLocal
```

运行目录：`charta\fabric\runs\client`（`mods`、`config`、存档都在这里）。

### 桥牌附属

```powershell
cd D:\deepseekharness\charta-bridge

.\gradlew.bat build           # 产出 charta-bridge\build\libs\bridge-fabric-1.21.1-0.1.0.jar
.\gradlew.bat runClient       # 开发客户端，Charta 会作为依赖模组一起加载
.\gradlew.bat deploy          # 构建 + 复制到 PCL2 实例 mods，并清掉旧的同名 jar
```

#### 免手动开局的调试牌桌

真牌桌要点开「选择游戏」得先备好牌组、桌布、多方块牌桌和四把椅子，很烦。
`BridgeDebug` 会在进世界后直接把一张自动对局的桥牌桌拍在屏幕上（客户端 `-2/-3/-4`
当机器人 id 走的就是真实玩家那条构造路径）：

```powershell
cd D:\deepseekharness\charta-bridge
$env:BRIDGE_DEBUG_SCREEN=1; .\gradlew.bat runClient
```

`build.gradle` 的 dev client 已经带了 `--quickPlaySingleplayer "New World"`，
会直接进 `runs/client/saves/New World`；世界不存在就退回主菜单。
想验证时看输出里的 `debug: trick n/13 ...` 心跳就行——整局 13 墩跑完无异常即通过。

---

## 4. 附属开发：Charta 的 API 长什么样

Charta 的附属扩展点全在 `dev.lucaargolo.charta.common.game.api` 下，**加一个游戏只需要两处注册**：

```java
// 1) 游戏类型 —— 直接塞进 Charta 的 charta:game_type 注册表
Registry.register(Games.getRegistry(), id("bridge"), BridgeGame::new);

// 2) 菜单类型 —— 塞进原版菜单注册表
Registry.register(BuiltInRegistries.MENU, id("bridge"),
    new ExtendedScreenHandlerType<>(BridgeMenu::new, AbstractCardMenu.Definition.STREAM_CODEC));
```

Charta 打开牌桌的 `TableScreen` 是 **遍历 `Games.getRegistry()` 现场生成按钮**的，
所以注册完什么都不用做，游戏就会自动出现在「选择游戏」界面里。

⚠️ 命名不是随便取的，注册 id 的 **path 会被 Charta 拿去拼三处资源路径**（见 `TableScreen` / `GameScreen` /
`OptionsScreen` 里的 `gameId.toLanguageKey()`）：

| 用途 | 由 id `bridge:contract_bridge` 推出 |
| --- | --- |
| 按钮/界面标题 | 语言键 `bridge.contract_bridge`（注意**不是** `game_type.xxx`，`toLanguageKey()` 只拼 `ns.path`） |
| 按钮贴图 | `assets/bridge/textures/gui/game/contract_bridge.png` |
| 玩法说明页 | 语言键 `charta_md:bridge.how_to_play_contract_bridge`，值为 `bridge:<locale>/how_to_play_contract_bridge` |

说明页文件必须放在 `assets/bridge/lang/charta_md/<locale>/...md` —— `MarkdownResource`
把扫描路径写死成了 `lang/charta_md`，**不会**跟着命名空间走。

### 必须实现的东西

| 类 | 作用 | 父类/接口 |
| --- | --- | --- |
| `BridgeGame` | 规则、发牌、回合、结算 | `Game<G, M>`（抽象方法：`createMenu` `getDeckPredicate` `getCardPredicate` `canPlay` `startGame` `runGame` `endGame` `getOptions`） |
| `BridgeMenu` | 声明有哪些牌槽 | `AbstractCardMenu<G, M>` |
| `BridgeScreen` | 画背景 + 文字 | `GameScreen<G, M>`（**继承它就白拿整套动画/拖拽/历史面板**） |

### 白拿的部分（不用自己写）

- **动画 UI**：`GameScreen` 自带的 `AbstractCardWidget` / `CardSlotWidget`，含悬停抬起、拖拽、翻牌插值。
- **牌表同步**：`AbstractCardMenu` 的 `cardSlots` + `CardContainerSynchronizer`，服务端权威、自动广播。
- **回合/选项同步**：`AbstractCardMenu` 内置 `ContainerData`（当前玩家、游戏是否开始）。
- **历史面板 / 选项面板**：`GameScreen` + `GameOption`，选项控件（`GameOption.Bool` / `GameOption.Number`）自动生成。
- **材质**：牌面/花色/牌背走数据驱动资源（`cards/*.png`、`suits/*.png`、`decks/*.png`，位于资源包根目录，
  卡面图 25×35 切片），`Deck.simple(...)` / `Deck.fun(...)` 可直接套用 Charta 现成牌组。
- **音效**：`ModSounds.CARD_PLAY` / `CARD_DRAW`，`GameSlot.onInsert/onRemove` 默认就会播。

### 关键 API 备忘

```java
// 牌桌坐标（TABLE 是 160×160 的平面，x/y 是平面坐标，z 是离桌高度）
new PlaySlot(this, new LinkedList<>(), x, y, z, angle, drawSlot);

// 发牌：从牌堆取 count 张给玩家，自动进 hand + censoredHand
dealCards(dealPile, player, 1);

// 注册牌槽（index 从 0 递增，Menu 里用 g -> g.getSlot(i) 引用）
addSlot(...);

// 消息 / 标题
table(Component...);  play(player, Component...);  player.sendTitle(...);

// 机器人（没玩家时 / AI）
new AutoPlayer(intelligence);   // shouldCompute() == true，会自动 getBestPlay 出牌
```

---

## 5. 附属工程里已经写好的东西

`D:\deepseekharness\charta-bridge\src\main\java\dev\bridge\`：

- `BridgeMod.java` — 两处注册 + `ensureChartaInitialised()` 顺序兜底。
- `BridgeClient.java` — `MenuScreens.register`。
- `game/BridgeGame.java` — 4 人 2v2 定约桥：满 52 张发牌、**跟花色校验**、13 墩、成对计分、
  胜负播报。座位用 `getSeat()` 维护（**不能用 `indexOf`**，见坑 9），机器人由
  `setRawOptions()` 钩子按选项补齐。**尚未实现**：叫牌（bidding）、明手（dummy）、定约分。
- `game/BridgeBot.java` — `AutoPlayer` 子类，只加身份 + 一个刻意做偏的 `equals`（见坑 9）。
  `game/BridgeMenu.java` — 只决定牌槽的**顺序**（0 = 墩堆，1..4 = 北/西/东/南，见 `HAND_ORDER`）和
  **类型**（北/南 `HORIZONTAL`、西/东 `VERTICAL`、墩堆 `DEFAULT`，见 `seatSlot`），
  坐标全部交给 `BridgeLayout`。注意牌槽索引是对外契约，改顺序要同步 `BridgeLayout.apply`。
- `client/BridgeLayout.java` — 所有可动元素（弹窗、四家手牌、墩堆、铭牌）的唯一坐标来源。
  **`bounds()` 统一返回屏幕坐标**（连自家手牌也是），三种 `CardSlot.Type` 各自的私有约定只在
  `setSlot` 里翻译一次；含屏幕尺寸推导的默认值、玩家拖动量、缩放值，和
  `config/bridge-layout.properties` 的读写（带 `version` 戳，改版会丢弃旧偏移）。
  **`defaults(Element)` 就是出厂布局**：构造时 `resetAll()` 铺一遍，`R`/`Ctrl+R` 也复位到它，
  存档退化成覆盖层（坑 48）。其中 `pile`/四条状态行共 5 个元素带非零偏移（`{dx,dy,scale}` 的整数），
  其余 10 个是 0 —— 也就是"锚点原位"。
  默认形状：南家是绝对盒 `(170,300,300,53)`（长边居中，所以 `x` 由 `(屏宽-宽)/2` 算出，
  不是常量），北家按屏幕水平中线镜像；西家 `(66,60,38,224)`、东家按竖直中线镜像，
  西/东的 `x` 同样不是常量：`columnLeft()` 把 38 宽的柱子摆进扇面两侧那 170px 空条的正中
  （66px 边距 = 66px 与扇面的间隙，两边对称）；竖向只剩 224 是因为 284 起就是被拖到两侧的
  状态行（见坑 47）；铭牌盒=铭牌本身的位置。
- `game/BridgeScreen.java` — 叫牌弹窗（整屏模糊 pass + 全屏霜 + 弹窗描边）+ 出牌/结算绘制 +
  **F9 编辑模式**（拖动/滚轮缩放/`R` 复位，右下角实时显示鼠标坐标：屏幕坐标、弹窗局部坐标、指针下的元素）。
  `renderTopBar` 被覆写成空——顶栏那 28px 正是北家手牌的位置。
- `mixin/CardSlotGeometry.java` — 让 `final` 的槽坐标准写、并给槽加一份可改的声明尺寸。
- `mixin/CardSlotWidgetColumnStep.java` — 把 `CardSlotWidget` 里竖排手牌那个写死的 10px 步进上限
  换成一张牌的高度，否则加长西/东那条盒子根本没用（见坑 47）。
- `mixin/CardSlotWidgetMetrics.java` / `mixin/GameScreenMetrics.java` — 把
  `CardSlot.getWidth/getHeight` 这两个**静态**调用重定向到实例上的声明尺寸（画的一边 + 命中检测的一边，
  见坑 19）。

选项面板入口：牌桌「选择游戏」界面里 **Shift+左键** 点游戏按钮 → 右边齿轮 → 里面有
「空座位由电脑补齐」。关掉就必须坐满 4 个真人。

改包名/模组 id 的话，记得同步 `gradle.properties` 的 `mod_id` / `group` 和 Java 包路径，
以及 `BridgeMod.GAME_ID` 对应的贴图/语言键。

---

## 6. 踩过的坑（别再踩）

1. **`CURSEFORGE_API_KEY` 是配置期强校验** — 模板里 `cursegradle` 在 configuration 阶段就要求 apiKey，
   没设的话**任何** gradle 任务都跑不起来（报 `apiKey not set for project 1150549`）。已设成 dummy。
2. **`fabric.mod.json` 的版本谓词**根本**不支持 maven 区间**——`[1.21, 1.21.2)` 一个版本都匹配不上** ⚠️⚠️
   一直以为只是"逗号后面不能有空格",实测不是。看 `VersionPredicateParser.parse`(loader 0.19.5 源码):
   它先按空格切,再找 `>=/<=/>/</~` 前缀,**找不到就当作 `EQUAL`**,然后把整串(含 `[` `,` `)`)
   丢给 `VersionParser.parse`;后者解析不了这种串,退化成 `StringVersion`,于是这一条变成
   "版本 == 字符串 `[1.21,1.21.2)`"——永远为假。加载时表现就是硬依赖不满足
   (`HARD_DEP_INCOMPATIBLE_PRESELECTED` 或直接拒载),而且**空格有没有都一样**。

   可用的写法只有一种:**空格分隔的运算符,会 AND 起来**。要 `[1.21, 1.21.2)` 的语义就写
   `>=1.21 <1.21.2`;`~1.21.1` 等价于 `>=1.21.1 <1.22.0`(会放进 1.21.2+,而 Charta 1.2.5 是按 1.21.1 编的)。
   `.properties` 里 `minecraft_version_range=>=1.21 <1.21.2`、`charta_version_range=>=1.2.5 <1.3.0`。

   别猜,直接拿真加载器验一遍(30 行,不用启动游戏):

   ```powershell
   # 用 gradle 缓存里的 fabric-loader-0.19.5.jar 当 classpath,调 VersionPredicateParser.parse(p).test(Version.parse(v))
   ```
   实测 `>=1.21 <1.21.2` 对 1.21/1.21.1 命中、对 1.21.2/1.20.6 不命中;
   `>=1.2.5 <1.3.0` 对 1.2.5/1.2.6 命中、对 1.3.0/1.2.4 不命中。
3. **`depends` 里写 `"charta"` 是必须的，但*不足以*保证加载顺序** ⚠️ 最坑的一个。
   Fabric Loader **不保证**附属的 entrypoint 排在依赖之后，而且顺序**跨进程不稳定**（新版加载器用
   identity-hash 的 map 推导顺序，`charta` 有时在前有时在后）。踩中时崩在：
   ```
   Caused by: NullPointerException: ... because "ChartaMod.instance" is null
       at ChartaMod.loadPlatformClass(ChartaMod.java:261)
       at Games.<clinit>(Games.java:19)
       at dev.bridge.BridgeMod.onInitialize
   ```
   更阴的是 **`Games` 静态初始化一旦抛异常就永久失效**（后续 `NoClassDefFoundError: Could not
   initialize class ...`），所以 try/catch 重试没用，必须在碰之前就保证 `instance` 非空。
   实测加载器 **0.17.3 不崩、0.19.5 崩**，同一份代码在开发环境和 PCL2 实例里表现相反。

   而且**两个注册都只能在 `onInitialize()` 里做**：Fabric 在 `main` entrypoint 阶段一结束就冻结
   **所有**注册表 —— 包括 `minecraft:menu` **和** Charta 动态建的 `charta:game_type`。
   推到 `ClientLifecycleEvents.CLIENT_STARTED` / `ServerLifecycleEvents.SERVER_STARTING` 只会拿到
   `IllegalStateException: Registry is already frozen`。

   最终解法（已实现在 `BridgeMod` 里）：`onInitialize()` 开头调 `ensureChartaInitialised()`，
   `ChartaMod.getInstance() == null` 就 `new FabricChartaMod()` —— 构造函数只干两件事：
   `instance = this` 和一个 packetManager，真正的注册在 `init()` 里、只由 Fabric 自己那个实例执行，
   所以不会重复注册。构造完顺序问题就消失了，剩下两行正常 `Registry.register`。
   注意 `AbstractCardMenu.Definition.STREAM_CODEC` 也会踩雷（`Definition.<clinit>` → `Deck.<clinit>`
   → `Suits.<clinit>` → `ChartaMod.registry`），所以别以为"只注册个菜单类型不算碰 Charta"。
4. **Charta 的 `ModMenuTypes.REGISTRY` 不能事后 `register()`** — 它在 `init()` 里已经遍历注册过了，
   之后再 `register` 只会进 `entries` map、不会真正注册。所以附属要用原版
   `BuiltInRegistries.MENU` 自己注册 MenuType，只有 `Games.getRegistry()`（裸注册表）能直接 `Registry.register`。
5. **Charta 的 POM 会拖 JEI / REI** — 附属 `repositories` 里要加
   `https://maven.blamejared.com` 和 `https://maven.shedaniel.me/`，否则解析失败。
6. **别在开着 `runClient` 时构建另一个工程** — 共用 `~/.gradle/caches/fabric-loom`，
   会撞锁出现 `ACQUIRED_PREVIOUS_OWNER_DISOWNED` 和删文件失败。
7. **`build/libs` 的 jar 只有 `gradlew build` 才会刷新** — 只跑 `runClient` 不会重新生成 jar，
   直接复制就会把上一次的旧 jar 部署进游戏（表现为改了 `fabric.mod.json` 却还是报旧的错）。
8. **延迟注册到 lifecycle 事件的坑** — 见第 3 条：`charta:game_type` 不是原版注册表，
   但它同样会被 Fabric 在 `main` 阶段末尾冻结，所以"注册表还能写"这个直觉只对了一半。
9. **`CardPlayer.equals` 会被 `containsAll` 反向调用** ⚠️ 想加机器人时最容易翻车的一处。
   `CardTableBlockEntity.serverTick` 靠这行维持对局：
   ```java
   if (!seatedPlayers.containsAll(game.getPlayers())) game.endGame();
   ```
   `seatedPlayers` 只含真人，机器人永远不在里面，所以机器人一旦进 `players`，
   朴素 `equals` 会让对局**开局第一 tick 就被判负解散**。
   而 `contains(o)` 内部是 `indexOf(o)` → 调的是**参数**的 `equals`，
   于是 `BridgeBot.equals` 返回 `!(other instanceof BridgeBot)` 就能骗过这个检查。

   代价是 `players.indexOf(bot)` 会返回第一个真人的座位，两种情况都会错：
   - `AbstractCardMenu` 内置的 `ContainerData`（当前玩家 = `players.indexOf(currentPlayer)`）
     → 机器人回合会被画成"你的回合"。解法：`BridgeMenu` 再 `addDataSlots` 一个自己的座位槽
     （id 排在后面，客户端按顺序应用，天然覆盖掉坏的那个）。
   - 自己写的轮转 → 一律用 identity 遍历的 `getSeat()`，别用 `indexOf`。
10. **`getCensoredHand(player)` 是懒创建的，首次时机不对会多一张** — `dealCards` 会
   `getPlayerHand.add(card)` 之后才 `getCensoredHand.add(new Card())`，如果此时 censored 还没被创建，
   它会按"已发 1 张"的手牌把列表初始化成 1 张、再 `add` 1 张 → 暗牌比真手牌多一张。
   `startGame()` 里对每个玩家先 `getCensoredHand(player).clear()`，把创建时机提前到发牌之前即可。
11. **`PREVIEW` 类型的牌槽用的是绝对屏幕坐标** — `GameScreen` 对其他类型都加 `topPos`，
   唯独 `PREVIEW`（和 `HORIZONTAL`）不加（前者贴屏幕顶、后者贴屏幕底）。
   想在"牌桌中间附近"放对手暗牌预览，就得在 Menu 构造时自己读
   `Minecraft.getInstance().getWindow().getGuiScaledHeight()` 把 `topPos` 补上；
   专用服务端没有 `Minecraft`，用 `try/catch(Throwable)` 兜底（坐标在服务端本来也不渲染）。
12. **真人玩家的 `CardPlayer` 也是 `AutoPlayer` 的匿名子类**（`LivingEntityMixin` 里），
   所以判断"这是不是机器人"不能只看 `instanceof AutoPlayer`，要看 `getEntity() == null`。
13. **模糊的关键不是"糊"，是"糊完之后那层均匀的霜"** ⚠️ 反复踩了三轮才定位。
   - 两点反编译事实（别再凭印象推）：
     - `GameScreen.render()` 第一行就是 `renderBlurredBackground(partialTick)`，**整屏已经糊过了**，
       跟叫牌界面没关系；`GameScreen` 还把 `renderBackground` 覆写成空方法。
     - `GameRenderer.processBlurEffect(float)` 开头有个硬门槛——
       `if (blurEffect != null && options.getMenuBackgroundBlurriness() >= 1)`，
       默认只有 5px 半径，棋盘又暗，所以"糊了但看不出来"。
   - 结论：**想更明显只能 (a) 再跑一遍 pass 把已糊的帧再糊一次（半径叠加）+
     (b) 糊完之后铺一层整屏半透明霜**。原以为"再调 processBlurEffect 是多余的"是错的。
   - 霜必须**铺满整屏**（不是 scissor 到弹窗），弹窗只在上面再多压自己那一块。
     两层都别太重：`SCRIM = 0x6B101014`（约 42% 黑，整屏）+ `BACKDROP = 0x59101014`（弹窗再压一点）。
   - ⚠️ 反面教材：面板贴图 `gui/bridge.png` 自己就是 30~45% 黑的 PNG，`renderTransparentBackground`
     又填 75% 黑，再来个 `0xC8` 背板——三层叠完世界只剩约 4%，糊了也白糊。所以弹窗**不画那张贴图**。
   - 调用时机要对：额外的 blur pass 必须放在 `super.render()` **之前**（顶栏/底栏/牌都还没画），
     否则会把它们一起糊成糊糊。
14. **`GameScreen` 给每种 `CardSlot.Type` 套的坐标变换都不一样** ⚠️ 写死坐标时最阴的一处。
   反编译 `GameScreen` 确认过（`isHoveringPrecise(CardSlot,float,float)` 里那个 `lookupswitch`）：
   它的**基础命中检测自己就会减掉 `leftPos`/`topPos`**，所以两种类型额外把 `topPos` 又加/减了一遍
   来抵消——等于命中框和绘制点必须逐像素对齐，差一个 `topPos` 就点不着。
   | 类型 | 实际绘制点 | 命中框 | 所以 `slot.y` 要写 |
   | --- | --- | --- | --- |
   | `PREVIEW` | `slot.y`（绝对屏幕坐标） | `slot.y - topPos` | 屏幕 y |
   | `HORIZONTAL` | `slot.y + 屏幕高 - 卡高` | `slot.y - topPos + 屏幕高 - 卡高` | `屏幕y - 屏幕高 + 卡高`，即**负的底边偏移**（原版游戏就是 `-5`，牌栏于是画在屏幕底边往上 5px） |
   | 其它 | `slot.y + topPos` | `slot.y` | `屏幕y - panelTop` |
   `slot.x` 例外，**三种都**是 `leftPos + slot.x`。
   ⚠️ 正因为 HORIZONTAL 这样存，**它的 `bounds[1]` 会是负数（在屏幕上方）**，标签/黄框要另用
   `handTop()` 换算，别直接拿 `bounds[1]` 用——否则铭牌会飘到屏幕外。
   症状：手牌**看得见但点不中/拖不动**（就是这一条踩了）。
15. **给 Charta 的类加 mixin 的两个"互相打架"的规则** ⚠️ 同时踩中会直接开不了游戏：
    - **mixin 包里的类是被那个 config "占有"的**，普通代码直接引用会炸：
      `IllegalClassLoadError: dev.bridge.mixin.X is in a defined mixin package dev.bridge.mixin.*
      owned by bridge.mixins.json and cannot be referenced directly`
      （表现是整个 `main` entrypoint 挂掉，栈顶是 `BootstrapMethodError` + 你引用它的那行）。
    - **Mixin AP 不允许 interface mixin 指向 class**：
      `Targetted type '...CardSlot' of ...CardSlotAccess is not an interface`（编译期就红）。
    结论：**访问器接口要放在普通包里**（`dev/bridge/client/CardSlotAccess.java`，纯 `interface`，
    不是 mixin），mixin 类（`dev/bridge/mixin/CardSlotGeometry.java`）`implements` 它，
    `@Accessor` 声明在 mixin 类里。这样两边规则都不碰。
    - **写 `final` 字段的 `@Accessor` 必须加 `@Mutable`**，否则编译通过、启动也通过，
      一进对局就 `IllegalAccessError: Update to non-static final field ...CardSlot.x attempted from a
      different method (bridge$setFieldX) than the initializer method <init>` —— 生成的 setter 不是构造器，
      所以普通 `PUTFIELD` 在 JVM 看来是非法的 final 写。
    另外：`CardSlot.x/y` 是 `final`，需要实例字段时用 `@Unique` 自己存（`@Shadow` 只能读）。
16. **`CardSlot.x/y` 是 `final`，宽度更是没字段** — 想在运行时拖动/缩放牌槽只能上 mixin（见坑 15）。
   宽度是 `CardSlot.getWidth(Type)` 这种**静态**方法算的，覆盖不了，只能自己在实例上存一份；
   而 `CardSlotWidget` 是拿"声明的槽宽"当扇牌钳制范围的，所以这也是唯一能控制扇牌疏密的旋钮
   （槽宽 ≤ 20 时 13 张牌会挤成一坨，拉到 100+ 才会摊开）。
17. **座位铭牌/明手高亮要跟牌槽锚点共用常量** — `PREVIEW` 槽走绝对坐标，屏幕文字走 `leftPos + x`，
   两边各写一份坐标最容易改一边忘一边（铭牌飘在牌上、黄框套歪）。现在全部走 `BridgeLayout`。
18. **`PREVIEW` 槽的"可见宽度"只有标称宽度的一半** ⚠️ 摆对手手牌时最容易算错的一处。
   `CardSlotWidget` 扇牌时会钳制 `leftOffset`：一旦 `slot.size() * childWidth > 槽宽`，
   就把整叠牌**压回槽宽以内**（13 张 20px 的牌挤进 41px）。拿 `CardSlot.getWidth(PREVIEW)`（41）
   当可见宽度去偏移，西家会压到叫牌表上、东家会跑到屏幕中间。
   ⚠️ 另外：手牌是在 `renderBg` **之后**画的，只要手牌和面板重叠，面板里的表格/叫牌盒就会被盖住。
19. **`CardSlot.getWidth/getHeight` 是静态的，所以"缩放牌槽"曾经是个空操作** ⚠️ 隐蔽到离谱。
   两个方法都是 `static float getWidth(Type)` 风格，`CardSlotWidget` 与 `GameScreen.isHoveringPrecise`
   调的又都是 `getWidth(CardSlot)` 这个静态重载——**你往实例上写多少宽度都没人读**。
   表现：F9 拖动有用、滚轮缩放对手牌完全无反应，扇牌永远钉在 150（HORIZONTAL）/41（PREVIEW）。
   修法：在两个调用点上做 `@Redirect`，把静态调用换成读 `CardSlotAccess.bridge$width()`。
   ⚠️ 两边**都要**改（画的一边 `CardSlotWidget.renderWidget`、命中检测的一边 `GameScreen.isHoveringPrecise`），
   只改一边就是"牌按新宽度摊开、点击框还是老宽度"。
   `GameScreen.render` 里那句 `CardSlot.getWidth(slot) == CardImage.WIDTH*1.5f`（决定要不要画墩堆底板）
   故意不动，保持原版类型宽度。
20. **模组类的成员分两种，`remap` 该不该关完全取决于"它有没有覆写原版方法"** ⚠️ 这一条踩崩过一次游戏。
   发布出去的 Charta jar 是 **remap 过的**：覆写了原版方法的成员会被改成 intermediary 名。
   实测 `CardSlotWidget.renderWidget`（覆写 `AbstractWidget.renderWidget`）在 release jar 里叫
   **`method_48579`**，而 `GameScreen.isHoveringPrecise`（私有、Charta 自己的）和
   `CardSlot.getWidth`（自己的静态方法）**保持原名**。所以：
   - 覆写原版方法 → `remap` 必须留默认 `true`，让 refmap 把 `renderWidget` 映射成 `method_48579`。
     这里写 `remap = false` 的后果是运行期
     `Critical injection failure: @Redirect ... could not find any targets matching 'renderWidget'`。
   - 自己的成员 → `remap = false`（写描述符也一样），因为 jar 里就是原名。
   两个额外坑：
   - Mixin AP 对它解析不出的成员会**编译报错**：
     `Unable to locate obfuscation mapping for @Redirect target isHoveringPrecise`
     —— 加上 `remap = false` 就跳过解析，编译通过且运行期用的是字面名（正是我们要的）。
   - 同名重载要么写全描述符（`isHoveringPrecise(Ldev/.../CardSlot;FF)Z`，实测能编译），
     要么加 `require = 0`，否则 `injectors.defaultRequire: 1` 会因为那个没有调用点的重载报错。
   - 验证手段：构建后打开 jar 里的 `*-refmap.json`，确认覆写原版的那个方法**有**映射条目、
     `remap=false` 的那些**没有**条目。
   - 症状（如果又写错）：`Mixin transformation of ...CardSlotWidget failed` →
     `GameScreen.init()` 在 `cardSlots.forEach(slot -> new CardSlotWidget<>(...))` 那行炸掉，
     `slotWidgets` 停在空表 → 下一帧 `GameScreen.render` 抛
     `IndexOutOfBoundsException: Index 4 out of bounds for length 0`（4 其实是第一个非空槽）。
     看到这个 `length 0` 就该先怀疑 mixin APPLY 失败，不要去查布局。
21. **对手手牌别用 `PREVIEW` 槽** — `PREVIEW` 是 1/3 缩放（`isSmall()` → 0.333f），13 张牌挤在 41px 盒子里
   只剩约 20px 可见，看着像一坨。北/南用 `HORIZONTAL`（横扇，整张牌）、西/东用 `VERTICAL`（竖列，
   整张牌，间距 `(盒高-52.5)/(n-1)`），并把牌槽的声明尺寸直接设成盒子尺寸，扇牌就会精确铺满盒子。
   `VERTICAL` 的坐标系和 `PREVIEW` 不同（它走"面板相对"那条 `default` 分支），`BridgeLayout.setSlot` 已统一处理。


22. **想让某个牌槽"自己别画"，最省事的做法是让它永远 `isEmpty()`** — `GameScreen.render` 里画牌槽内牌
    的条件是 `!slot.getSlot().isEmpty()`，一句话而已。墩堆要按四方位分四格画，而一个牌槽只会把**最后一张**
    画在同一个点上，于是 `BridgeGame.PileSlot extends PlaySlot` 覆写 `isEmpty() -> true`：
    原版那套画法整体退出，命中检测（走 `isHoveringPrecise(slot, ...)`，跟 `isEmpty` 无关）和
    入槽校验（`PlaySlot.canInsertCard`）、镜像（`broadcastChanges` 比对的是 `cards` 列表，不是 `isEmpty`）
    全部原样保留 —— **`insertCards` 用的是 `slot.addAll(...)`，也不看 `isEmpty`**（只有算插入位置时看）。
    声明尺寸故意留成 3x3 的方块（113x158），整个方块就是拖放按钮，四格的图由 `BridgeScreen.renderPile` 自己画。
    顺带：牌槽类型选 `PREVIEW` 只是为了避开 `render` 里那句 `getWidth(slot) == 37.5f` 的底板（41 != 37.5），
    以及它恰好是"y 读作绝对屏幕坐标"的那一种。
23. **`isEmpty()` 撒谎的副作用：`CardSlot.insertCards` 会永远把牌插到下标 0** — 它的原话是
    `index == -1 ? addAll(collection) : addAll(!slot.isEmpty() ? (index+1)%(size+1) : 0, collection)`，
    撒谎之后走 `0` 分支 = **前插**；而机器人走的是 `trickPile.addLast` = **后插**。两条路径顺序相反，
    客户端按 `leader + i` 定位就会错位。修法：出牌时用 `currentTrick`（唯一的出牌流水）整体重写一遍
    `trickPile`（`syncTrickPile()`），顺带把 `hoveredCardId` 可能是别的槽的残留下标也一起解决。
    ⚠️ 重写要用**可变**列表（`new ArrayList<>(...)`）：`setCards` 会换掉 `cards` 字段，之后
    `addAll/clear` 还在用，`Stream.toList()` 那种不可变表会在下一张牌时 `UnsupportedOperationException`。
24. **墩堆/牌的绘制要复刻 `AbstractCardWidget` 的 4/3 外扩 + 四个 shader uniform** — Charta 的牌不是普通 blit，
    `perspective.vsh` 用 `Fov/InSet/XRot/YRot` 假透视，widget 给的框比牌大 1/3。
    自己 `blitCard` 时若不先 `getCardInset().accept(0f)` / `getCardFov().accept(30f)` / `XRot,YRot = 0`，
    就会套用**上一帧最后一张被 hover 的牌**留下的抬起值，画出来是歪的、大小也不对。
25. **`Game.createPlayerHand` 是 `protected`，可以换成自己的 `GameSlot` 子类** — 手牌槽和别的槽不一样，
    它是 `hands.computeIfAbsent(player, this::createPlayerHand)` 现造的，不是 `addSlot` 注册的，
    所以 `getIndex()` 恒为 -1，要认"这是谁的手牌"只能把 `CardPlayer` 一起存进子类。
    用它来卡"明手的牌不许拖进庄家手里"：`onRemove` 记下 `pickupOwner`，`canInsertCard` 只在
    `pickupOwner == owner` 时放行。**必须做在 `GameSlot` 上**，因为 `CardSlot.canInsertCard/insertCards`
    都是 `final`；而"放行/拒绝"只在服务端被问（客户端只发点击包），所以客户端不记也没关系。
    拒绝是**优雅**的：`CardContainerSlotClickPayload.handleServer` 里 `canInsertCard` 返回 false 就什么都不做，
    牌还挂在手上，玩家接着点墩堆即可。
26. **手牌排序必须在服务端改真实的那条 list** — 点击包传的是牌在扇子里的**下标**，
    服务端 `slot.removeCards(payload.cardId)` 按下标取牌；客户端只负责显示服务端同步过来的顺序。
    所以排序要 `getPlayerHand(p).getCards()`（就是 `CardPlayer.hand()` 那条 `LinkedList`）原地 `sort`，
    而不是新建列表 `setCards` —— 后者会让实体手里的 list 和牌槽脱钩。排一次就够了：发完牌排
    （`beginAuction`），之后出牌只会删不会插。
27. **bitmap 字体的 `height` 是屏幕像素，不是贴图格边长；格子边长由贴图的 `chars` 网格反推** —
    `BitmapProvider$Definition.load` 里是
    `cellW = imageWidth / codepointGrid[0].length`、`cellH = imageHeight / codepointGrid.length`、
    `scale = height / cellH`、`advance = (int)(0.5 + 实际用到的宽 * scale) + 1`，
    `codepointGrid` 就是 json 里 `chars` 那几行字符串，
    `BakedGlyph.top = ascent - height`、`bottom = ascent`（`getBearingTop()` 直接返回 `ascent`）。
    两个结论：
    - 想**原像素 1:1** 上屏（像素画必须如此），就让 `scale = 1`，也就是 `height` 等于贴图格高。
      当前花色是 52x12 的图、一行四个字符、`"height": 12`，格子 13x12、scale = 1。
      格子宽度 = 最宽墨迹（11，黑桃/红心/梅花）+ 左右各 1px 边距。
    - **`ascent` 决定底边**：`bottom = ascent`，`top = ascent - height`。
      默认字体是 `ascent 7 / height 8`（字形占屏幕行 -1..6），花色写 `ascent 9` 时
      `top = -3`、12px 高的黑桃落在行 -3..8，正好和数字**同心**（两边中心都在 2.5）。改 `ascent`
      就是挪字形，**居中算式必须跟着走**。
      另外注意：墨高不同的两个字共用的是"中心"，不是"高度"。黑桃墨高 12、数字墨高 8，
      但都压在 2.5 这条线上，所以按 `(h - 墨高) / 2` 居中时，花色和 PASS 都该传 **8**（数字的墨高），
      传 12 会让花色整体高 2px。
      顺带：`ascent > height` 会被 codec 的 `validate` 直接判错。
    - 还有一条不在 json 里但同样要紧的：**`advance` 是算出来的**，`load()` 里
      `advance = (int)(0.5 + inkWidth * scale) + 1`，而 `getActualGlyphWidth` 是从**格子右边缘
      往里扫**找第一个非透明列、返回相对**格子左边缘**的下标 + 1。
      也就是说字形控制不了自己的 advance，只能靠"在格子里站哪儿"来定：
      贴到 x = 1 就恒等于 `墨宽 + 2`，即左右各 1px 间距；贴到左边 0 则左边距 0、右边距 2。
      格子只要够宽（最宽墨迹 11 + 两侧各 1 = 13）即可。想让字距变小，得先让墨迹变窄；
      改 `height` 只动大小、不动字距。
28. **像素画素材别缩，原样贴；缩了作者一眼就能看出"变形"** —
    素材是手绘的 11x12 / 11x9 / 9x9 三档。前后试过两种"归一化"，都不满意：
    - 缩到 8px：非整数比，1px 描边断成点；
    - 缩到 10px（按游程重采样，见 28b）：已经不丢特征了，但作者对着原图还是说"有区别、看着有一点变形"。
    最终做法：**格子直接开到最大素材的尺寸（13x12 = 11x12 黑桃/梅花 + 左右各 1px 边距），
    四个花色和 NT 全部按原像素拷贝、各自在格子里居中**，一个像素都不重采样。
    代价是黑桃/梅花（12 高）比红心/方片（9 高）高——但那本来就是作者画的比例，黑桃本来就该比红心高。
    生成脚本是 `tools/make-suit-font.ps1` → `bridge:textures/font/{suit,nt}.png`。
    验证方式：把生成好的 `suit.png` 逐格裁出来跟 `tools/suits/*.png` 比 alpha 掩码和通道值，
    必须 `mask-mismatches=0`、`maxChannelDev=0`（`Add-Sprite` 只做整体等比提亮，色相比值不变）。
    也**别用 `System.Drawing` 描系统字体的 ♠♥♦♣ 糊弄**：`Segoe UI Symbol` 的 ♠♥ 缩到 16x16 会糊成球。
28b. **非整数比缩小像素画：点采样会吃掉 1px 的特征，要按"整条游程"分配** —
    当前方案不缩了，但这条以后要缩时仍然成立。黑桃的尖端是 11px 宽素材正中那一列的**单个像素**，
    梅花那两个缺口各只有 1px 宽，按像素中心点采样时这些列有一半概率落在两次采样之间被整个跳过
    （尖端消失、缺口糊平）。
    做法（`Convert-Line`）：把一行拆成"连续同状态"的游程，按比例给每段分输出像素数，
    余量按"离应有份额还差多少"依次补给最缺的那一段——所以**任何一段都不会塌成 0 像素**。
    只有首尾两端的空白游程允许归零（那只是边距），平手时优先补给实心段（宁可加粗形状不加粗留白）。
    先按行跑一遍、再按列跑一遍，两个方向都护住。
    顺带两个 PowerShell 坑：`[int]((12 - 10) / 2)` 是 **1** 但 `[int]((12 - 9) / 2)` 是 **2**
    —— `[int]` 走"四舍六入五成双"，1.5 进位成 2，垂直居中会偏一像素，要截断得写
    `[int][math]::Floor(...)`；同理 `[int](0.5 + 9)` 是 10 而 Java 的 `(int)` 是 9，
    拿 PowerShell 模拟 Java 算式时别把 `[int]` 当 `(int)` 用。
29. **深色素材贴到深色底上，要在生成贴图时就把亮度提上去，而不是靠 tint** —
    `Deck.getSuitColor` 只把"比中灰暗"的颜色乘 2.5，`0 * 2.5 = 0`，纯黑提完还是黑的；
    而且它是按**牌面贴图的平均色**算的，跟素材本身的颜色对不上。花色素材是从牌面上取下来的
    （`#252525` 黑桃、`#7F0000` 红心、`#00007F` 梅花），直接贴到叫牌盒那种
    `0x50` 半透明黑底上等于隐形。
    做法：生成脚本把每个素材的**所有通道乘同一个系数**、直到最亮通道到 `0xE6`（色相不动），
    然后 `BridgeBid.symbol` **不再 tint**，让素材自带颜色直接上屏。
    同理墩堆格子里的方位字母改成**深色** `0xFF262626` 且**关掉阴影**：格子只压了一层薄黑，
    底下是浅色桌布，浅灰配深色阴影只会更糊。
30. **`Ranks` 的 `ordinal()` 是 A=1 一路到 K=13，所以"自然序"里 A 是最小的** —
    `Rank.compareTo` 就是 `ordinal` 相减。桥牌里 A 最大，直接
    `comparing(Card::rank).reversed()` 会把 **K 排最前、A 排最后**，正好反了。
    `BridgeGame.rankOrder` 把 A 折到 K 之上再 reversed，得到 A-K-Q-…-2 的握牌顺序。
    同一条也适用于任何"按点数比大小"的地方：`Ranks.STANDARD` 那个列表顺序（A 开头）是**注册顺序**，
    不是大小顺序，别拿来当排序键。
31. **"结算动画"这种一次性的演出要从服务端的一个状态沿触发，并且自己管生命周期** —
    赢家在这次墩里是个**上升沿**：服务端只在"牌摊在桌上、还没收走"的这段窗口里同步
    `SYNC_TRICK_WINNER`（`completeTrick` 里置位，`afterLinger` 里连同 `trickPile.clear()` 一起清掉），
    这样客户端不需要额外的时间戳包，也不需要猜。
    客户端用 `Util.getMillis()` 计时而不是 tick：帧率无关，也不用往 menu 上加可变状态。
    动画总时长（约 1045ms）要**明显短于** `TRICK_LINGER`（25 tick = 1250ms），
    否则服务端清空牌堆时动画还没落地，最后会"啪"地一跳。
    另外清状态的条件要挂在**牌堆变空**上而不是"计时到了"：同一个座位连赢两墩时，
    "赢家变了吗"这个判断是变不出来的（值一样），只有牌堆空了才说明上一墩真的收走了。
32. **"A 最大"这件事有好几个入口，改排序时别只改看得见的那个** —
    坑 30 修了手牌排序，但赢墩判定 `findTrickWinner` 里还留着
    `played.card().rank().compareTo(best.card().rank())`，也就是枚数序数比较（A=1）。
    症状很隐蔽：手里的牌按 A-K-Q… 摆得好好的，**牌桌上却是 Q 压 A、东家拿走西家的墩**，
    花色跟对了也照样判错。凡是"比大小"的地方都得走 `BridgeGame.rankOrder`，
    现在三处（排序、赢墩、以及将来任何比牌）统一。剩下的 `rank().ordinal()` 用法都要看一眼
    是不是**故意**的点值表（`BridgeBidding.highCardPoints` 就是故意的，A=1 对应 4 点大牌）。
33. **"轮到谁"的高亮用 `SYNC_TRICK_WINNER < 0` 当"这墩还活着"，别自己再造一个条件** —
    服务端只在"第四张牌还没落地"这段窗口里让赢家为 -1，之后整个停留期都是有效座位；
    所以这一个信号同时表达了"牌没打完"和"有人该动"，跟收墩动画共用同一个哨兵，
    两处永远不会说得不一样。收墩动画的 `trackSweep` 用的也是它。
    颜色别自己调，用 `menu.getPlayerAtSeat(seat).getColor()` —— 铭牌就是这个色，
    两处同色才读得出"是同一个人"；脉冲只动 alpha（`0x30..0x80`），不动色相。
34. **面板元素的 `baseHeight` 要按"最下面那排东西的底边 + 边距"算，别按它当初画到哪儿** —
    `BridgeLayout.Element.POPUP` 的宽高是**画出来的范围**（`popupHeight()` 直接返回它），
    而且只有 dx/dy/scale 会存进配置文件、**宽高不存**——所以改默认值立刻生效，
    不用动 `layout.version`，也不会冲掉玩家拖过的位置。
    叫牌盒的 pass/double/redouble 那一行在 y=165、高 12，底边 177，而面板是 170：
    三个按钮的下半截直接挂在面板外面，`加倍` 看着就像没框住。现在 186 = 177 + 和顶部标题对称的 9px。
    加东西进面板时先算一遍"最靠下的元素 + 边距"，比事后看截图猜靠谱。
    **另一条同类的**：弹窗内容是用 `BridgeMenu.PANEL_WIDTH/HEIGHT` 写的坐标再整体平移，
    而面板本身是 `BridgeLayout.Element.POPUP` 画出来的，两个数对不上就是内容整体滑出去。
    改面板尺寸时两边一起改（加宽到 168 就是为了给叫牌表格腾出列的位置）。
35. **"记录"类的表格要给序号就做成网格，序号要按参与者数分组、并且把可视窗口钉在最新一条上** —
    叫牌记录最早写成"每行把该座位的叫品从左往右顺排"，好处是省地方，坏处是**序号没地方放**：
    第几手这件事在一行里根本读不出来。改成"表头一行序号 + 四行座位 + 每个叫品居中落进自己的列"，
    行和列交叉一下就同时表达了"谁"和"第几手"。
    **但"第几手"这个单位要跟着参与者数走**：四家轮流叫，一轮 = 4 手，一列就该是一整轮
    （`round = index / PLAYERS`），这样每格恰好一手、永远不会有两手挤一格，表头也才是人能读的数。
    只按"手"编号会得到"第 7 个叫品"这种没人数的东西。
    一局能叫十几轮、固定宽度的面板塞不下，所以列数按面板宽度算、只显示**末尾**几列。
    关键是**钉在末尾而不是开头**：表头里的数字因此是真实轮数（不用加偏移），最新一手永远可见，
    也不会因为又有人叫了一手就整排左移、把已经在看的东西挪走。
    同理，需要"进度感"的地方就高亮最后一个（这里是给当前那一轮的编号上 `ACTIVE` 色）。
    顺带：这种几何要**自上而下推导**（`BOX_Y = TABLE_TOP + 4 * 行高 + 2`），
    别写死数字——这次加了一行表头，凡是写死的 y 都得手动挪一遍。
36. **鼠标悬停掉帧：Charta 的 hover 是"每次画别人之前先把 hover 重画一遍"，套两层就是 O(n²) 张牌** —
    作者反馈"鼠标放到自家手牌上 120→35fps，看套牌详情就不会"。原因不在我们的代码，在
    `GameScreen.render` 和 `CardSlotWidget.renderWidget` 里各有一个同形状的循环：
    ```java
    for (Renderable r : renderables) {
        if (r != hoverable) {
            if (hoverable != null) hoverable.render(...);  // ← 每次画别人前先把 hover 重画一遍
            r.render(...);
        }
    }
    if (hoverable != null) hoverable.render(...);          // ← 循环后再画一次（这次在最上层）
    ```
    在 `GameScreen` 这一层，"一个 renderable"是一个**整手牌**（`CardSlotWidget` 会把整把扇子画一遍），
    而它内部还有同样的循环。四家各 13 张时，悬停一张牌 ≈ `56 × (13 + 12) ≈ 1400` 张牌的绘制，
    每张还是 3 次 blit + 一次 glow RenderTarget 绑定/解绑，于是 120fps 掉到 35。
    **为什么套牌详情不掉**：`DeckScreen` 走的是 `CardScreen`（同一套循环）但里面是单张 `CardWidget`，
    只多 54 次单卡绘制，没有"整手牌 × 内层再来一遍"的乘法。
    **修法**（`GameScreenHoverCost` / `CardSlotWidgetHoverCost`，两个 `@Redirect` 空实现）：
    循环里那次直接不画——循环后那次本来就在最上层，中间的每一次几何、shader 颜色、混合函数都一模一样，
    会被最后那次完整盖掉，所以这是**纯删除**不是重排。
    选择器要点：`render`/`renderWidget` 都是覆写原版的方法，名字必须留在 remap 里（`method_25394`/`method_48579`）；
    `ordinal = 0` 取循环里那次（外层方法里有 2 处匹配，内层 3 处，第 2/3 处分别是别的卡和循环后那次）；
    `HoverableRenderable.render` / `CardSlotWidget.render` 继承自原版 `Renderable`，
    所以 owner 是 Charta 的类但名字和 `GuiGraphics` 描述符都要 remap。handler 要 `static`，
    因为被调用的接收者是字段 `hoverable` 而不是 `this`。
    副作用：`hoverable.isHovered` 这个字段每帧只被刷新一次（原先被刷 n 次），切换悬停目标会**晚一帧**，
    肉眼不可见；点击判定走的是 `isHoveringPrecise`，不受影响。
37. **UI 要"任何窗口尺寸都完整显示"，就别把窗口尺寸写进每个坐标——做一个固定设计帧，把整屏缩放上去** ⚠️
    `BridgeLayout` 里每个数字（`SOUTH_Y=300`、`PILE_TOP_GAP`、四条状态行的 `*_UP`）都是 640x360 上的
    绝对像素，而 640x360 恰好等于 1920x1080 开界面尺寸 3。窗口一变小，`y=300` 的自家手牌直接掉出
    240 高的屏幕，墩堆的 `height - SOUTH_Y` 还会变成**负数**跑到屏幕上方——"能显示"从来只是某一个
    分辨率下的巧合。逐个坐标去乘比例会让绘制代码到处是换算，正确做法是让**屏幕本身变成 640x360**：

    - `BridgeFrame`（`dev.bridge.client`）持有唯一的窗口→设计帧映射：**取两轴比例的较小值**做等比缩放
      （永远不超过任一轴，代价只是另一轴留白），再加一个居中原点。缩放每次从活窗口现算，F11 / 拖边框 /
      改界面尺寸下一帧就生效。1920x1080@3 时 `scale=1、origin=(0,0)`，即和改动前**逐像素相同**，
      作者在 F9 里拖出来的偏移不用重来。
    - `BridgeScreen.init()` 开头把 `this.width/this.height` **改写成设计帧尺寸**（必须在 `super.init()`
      之前：`AbstractContainerScreen` 用它们算 `leftPos/topPos`，`GameScreen` 用它们摆右上角按钮，
      `ChatScreen` 也用它初始化）。这样这个文件里所有现成的 `width/height` 公式**一行都不用改**。
      窗口真实尺寸改用 `BridgeFrame.windowWidth()`。
    - `BridgeScreen.render()` 把整个原版 pass 包在 `frame.push/pop` 里，并在入口把鼠标换算到帧坐标。
      帧是等比 + 居中的，所以光标位置和控件仍然一一对应。
    - 四个 `mouse*` 覆盖都在开头换算坐标，类的其余部分（命中、拖拽累计、F9 编辑器）只见帧坐标。
      **`dragX/dragY` 是长度不是位置**，要除以 scale，否则小窗口上拖动会跑在光标前面。

    **三件 `PoseStack` 带不动的东西**（这是本方案全部的额外成本）：

    - `GuiGraphics.enableScissor` **不看 pose**：`applyScissor` 把矩形乘窗口的 GUI scale 直接交给
      `RenderSystem`，所以叫牌面板的裁剪框必须传窗口像素（`BridgeFrame.toGuiX/Y`）。
    - **聊天**：`Gui` 自己也会在窗口坐标画一份（它的 chat layer 和前有没有屏幕无关），
      `GameScreen` 只是又画了第二份高 25px 的。帧里那份的 y 来自 `GuiGraphics.guiHeight()`，
      而它是直接读窗口的、不认识 pose，于是 k≠1 时两份会明显错开。修法：
      `GameScreenChatFrame` 把 `GameScreen.render` 里那次 `ChatComponent.render` 重定向成空，
      `BridgeScreen` 在 `pop` 之后用窗口坐标自己画一遍。
    - **`GameScreen.containerTick`** 自己读 `MouseHandler.xpos()` 换算鼠标（不经过 render），
      帧坐标下会差 1/scale 倍；`AbstractCardWidget.tick` 拿它算悬停倾角且**不做 clamp**，
      于是卡片会歪几百上千度。修法：`GameScreenFrameTick` 重定向 `xpos()/ypos()`，
      交回一个"预解过"的值让原式自己算出帧坐标（`BridgeFrame.tickX`）。

    两条新 mixin 的 handler 都用**实例方法 + `((Object) this) instanceof BridgeScreen` 守卫**：
    `GameScreen` 是 Charta 自家游戏共用的基类，pose 只在 `BridgeScreen` 里，
    不守卫的话别的牌桌会拿到被缩放的倾角、还会丢掉聊天。

    另外：`CardScreen.renderGlowBlur` 用 `screen.width/height` 铺满，而 `BridgeScreen` 的
    `width/height` 现在就是设计帧，所以辉光正好盖在帧区域上——卡片和辉光走的是同一个 pose，
    不会错位。

38. **座位→方位别再写成 `seat - localSeat`：那样四个人的画面互为镜像，谁看自己都在南** ⚠️
    症状（作者报的）："多人里每个玩家视角的南玩家都是自己"。原因是把方位定义成了**相对量**：
    `COMPASS[floorMod(seat - localSeat, 4)]`、`pileCell(seat - localSeat)`、
    `HAND_ORDER = {2,1,3,0}` 当偏移用。本地玩家永远落在偏移 0，而偏移 0 就是南，
    于是四家各自看到"我在下、上家在我上面"，谁跟谁说"北家那张牌"都指的不是同一个人。

    改法是把座位索引**直接当成方位**：`seat 0 = 北、1 = 东、2 = 南、3 = 西`。
    这个方向不是随便挑的——它正好是游戏自己的顺时针（`BridgeGame.seatFrom(seat, 1)` 递给下一家），
    而屏幕上的 北=上、东=右、南=下、西=左 也是顺时针，两边一对齐，墩堆那张
    "entry i 属于 leader + i" 的注释就自然成立。**屏幕不随视角旋转**，只有措辞跟着视角走
    （自己的铭牌和叫牌表那一行写"你"）。

    要清掉的相对量（一处漏掉就会半张桌子错位）：

    - `BridgeScreen.COMPASS` 改成 `{north, east, south, west}`（按座位索引查）；
      `labelSuffix`、`renderPile` 的 `activeCell`/卡片 cell、`winnerAnchor`、`highlightDummy`、
      `renderSeats` 全部改查座位本身。叫牌表的四行也从"本地玩家第一行"改成固定的 北/东/南/西 行序。
    - `BridgeMenu.HAND_ORDER` 从**偏移表**变成**座位表** `{0, 3, 1, 2}`（北、西、东、南，
      和 `BridgeLayout.apply` 的槽位下标一一对应），构造时 `seatSlot(seat)` 不再加 `localSeat`。
      槽位朝向也由座位自己的方位决定（北/南 `HORIZONTAL`、东/西 `VERTICAL`），
      所以**坐在东/西的人自家手牌是竖列**——这是"一张桌子大家看到同一幅画"必然的代价，
      也是它买到的东西。
    - `BridgeLayout` 加 `hand(compass)/plate(compass)` 两个查表方法，`BridgeScreen` 不再各写一遍 switch。

    另外必须新增 `Element.SOUTH_PLATE`：南家以前**没有**铭牌元素，本地玩家的牌子是
    `drawSeatLabel` 里 `element == SOUTH_HAND` 的一个特例（画在自己手牌上方 4px）。
    本地玩家恒在南时这没问题，方位钉死之后就必须四个座位都有一块真牌子，
    否则坐在北/东/西的人头上会凭空多一块牌子、坐在南的人一块也没有。
    新元素只在配置文件里多一个 `south_plate.*` 键，缺省偏移 0，`layout.version` 不用动。

39. **`getCensoredHand` 返回"真手牌"时,`HandSlot.postUpdate` 会把真手牌刷成牌背** ⚠️
    Charta 的菜单 `HandSlot` 每 tick 干一件事:把自己的**censored 槽**填成一整列 `new Card()`(空白牌)。
    平时这是对的——censored 槽就是另一份"看不见"的副本。但明手那一份是特例:
    `BridgeGame.getCensoredHand` 为了让全桌都能看见明手,对明手**直接返回真手牌 GameSlot**(不是副本)。
    于是在明手自己的客户端上,`HandSlot` 的 owner 是明手 → `censored == getSlot()` 是同一个对象 →
    每 tick 把明手 13 张真牌覆盖成 13 张空白牌。表现就是**明手自己的手牌全变牌背、而且花色判定全瞎**
    (空白牌的 `suit()` 是 null)。别人的客户端不受影响(他们对明手用的是普通 `CardSlot`,没有 postUpdate),
    所以只在"明手是真人"这一种情况下出现。

    修法不是加特例分支,而是把 `shouldUpdate` 谓词写对:`game -> !game.isDummy(this.getCardPlayer())`。
    明手本身就是"已经摊开的牌",压根不需要那份空白副本,关掉刷写即可。

    顺带一提,同一个事实还有第二个后果:明手的手牌在服务端也有一份,服务端每个玩家的菜单同样会跑
    `postUpdate`,所以不修的话空白牌会一路同步给全桌。

40. **明手的牌既不是明手的,也不是公共的——是庄家的** ⚠️
    桥牌里庄家替明手打牌,所以拿牌的权限只有两个来源:自己的手牌归自己,明手的手牌归庄家(= 明手的搭档);
    明手本人只是看着自己的牌被出掉。作者的原话是"除了队友之外,其他人不能操控明手的牌,明手自己也不行"。

    Charta 的默认是**任何手牌都能随便拿**(`GameSlot.canRemoveCard` 只有一句 `!isEmpty()`),
    另外两家的手牌因为菜单拿到的是 censored 槽(`Game.canRemoveCard` 恒 false)碰巧是锁着的——
    于是明手就是唯一的洞:全桌任何人都能把明手的牌拖来拖去,明手自己也能。
    出牌那一步其实已经是锁的(`PlaySlot.canInsertCard` 要求 `player == getCurrentPlayer()`,
    而明手轮次时 `currentPlayer` 是庄家),所以只有"拿"这一层漏。

    修法:在 `BridgeGame.canHandleHand(player, owner)` 里一条写完 ——
    `player == owner && !isDummy(owner)`,否则 `isDummy(owner) && getSeat(player) == declarerSeat`;
    然后在 `BridgeGame.HandSlot` 的 `canRemoveCard` / `canInsertCard` 上都过一道。
    机器人不走槽位(直接 `player.play`),不受影响。

41. **自定义字体里的中文会变方框:`minecraft:include/default` 不含 CJK,而且 `Style` 是继承给 `append` 子组件的** ⚠️⚠️
    两个坑叠在一起,只会在"给带花色字体的组件追加中文"时炸,所以特别隐蔽。

    第一个:`bridge:font/suit.json` 里原本末尾是 `{"type":"reference","id":"minecraft:include/default"}`。
    那个 `include/default.json` 里只有 `nonlatin_european.png` / `accented.png` / `ascii.png` 三张图,
    **一个汉字都没有**。CJK 来自 `include/unifont.json`(→ `unihex` 读 `unifont.zip`),
    而 `default.json` 是 `include/space` + `include/default` + `include/unifont`。
    注意 **client jar 里的 `assets/minecraft/font/include/unifont.json` 是空的 `{"providers":[]}`**,
    真正的 unifont 由**资源索引**(`assets/indexes/17.json` 里的 `minecraft/font/unifont.zip`)提供,
    所以翻 jar 会以为"根本没有 unifont"、以为默认字体也没有中文——是错的。
    修法:自定义字体末尾直接 reference **整个 `minecraft:default`**,别 reference `include/default`。
    provider 的优先级是**先声明先赢**(`FontSet.selectProviders` 遇到第一个有该字形的 provider 就 break),
    所以自家的花色 bitmap 必须排在 reference 前面,否则会被 `nonlatin_european.png` 里那套系统 ♠♥♦♣ 顶掉。

    第二个:`Component.copy()` 只复制**根**,再 `append(x)` 时 `x` 会把根的 Style 当父样式继承下来,
    **包括 `withFont`**。于是 `BridgeBid.name(contract)`(根带 `BridgeMod.SUITS`)后面 `.append(加倍)`,
    「加倍」两个字就继承成了花色字体 → 两个方框(再加倍是三个)。
    修法:换成 `Component.empty().append(name).append(other)`,空根没有 font,两边各自保留自己的样式。
    状态行那句 `message.bridge.contract_line` 没事,因为 doubling 是**参数**不是 append 的子组件。

42. **座位索引不是方位:Charta 会把玩家列表随机旋转,`seat 0 == 北` 只是碰巧** ⚠️⚠️
    `CardTableBlockEntity.getOrderedPlayers()` 先用 `Collections.shuffle` 随机挑一个玩家当起点,
    再按 `PREDICATES`(八象限 `PY/PX_PY/PX/...`)绕桌排序,最后 `.reversed()`,得到的是**顺时针**序列。
    所以座位顺序确实是绕桌的,但**整圈被转了一个随机角度**——玩家坐哪个方位跟 `seat` 毫无关系。
    之前把"方位 == 座位索引"写死,导致每一副牌方位盘都是随机转的:坐北家可能被标成西家,
    自家手牌还会被画到屏幕侧边成竖列。

    正确读法:椅子 `FACING` **指向桌子**,所以 `GameChairBlock.getSeatedDirection(entity)` 的**反方向**
    就是玩家所在的方位(S 朝 → 玩家在北)。机器人和空位没有实体,但真实座位之间是顺时针连着的,
    用"已知座位里拟合度最高的那个旋转量"补全即可。
    `BridgeGame.compass(seat)` / `seatAtCompass(compass)` 是唯一的出口,客户端和服务端各自算
    (同一份名单 + 同一批椅子,结果必然一致,不用同步);`rebuildRoster()` 里要把缓存置空。
    凡是用到"方位"的地方都要换成 `compass(seat)`:手牌槽的屏幕位置与朝向
    (`BridgeLayout.apply` + `BridgeMenu.handType`)、铭牌、墩堆格子、收墩目标、明手黄框、
    叫牌表行序(行 = 方位,行里的座位用 `seatAtCompass(row)` 反查)。

43. **这个 shell 是 Windows PowerShell 5.1,没有 `` `u{XXXX} ``,而且 `Get-Content -Raw`/`Set-Content` 会毁掉 UTF-8** ⚠️
    `$PSVersionTable.PSEdition` 是 `Desktop`(5.1),不是 pwsh 7,所以 `` "`u{9225}" `` 拿到的是**六个字符**
    `u{9225}` 而不是那个汉字——要拼字符用 `[string][char]0x9225`。
    更阴的是 `Get-Content -Raw | Set-Content` 往返:默认按 ANSI 解码再编码,源码里的 em dash(`—`)
    会变成 `鈥?` 或 U+FFFD。读写一律走 .NET:

    ```powershell
    $s=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText($p,$s,(New-Object System.Text.UTF8Encoding($false)))  # $false = 不写 BOM
    ```

    (另外 `cd` 不改变 .NET 的 CWD,`[System.IO.File]::*` 必须用绝对路径。)
    已经中招的两种写法:`鈥?`(U+9225 + `?`)其实是 `—`,`鐢佃剳`(U+9422 U+4F43 U+5273)其实是 `电脑`;
    全文扫一遍 `[regex]::Matches($s,'[\uFFFD\u9225\u9422]')`。
    根因在 Gradle 侧也堵上了:`compileJava.options.encoding` 和 `processResources.filteringCharset` 都钉成
    UTF-8(坑 46),免得换台机器构建时把中文语言文件写成乱码。

44. **只打在客户端类上的 mixin 必须放进 `client` 块,否则专用服务端也会去套** ⚠️
    `fabric.mod.json` 的 `environment` 是 `*`,`bridge.mixins.json` 的 `mixins` 数组是**两边都加载**的。
    `GameScreen`/`CardSlotWidget` 都是 `dev.lucaargolo.charta.client.*`,放进 `mixins` 就等于让专用服务端
    也去解析客户端类——`required: true` + `defaultRequire: 1`,目标一旦对不上就是硬失败。
    Charta 自己的 `charta.mixins.json` 就是这么分的(`ChatComponentMixin`/`EntityRendererMixin`/
    `MinecraftMixin` 全在 `client` 里),照抄结构即可。只有 `CardSlotGeometry` 打在 common 的 `CardSlot` 上,
    留在 `mixins`。

45. **`BridgeBid` 用的是紧凑整数编码,非叫品码上的 `% 5` 会算出负数** ⚠️
    `PASS/DOUBLE/REDOUBLE` 是 0/1/2,`level()`/`strain()` 原来是裸算术:`strain(0)` = `(0-3) % 5` = **-3**
    (Java 的 `%` 对负数返回负值)。`glyph(-3)` 走 `default` 正好是 NT,所以肉眼看不出来,
    直到有人对返回值做算术。现在两个方法都对 `code < FIRST_CONTRACT` 短路:`level` 返回 0、
    `strain` 返回 `NOTRUMP`。

46. **要发布就得补的不是功能,是元数据——`mod_author` 还是模板里的 `You` 就是最典型的一个** ⚠️
    已做清单:`fabric.mod.json` 的 `authors`/`contact`/`icon`;`assets/bridge/icon.png`(128x128,从 560x560
    的 `contract_bridge.png` 降采样);根目录 `LICENSE`(MPL-2.0 全文)+ `README.md`;
    `depends.minecraft` 用 `minecraft_version_range`(不能写 `[1.21, 1.21.2)`,见坑 2),
    `depends.charta` 收紧到 `>=1.2.5 <1.3.0`——附属的 mixin 打在 Charta 的屏幕/槽类上,
    次版本一动就可能悄悄错位,宁可硬报不兼容;
    jar 内带 `META-INF/LICENSE_bridge`;`en_us.json` 里 `bridge.contract_bridge` 原本是中文「定约桥牌」
    (英文环境里一直显示中文)。
    另外删掉了一批没人引用的成员/资源(见变更记录):它们不报错,只让后来读代码的人多花时间。

47. **竖排手牌的"每张露出一截"有个写死的 10px 上限,光加长盒子是没用的** ⚠️ 本轮实测。
    `CardSlotWidget.renderWidget` 里两条分支不对称:横排是拿声明宽度反解步进
    (`leftOffset = 槽宽 - 牌宽` 再被摊平),所以槽宽直接等于扇面长度;竖排是**先给 10f 再往下压**:

    ```java
    topOffset = 10f;
    if (topOffset * (size-1) + CardSlot.getHeight(DEFAULT) > getPreciseHeight())
        topOffset = (getPreciseHeight() - CardSlot.getHeight(DEFAULT)) / (size - 1);
    ```

    即 `步进 = min(10, (高-52.5)/(张数-1))`。13 张牌在 160 高的盒子里已经是 8.96,盒子加到
    172.5 以内全是 10——**再往上加高,卡片间距一点不变**,表现是"盒子明明拉长了,扇面没动、
    下半截空一截"。修法:`@ModifyConstant` 把那个 10 换成一张牌的高度(52.5),上限就变成"整张牌",
    步进随盒子连续变化。
    - ⚠️ `renderWidget` 里有**两个一样的 `ldc 10.0f`**(另一个是横排的 `childWidth/10f` 余量),
      必须用 `@Constant(floatValue = 10f, ordinal = 1)` 点名;反编译 release jar 确认两者在
      字节码里相隔 148 条指令、横排那个在前(偏移 119 / 267),`ordinal = 1` 就是竖排那个。
      不加 `ordinal` 会把横排的余量一起改掉。
    - ⚠️ 用 `@ModifyConstant` 而不是 `@Redirect`:这里要换的是常量本身,`@Redirect` 只能换方法调用。
    - 这条没有按"是不是桥牌槽"做守卫,Charta 自己的竖排手牌也会受影响:它的槽高 112.5,
      旧上限对满手牌本来就够不到,只有 ≤6 张时才会压步进——所以是变宽松,不会变挤。
    - 顺带记一个约束:西/东那条盒子不是想多长就多长。`progress` 被拖到左侧后从 y=284 起、
      `tricks` 从 285 起,而手牌是在 `renderBg` **之后**画的(坑 18),重叠就是文字被牌盖掉。
      所以西/东的实际可用区间只有 y=60(北家下沿)到 284,即 224 高、步进 14.3。
      而"作者那份拖到两侧的存档"现在已经是默认布局本身,见坑 48。

48. **"按我现在的布局当默认"——先看清楚 `(int)` 截断把存档里的值吃成了什么** ⚠️
    `BridgeLayout.bounds()` 是 `anchor[1] + (int) dy(element)`,**先截断再相加**,不是
    `(int)(anchor + dy)`。所以存档里的 `-158.66667` 渲染出来是 `-158`、`19.999979` 是 `19`——
    精度在小数点后第一位就被丢掉了,而 `save()` 又把丢掉之前的值写回去,于是文件里的分数
    永远存在、又永远不起作用。落地成默认值时必须按**截断后的整数**写:
    真正在屏幕上生效的是 `pile dy=+27`、`contract dy=-156`(dx 均为 0)、
    `turn dy=+17`、`progress (-158, 19)`、`tricks (177, 9)`,其余 10 个元素全是 0。
    - 唯一一处**故意加 1**:`progress.dy` 写 20 而不是 19。截断值是 19 → 文本顶边 y=283,
      正好压在西家柱子最后一张牌的底行(柱底 284,画到 283);改成 20 后文本顶边 284,
      和柱底严丝合缝错开。原值 `19.999979` 本来就该是 20,是拖拽换算的浮点尾巴。
    - 实现:`Element` 的 `{dx,dy,scale}` 里直接存默认值(`defaults(Element)`,构造时 `resetAll()`),
      `reset()`/`Ctrl+R` 复位到这份默认而不是 0。`VERSION` 必须跟着升到 5——
      否则旧文件会以"几乎一样但差 1px"的姿态盖掉新默认,现在文件降级成**覆盖层**。
    - ⚠️ 副作用:默认值不再是"锚点原位"。以后再改 `anchor()` 里的常量,存过档的人看不到变化,
      必须先升 `VERSION`(这条本来就写在 `VERSION` 的注释里,现在是硬约束了)。

### 部署

```bash
cd D:/deepseekharness/charta-bridge
./gradlew deploy          # 构建 + 复制到 PCL2 实例 mods，并清掉旧的同名 jar
```

目标目录可用 `-Ppcl2ModsDir=...` 覆盖。

### 变更记录

- **2026-09-30** **四家手牌加长、西东外靠**（作者反馈"每张牌露出的部分有点小"）。
  - 横排（北/南）：盒宽 160 → 300，`x` 从写死的 `SOUTH_X=240` 改成按屏幕居中
    （`(屏宽-宽)/2`），铭牌跟着走。13 张牌的单张露出量 10.2 → 21.9 px。
  - 竖排（西/东）：盒高 160 → 224，上沿钉在北家下沿 y=60、下沿 284；
    这需要先松开 Charta 里那个写死的 10px 步进上限，新增
    `mixin/CardSlotWidgetColumnStep.java`（`@ModifyConstant` + `ordinal=1`，理由见坑 47）。
    单张露出量 8.96 → 14.3 px。
  - 224 而不是更长：`progress`/`tricks` 两条状态行在作者的存档里被拖到了两侧、
    分别从 y=284/285 起，而手牌画在 `renderBg` 之后，越过就会盖住文字。
  - `layout.version` 一度**保持 4**（只有手牌盒变长、位置外移，手牌存的 `dx/dy` 都是 0），
    但在作者要求"按我现在的布局当默认"之后**升到 5**：那份存档里的 5 个非零元素被搬进
    `BridgeLayout.defaults()` 成为出厂默认（见坑 48），旧文件必须丢掉，否则它会以
    "几乎一样但差 1px"的姿态盖掉新默认。手牌锚点从"左边缘"换成"居中"、
    西/东从写死的 `x=120` 换成按空条推算，都会让拖过手牌的人感到偏移，
    但存档里四个手的 `dx/dy` 都是 0。
  - 西/东随后再往外靠:锚点常量 `WEST_X=120` 换成 `columnLeft()`,把 38 宽的柱子摆到
    扇面两侧那 170px 空条的正中 → `x=66`,左边距与到扇面的间隙都是 66px、左右完全对称。
    写成从扇面宽度推导而不是写死 66,是为了以后改扇面长度时柱子跟着走、不会撞上。
  - 验证:构建 `clean deploy` 通过(只剩 3 条已知 `@Accessor` 警告);`javap` 核对 `Element`
    常量(300/53、38/224)、`columnLeft` 的算式、`defaults()` 的 tableswitch 分支与 7 个常量、
    refmap 里新 mixin 映射到 `method_48579`、注解上是 `floatValue=10.0f, ordinal=1`;
    另用脚本按公式把 13 个盒子(**只用出厂默认,不含存档**)全列了一遍,
    四家手牌/铭牌/墩堆/四条状态行两两零重叠、左右边距各 66px,
    `progress` 文本顶边 284 = 西家柱底 284(严丝合缝错开)。

- **2026-09-30** **发布前整理**（0.1.0 → 1.0.0，署名 BigMer，仓库 `github.com/BigDare819/charta-bridge`）。三类：
  ①**元数据**：`fabric.mod.json` 补 `contact`/`icon` 并把 `minecraft`/`charta` 依赖换成范围断言
  （`>=1.21 <1.21.2` / `>=1.2.5 <1.3.0`，注意不能用 `[1.21, 1.21.2)` 写法，见坑 2——这条是老坑，
  写这条记录时顺手用真加载器验了一遍才发现"逗号空格"的说法是错的、maven 区间整个不生效），
  新增 `LICENSE`（MPL-2.0）、`README.md`、128x128 `icon.png`，jar 内带 `META-INF/LICENSE_bridge`，
  `build.gradle` 钉死 UTF-8 编码（坑 46）；`en_us.json` 的玩法名从中文改回 `Contract Bridge`。
  ②**删死代码**：`BridgeBid.higherStrain/glyph(Suit)` 与 `name/symbol` 上没人用的 `Deck` 参数（10 个调用点）、
  `Auction.dealer/codes/codeOrNull` 与冗余的 `seats` 列表（`seatOf(index)` 已经等价）、
  `BridgeMenu.guiHeight/getPileSlot/getAuctionSeat/getTrump/canPlayNow/contractName/strainSymbol`、
  `BridgeGame.getActingSeat`、`BridgeFrame` 的三个 getter、`BridgeLayout.handLeft/handTop`（调用点本来就
  已经算过 `bounds`）、未使用的 `textures/gui/bridge.png` 与 `message.bridge.partner_tricks`；
  `handSlotIndex` 从"没人用"改成 `BridgeLayout.apply` 真的调它，槽位下标不再两边硬编码 `1 + i`。
  ③**热路径与可读性**：`BridgeScreen` 增加按叫品码索引的文案/宽度缓存（叫牌盒 35 格每帧重建组件，
  用 `Language` 实例当失效键），`BridgeFrame.current()` 按窗口尺寸缓存不再每帧 new，
  `BridgeLayout` 的偏移从 `LinkedHashMap` 换成按 `ordinal` 的数组，F9 覆盖层的元素名改走
  `editor.bridge.*` 语言键（原来硬编码中文），`BridgeBid.level/strain` 对非叫品码短路（坑 45），
  只打客户端类的 6 条 mixin 移进 `mixins.json` 的 `client` 块（坑 44），修掉 `BridgeScreen`/`BridgeLayout`
  里遗留的 mojibake（`鈥?`→`—`、`鐢佃剳`→`电脑`，坑 43），`BridgeScreen` 顶部那段"座位索引就是方位"的
  过期注释重写，`how_to_play` 两版 md 里"你永远是南家"改成"坐哪边就是哪边"。
  自检：clean build 无警告（只剩 3 条已知的 `@Accessor` 无映射提示），jar 110,690 字节、
  SHA256 `CE6D930562D1614EACF6239283AA6D5CD6BF27841341A38007AA5F965808C6E4`，
  javap 逐条核对增删成员、三个 json 解析通过、en/zh 各 72 键且键集完全一致、
  用真加载器把 `fabric.mod.json` 的两条依赖谓词对各版本跑了一遍（坑 2）。

- **2026-09-29** 玩法改名 **Contract Bridge**（注册 id `bridge:contract_bridge`）；
  牌桌改成东南西北四方位（中央墩堆、上方北家、左右西/东家、底部自家手牌，视角随本地玩家旋转）；
  新增「空座位由电脑补齐」选项（`BridgeBot`，默认开，单人也测得了）；
  新增出牌跳墩停留、跟花色校验和成对计分；补了 `how_to_play` 说明页。
  已用调试牌桌跑完整局 13 墩无异常。
- **2026-09-29** 补齐真正的定约桥牌：发牌 → **叫牌（7×5 叫牌盒 + 过/加倍/再加倍，非法叫牌置灰）** →
  定约 → **明手摊牌、由庄家代打** → 复式计分（成局/满贯/加倍/宕墩递增罚分），
  四家皆过自动重新发牌。客户端叫牌走 `BridgeCallPayload`（Fabric C2S），其余状态复用 Charta 的
  `ContainerData`。新增 `bridge:suit` 字体提供 ♠♥♦♣ 与 NT（原版字体没有这四个符号），
  选单图标用 `bridge_icon.png`。自检：23 轮叫牌 → 7♣X，13 墩打完，宕 5 无局加倍 = -1100，无异常。

  > 字体坑：bitmap provider 的 `file` 是相对 `assets/<ns>/textures/` 解析的，
  > 所以 json 放 `assets/bridge/font/suit.json`，贴图必须放 `assets/bridge/textures/font/*.png`。
  > 放错只报一句 `FileNotFoundException` 然后静默丢掉整个字体。
- **2026-09-29** 叫牌界面加了局部模糊层（scissor + 淡霜，见坑 13）；四家的手牌从中央挤成一团
  改成摊到屏幕四周：北家吊在面板上方（-55），西家贴面板左边缘外 `-(FAN_WIDTH+20)`、
  东家贴右边缘外 `+20`，西/东/南三家统一在面板下方 188 处那条带里（见坑 14、15）；
  面板内部只留叫牌表（下移到 y24）和叫牌盒；铭牌带屏幕边缘钳制、明手黄框跟手牌同一套锚点。
  选单图标改为**原图 560×560 不缩放**。
- **2026-09-29** 叫牌改成**屏幕内的模态弹窗**（`BACKDROP = 0xC8101014` + 白描边 + 局部模糊，
  见坑 13），不再和整块面板绑定；新增 **F9 布局编辑器**：拖动移动、滚轮缩放、R 重置选中、
  Ctrl+R 全部重置，布局存 `config/bridge-layout.properties`。
  为此新增 `client/BridgeLayout.java`（元素表 + 屏幕尺寸推导的默认值 + 落盘）和
  `mixin/CardSlotGeometry.java` + `client/CardSlotAccess.java`（让 `final` 的槽坐标可写、
  并给槽加上可改的声明宽度，见坑 15/16），`bridge.mixins.json` 在 `fabric.mod.json` 注册。
  坐标约定整理进坑 14（三种 `CardSlot.Type` 的坐标系各不相同）。
  自检：dev client 跑完整局（叫牌 → 出牌 13 墩 → 结算）无异常，`BOARD DONE ... score=90`。
- **2026-09-29** 模糊改成正确的顺序：**先整屏糊、再画叫牌弹窗**。`BridgeScreen.render` 在
  `super.render` 前对叫牌阶段多跑一遍 `renderBlurredBackground`（半径叠加，见坑 13 的
  `getMenuBackgroundBlurriness() >= 1` 门槛），`renderPopup` 先铺一层**全屏** `SCRIM = 0x6B101014`，
  弹窗区域再压 `BACKDROP = 0x59101014`。F9 编辑模式右下角加**实时鼠标坐标**（屏幕坐标 + 弹窗局部
  坐标 + 指针下元素），坐标轮询 `MouseHandler` 而不是 `mouseMoved`（鼠标静止时不触发）。
- **2026-09-29** 删掉顶栏（`renderTopBar` 覆写成空，那 28px 让给北家手牌）；四家手牌改成
  **绝对盒 + 镜像**：南 `(240,300,160,53)`，北按屏幕水平中线镜像，西 `(120,100,38,160)`，
  东按竖直中线镜像。北/南从 `PREVIEW` 改成 `HORIZONTAL`、西/东改成 `VERTICAL`（见坑 21），
  `BridgeLayout` 重写成"`bounds()` 一律返回屏幕坐标"，`layout.version` 升到 3（丢弃旧偏移）。
  顺带发现并修掉一个老 bug：`CardSlot.getWidth/getHeight` 是静态的，之前 `bridge$setSize`
  写进去没人读、**手牌缩放一直是空操作**（见坑 19），补了 `CardSlotWidgetMetrics` /
  `GameScreenMetrics` 两个 `@Redirect` mixin；`method` 的 `remap` 要按"有没有覆写原版方法"分别处理（见坑 20）。

---

- **2026-09-29** 墩堆重做：叫牌阶段隐藏，出牌阶段按**东南西北四个格子**画（格内写 N/E/W/S，
  牌按"本墩首攻者 + i 个顺时针座位"落格，加了 `SYNC_TRICK_LEADER` 镜像）。原版牌槽只会把最后一张
  画在一个点上，于是让墩堆槽永远 `isEmpty()` 把原版画法整体挡掉（见坑 22），出牌时按 `currentTrick`
  重写墩堆顺序（见坑 23）；牌自己画，复刻 widget 的 4/3 外扩和 shader uniform（见坑 24）。
  发完牌按花色 + 点数排好手牌（服务端原地 sort，见坑 26）；明手的牌拖不回庄家手里
  （`createPlayerHand` 换成带 `pickupOwner` 门的 `GameSlot` 子类，见坑 25）。
  出牌状态栏从 y118 挪到 y238，让开中央的墩堆方块；布局版本升到 4。
- **2026-09-30** 两处看不清的修掉：墩堆格子的方位字母改成近黑 `0xFF262626` 并关掉阴影
  （格子底太透、桌布偏浅，浅灰反而糊）；叫牌的花色符号重画 —— `bridge:font/suit.png`
  换成手绘 16x16、偶数对齐的对称字形（贴图生成 + 预览脚本进了 `tools/`，见坑 27/28），
  并给 `BridgeBid.symbol` 的取色加了亮度下限，黑桃/梅花不再是 `#5C5C5C` 那种深灰（见坑 29）。
  字号/行高/advance 都没变，`layout.version` 仍是 4。
- **2026-09-30** 手牌顺序修正为 **A 最大**（原来按 `Ranks.ordinal` 排、A=1 会被反到最末，见坑 30）；
  新增**收墩动画**：一墩打完，四张牌错开收拢到墩堆中心，再整体缩小飞向赢家的手牌位置
  （`SYNC_TRICK_WINNER` 上升沿触发 + `Util.getMillis` 计时，见坑 31）。
  整套演出约 1.05s，落在原本 25 tick 的收墩停顿里，服务端行为没动。
- **2026-09-30** 叫牌的花色换成**手绘像素素材**（`tools/suits/{spade,heart,diamond,club}.png`，
  梅花/红心/方片/黑桃各 9~12px，原尺寸贴进 48x12 的 `bridge:font/suit.png`，1:1 上屏，见坑 27/28）。
  生成时把所有通道等比提到最亮 `0xE6` 以适配深色底，`BridgeBid.symbol` 取消 tint 让素材自带颜色上屏（见坑 29）。
  `bridge:suit` 字号从 8px 提到 12px，随之调整：叫牌盒/表格/状态栏的居中算式换用 `SUIT_GLYPH`、
  状态栏四行从 238/250/262/274 改成 238/252/264/276、悬停提示挪到标题行；
  顺带修掉结算面板一个老 bug —— 四行文字原本按 `top - height / 2 + n` 算，实际画在 y≈6~40，
  离面板（y≈146）差了大半屏，现在按面板自身顶端排版。
- **2026-09-30** 修掉**赢墩判定把 A 当最小**的 bug：`findTrickWinner` 用的是枚数序数比较
  （`Ranks.compareTo`，A=1），所以跟了花色的 Q 会把 A 顶掉、墩飞给错的人（见坑 32，坑 30 的漏网）。
- **2026-09-30** 屏幕下方的四行出牌状态改成**逐行可拖**：`BridgeLayout` 新增
  `STATUS_CONTRACT/STATUS_TURN/STATUS_PROGRESS/STATUS_TRICKS` 四个元素，各是一条 150x10 的
  "抓手"，文字在抓手内居中（拖完仍保持居中）、滚轮缩放会连字号一起缩，F9 框和点击命中都用同一个盒。
  同时把四行的 y 从写死的 238/252/264/276 改成**以屏幕底边为基准**（-122/-108/-96/-84），
  窗口变矮时不再整摞掉出屏幕。`layout.version` 仍是 4，旧配置文件能直接用。
- **2026-09-30** 花色字形从 12px 缩到 **9px** 墨高：素材原样贴时黑桃/梅花比 8px 的数字高一半，
  看着突兀。现在生成脚本把四个花色和 NT 都归一到 9px 墨高（格子 13x12，`height` 仍 12 所以
  仍是 1:1 上屏，`ascent` 11→10 让墨迹压在数字基线上），字距随之从 13 收到 10~13（见坑 28）。
  缩小用的是**按覆盖采样**而不是点采样：黑桃尖端是正中那一列的单个像素，点采样在 11→8 时
  一次都采不到、尖端会消失，见坑 28b。`SUIT_GLYPH` 12→9，叫牌表行文字 y+1→y+2。
- **2026-09-30** 出牌阶段**轮到谁，谁的墩位格子就亮**：格子上浮一层该座位颜色的呼吸脉冲
  （alpha 0x30~0x80）加 2px 描边，颜色取自 `getPlayerAtSeat(...).getColor()`，和铭牌同色。
  用 `SYNC_TRICK_WINNER < 0` 当"这墩还活着"，赢家一公布就熄灯、交给收墩动画（见坑 33）。
- **2026-09-30** 度数调整：上一版把花色缩到 9px 时用的是**点采样**，黑桃的尖顶和梅花两侧的缺口
  被整个跳过（作者反馈"贴图怎么变了"）。改成**按游程分配**（见坑 28b）并把目标高度放宽到 **10px**
  （坑 28 重写：缩得越狠越容易"编形状"）。图集 52x12→56x12、格子 13x12→14x12、
  `ascent` 10→9（墨迹占屏幕行 -2..7，与数字 -1..6 同心）、`SUIT_GLYPH` 9→10。
  `nt.png` 同时重画：原来 T 的竖和 N 的斜都是 2px，显得太粗，现在是全 1px 笔画。
- **2026-09-30** 花色**彻底不缩了**：10px 归一化虽然没丢特征，作者对着原图还是反馈"有区别、看着
  有点变形"。现在格子直接开到最大素材的尺寸 **13x12**（= 11x12 黑桃/梅花 + 左右各 1px 边距），
  四个花色素材原像素拷贝、各自居中，`suit.png` 52x12 / `nt.png` 13x12，一个像素都不重采样
  （坑 28 重写；游程重采样那套作为"以后真要缩时"的办法留在坑 28b）。
  `ascent` 仍 9（12px 黑桃落 -3..8，和数字 -1..6 同心，中心都在 2.5），
  但 `SUIT_GLYPH` 10→8：墨高不同的两个字共用的是中心不是高度，居中要按数字的 8px 传（坑 27）。
  逐格比对原图确认 `mask-mismatches=0`、`maxChannelDev=0`。
- **2026-09-30** 叫牌弹窗**加高 170→186**，把 pass/double/redouble 那一行整个包进面板
  （原来那行在 y=165..177，面板 170 把三个按钮切了一半）。宽高不进配置文件，改默认值即时生效，
  `layout.version` 不动（坑 34）。
- **2026-09-30** 叫牌记录改成**带轮次表头的网格**：四行"东南西北"上方加一行**轮数**
  （1、2、3…），每个叫品按"这是第几轮"居中落到对应列下、行则按座位。
  **一列是一整轮（4 手）而不是一手**——一手里"第 7 个叫品"没人会数，一轮四家各叫一次才是个
  能读的单位；这样网格也正好是"每格一手"（每家一轮只叫一次），永远不会有两手挤在一格里。
  （前两版先做过"每行右侧一列该座位叫了几手"，作者要的是行；再做过"每手一个序号"，作者要的是一轮一次。）
  窗口**右对齐到最新一轮**：一局能叫十几轮、固定宽度的面板放不下，所以只显示末尾几列，
  这样表头里的数字是真实轮数、最新一手永远在屏幕上，也不会因为下一手叫出来就整排左移。
  当前那一轮的编号用 `ACTIVE` 高亮，其余暗色；表头下加一条 1px 分隔线。
  列数按面板宽度算（`callColumns()`，当前 5 列 x 27px），列宽取叫牌盒里能出现的最长叫品（`pass`）。
  表格几何改成自上而下推导（`TABLE_TOP = TABLE_HEAD_TOP + TABLE_HEAD_H`、`BOX_Y = TABLE_TOP + 4*行高 + 2`），
  面板随之 150x186 → **168x192**、`BOX_X` 15→24，`PANEL_WIDTH/HEIGHT` 同步（坑 34）。
- **2026-09-30** 修掉**鼠标悬停手牌掉帧**（120fps→35fps）：Charta 的
  `GameScreen.render` 与 `CardSlotWidget.renderWidget` 都在"画每个 renderable 之前先把当前 hover
  重画一遍"，外层那个 renderable 又是整手牌，两层一乘就是每帧上千张牌。新增两个空实现的 `@Redirect`
  （`GameScreenHoverCost`、`CardSlotWidgetHoverCost`）删掉循环里那次，保留循环后最上层那次——
  最终像素完全相同。悬停时的手牌绘制从约 1400 张降到约 78 张（见坑 36）。
  注意这两条只改了 `GameScreen`/`CardSlotWidget`，没动 `CardScreen`（套牌详情本来就是好的）。
- **2026-09-30** UI 改成**任何窗口尺寸都完整显示**：以界面尺寸 3（= 1920x1080 的 640x360，
  也就是整套布局的原尺寸）为**设计帧**，按窗口等比缩放并居中，永不超过任一轴。
  新增 `dev/bridge/client/BridgeFrame`（映射 + push/pop + 鼠标换算）。
  `BridgeScreen.init()` 把 `width/height` 改写成 640x360，`render()` 把整个原版 pass 包进帧 pose
  并把鼠标换算进去，四个 `mouse*` 覆盖在入口换算、F9 的拖拽增量按 scale 折算。
  `renderPopup` 的裁剪框改传窗口像素（`enableScissor` 不认 pose），SCRIM 移到 pose 之外盖满整窗。
  新增两条 mixin：`GameScreenChatFrame`（把聊天移出帧，避免和 `Gui` 那份错开）、
  `GameScreenFrameTick`（`containerTick` 的鼠标换算，否则悬停倾角会放大 1/scale 倍），
  两条都带 `instanceof BridgeScreen` 守卫，因为 `GameScreen` 是 Charta 自家游戏共用的基类。
  1920x1080 开界面尺寸 3 时 `scale=1、origin=(0,0)`，画面与改动前逐像素一致（坑 37）。
- **2026-09-30** 修掉**多人下"每个玩家视角里的南家都是自己"**：方位从"相对本地玩家"改成
  **座位索引就是方位**（0 北 / 1 东 / 2 南 / 3 西，正好等于游戏顺时针的下一家方向），
  屏幕不再随视角旋转——所有人在屏幕上方看到的都是同一个北家，说"东家那张牌"指的是同一个人。
  清掉了 `BridgeScreen.COMPASS`/`labelSuffix`/`renderPile`/`winnerAnchor`/`highlightDummy`/
  `renderSeats`/叫牌表行序里全部 `seat - localSeat`；`BridgeMenu.HAND_ORDER` 由偏移表
  `{2,1,3,0}` 变成座位表 `{0,3,1,2}`，槽位朝向改由座位方位决定（坐东/西时自家手牌是竖列）。
  新增 `Element.SOUTH_PLATE`（南家原先没有铭牌元素，本地玩家那块是渲染时的特例）
  和 `BridgeLayout.hand(compass)/plate(compass)`；`seat.bridge.you` 由「南 - 你」改成后缀「 - 你」，
  铭牌读作「北 - 你」。`layout.version` 不动，作者已存的偏移继续有效（坑 38）。
- **2026-09-30** 四条修补：①补上漏掉的 `seat.bridge.south` 语言键（南家铭牌之前一直借 `seat.bridge.you`
  显示，改成绝对方位后就露出来了）；②`BridgeScreen` 覆盖 `renderBottomBar` 改成**空的**：
  先删掉 Charta 那条 63px 全宽黑带的左右两截（黑带比 160 宽的手牌宽得多，两端看着就是挂在手牌
  旁边的两根空条），再删掉剩下中间那条约 47px 的座位色小条——它比手牌窄得多，只会在牌缝里露出一块
  暗色，读起来不像底栏只像块阴影；手牌本来就有自己的铭牌，南家那条不需要任何背景；
  ③明手的手牌只有庄家能动，明手自己也不行（`BridgeGame.canHandleHand` + `HandSlot` 的
  `canRemoveCard`/`canInsertCard`，见坑 40）；④修掉明手自己的手牌会变成牌背：菜单 `HandSlot`
  的 `shouldUpdate` 改成 `!isDummy(本地玩家)`，不再把明手那份"真手牌"当 censored 槽刷成空白牌（坑 39）。
- **2026-09-30** 叫牌结束不再瞬间关面板：`BridgeScreen.containerTick` 盯 `phase` 的 **AUCTION → PLAY/DEALING
  边沿**（没有任何同步字段说"叫牌刚结束"，只能自己看边沿），记下时间戳后把叫牌面板**多留 2 秒**
  （`AUCTION_RESULT_MS`），下半张面板从叫牌盒换成结果：2 倍大的定约（带花色字形和加倍标记）、
  "由 X 主打"、有局/无局；标题改成「叫牌结束」，四家皆过则显示「四家皆过 · 流局」。
  这 2 秒里不画墩堆和四条状态行（会被面板压住），点击叫牌盒仍然忽略（`canClickNow` 已 false）。
  另外**定约行加上有局/无局**：`message.bridge.contract_line` 多一个 `%s`，
  新增 `message.bridge.vulnerable`/`not_vulnerable`（有局红色）。
  注：`containerTick` 在发布 jar 里是 `method_37432`，覆写它不影响 `GameScreenFrameTick`
  （那条 redirect 在 `GameScreen` 自己的方法体里，超类调用照样走）。
- **2026-09-30** 两条：①修掉叫牌结束那张结果面板上"加倍/再加倍"显示成方框——
  `suit.json` 末尾从 `minecraft:include/default` 换成 `minecraft:default`（前者没有 CJK），
  并把 `headline.copy().append(doubling)` 换成 `Component.empty().append(...)`，
  别让「加倍」继承花色字体（见坑 41）；顺带补上结算面板 `contract_line` 少传的第四个参数
  （之前会漏出一个字面 `%s`）与加倍标记。②方位改成**按真人实际坐的椅子**分配：
  新增 `BridgeGame.compass(seat)`/`seatAtCompass(compass)`（椅子朝桌子，取反方向即玩家所在方位；
  机器人/空位按已知座位的顺时针延续补全），座位→屏幕位置的所有入口
  （`BridgeLayout.apply`、`BridgeMenu.handType`、铭牌、墩堆格子、收墩目标、明手黄框、
  叫牌表行序）全部改用 compass 而不是座位索引（见坑 42）。
  自检：首轮方位不再是随机的，坐北的玩家手牌在屏幕上方、铭牌读「北」。

## 7. 许可

Charta 是 **MPL-2.0**。做附属、改它、发布都行；改了 Charta 自身的文件再分发时需要开源对应文件。

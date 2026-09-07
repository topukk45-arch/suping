# 素屏

每天自动把安卓手机的桌面和锁屏换成必应当日壁纸。做这个是为了替掉只用来换壁纸的
微软桌面（1GB），本体两三兆。

包名 `com.baofu.suping`，跟「记问」是两个独立的 App，互不影响。

## 名字

取自白居易《素屏谣》：「素屏素屏，虚白空闲。」

原诗是夸不加装饰的屏风，跟一个专往屏幕上贴图的 App 正好拧着——这层反讽是有意留下的。
「屏」字古为屏风、今为屏幕，一个字打通两头；「素」则是这个 App 的自我要求：
没有账号、没有推送、没有广告，一张图铺满，别的什么都不做。

## 拿到 APK

1. 把整个文件夹上传到 GitHub 仓库（**别漏掉 `.github` 目录**，APK 靠它构建）
2. 在仓库 Settings → Secrets and variables → Actions 里加四个 Secret：
   `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
   （可以直接复用记问那套 keystore）
3. Actions 里等「构建安卓 APK」跑完，Artifacts 下载 `suping-apk`

Secret 没配也能跑，只是产出的是随机调试签名，换版本时装不上去、要先卸载。

### JDK 和 Gradle 是绑死的

workflow 里装的是 **JDK 17**，别随手改大。Gradle 8.2.1 最高只支持到 JDK 20，
用 21 会在配置阶段就报 `Unsupported class file major version 65`，连编译都进不去。
真要升 JDK，得先把 `android/gradle/wrapper/gradle-wrapper.properties` 里的
`gradle-8.2.1-all.zip` 换成 8.5 以上，两个一起动。

## 装好之后第一件事

打开 App → 底部「电池优化设置」和「应用信息 / 自启动」，把这个 App 放行。

不做这步，后台任务在国产 ROM 上基本不会执行，表现是"装了几天一次都没自动换过"，
很容易误判成代码有问题。

## 图标

「屏」字取自古帖行书，二值化后描摹成单条矢量路径，不是字体、不依赖任何外部文件。
底色纯白 `#FFFFFF`，墨色 `#5E5546`（和 App 里主按钮同色）。

| 文件 | 用途 |
|---|---|
| `drawable/ic_launcher_foreground.xml` | 自适应图标前景，矢量 |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ 主题图标单色层，跟随壁纸取色 |
| `drawable/splash_logo.xml` | 启动画面上的字 |
| `values/ic_launcher_background.xml` | 图标底色 |
| `mipmap-anydpi-v26/` | 自适应图标定义 |
| `mipmap-*/*.png` | API 25 及以下的旧版图标（minSdk 是 24，这两档还在） |
| `art/` | 矢量原件、商店图 512、原始拓片 |

画布 108dp，字高 52dp。圆形遮罩的安全区是 66dp，字完全在里面，
所以圆形 / 圆角方 / squircle / 方形，各家 ROM 怎么裁都切不到笔画。

改颜色：墨色改 `ic_launcher_foreground.xml` 的 `fillColor`，底色改
`values/ic_launcher_background.xml`。单色层不用管，系统会自己取色。
想让图标底和 App 页面底统一，把底色换成 `#F7F0E6`。

启动画面上那个字如果不想要，删掉 `styles.xml` 里
`windowSplashScreenAnimatedIcon` 那一行即可，其余不受影响。

空态（还没取到壁纸时）里也有同一个字，压到 16% 透明度当背景，
路径直接内联在 `index.html` 里，约 11KB。

### 覆盖安装看不到新图标

启动器对图标缓存得很凶。改完图标要么 `./gradlew clean` 再装，要么直接卸载重装，
否则桌面上大概率还是旧图，容易误判成没生效。

## 后台是怎么跑的

| 层 | 机制 | 角色 |
|---|---|---|
| 闹钟 | `setAndAllowWhileIdle`，每天 00:10 | 主力，夜里就把图换好 |
| 周期任务 | WorkManager 每小时 | 兜底，防闹钟被 ROM 清掉 |
| 解锁 | `USER_PRESENT`，半小时限流 | 兜底 |
| 手动 | 打开 App / 「立即更换」按钮 | 主动介入，强制重设 |

四层全都调 `WallpaperUpdater.runOnce()`，靠接口返回的 `hsh` 比对决定要不要真动手，
重复触发零成本。

判断「今天换没换」用 `hsh` 而不是本地日期 —— 手机时钟、时区、必应 CDN 推送时间
三者都可能对不上。必应 zh-CN 的换图时刻是北京时间 00:00（接口的 `fullstartdate`
是 UTC，恒为 `startdate` 加 16:00）。

用 `setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：两者都能穿透 Doze，
但后者在 Android 12+ 要申请 `SCHEDULE_EXACT_ALARM` 权限。前者不精确（定 00:10 可能
00:25 才响），对换壁纸无所谓。

**闹钟在关机后会被系统全部丢弃**，所以 `BootReceiver` 里必须重设。漏了的话表现是
「重启手机之后第二天就不换了」，极难排查。

## 滑动浏览 / 全屏预览 / 设为壁纸

左右滑看最近 8 天，点图进全屏预览（双击或双指放大，放大后单指平移，
单击或下拉关闭，实体返回键先关预览再退出）。滑到往期时主按钮从
「立即更换」变成「设为壁纸」，按下去就把那一张设上。

必应接口不返回跨域头，网页层直接 `fetch` 会被 CORS 挡下来，所以列表走原生的
`getRecent()`（缓存一小时）。但图片不受同源限制，直接挂 `https://` 链接就能加载，
往期图片零本地存储。第 0 屏在本地图和今天的图对得上时用本地文件（秒开、断网可看），
对不上就改挂远程直链 —— 图和说明必须是同一张。

### 选了往期图，自动更新怎么办

用 `pin_day` 解决，**不去动「每天自动更换」那个开关**。

按下「设为壁纸」时，把当下必应「今天」那张图的 `hsh` 记进 `pin_day`。之后每次
`runOnce()` 先看这个值：

- 拉回来的 `hsh` 还等于 `pin_day` → 必应没换图，用户挑的那张留着不动，直接返回
- 对不上了 → 新的一天到了，清掉 `pin_day`，照常换成今天的图

所以效果是「今天就看这张，明天 00:10 自动恢复」。用 `hsh` 而不是本地日期，
理由和 `last_id` 一样：手机时钟、时区、CDN 推送时间三者都可能对不上。

用户自己按「立即更换」（第 0 屏）时 `force=true`，也会顺手清掉 `pin_day`。

## 代码结构

```
www/index.html                     整个界面，唯一的网页文件
android/app/src/main/java/com/baofu/suping/
  WallpaperUpdater.java            全部业务逻辑（取图 / 比对 / 下载 / 设壁纸 / 固定）
  WallpaperPlugin.java             网页层 ←→ 原生层唯一通道
  UpdateScheduler.java             WorkManager 任务启停
  AlarmScheduler.java              每天 00:10 的闹钟
  AlarmReceiver.java               闹钟响 → 排任务 + 排明天的闹钟
  DailyWorker.java                 实际执行更新
  BootReceiver.java                开机 / 升级后重排
  UnlockReceiver.java              解锁时检查
  App.java                         进程启动时注册解锁广播
  MainActivity.java                注册插件 + 防启动白屏
```

WebView 不是这个 App 的中心，只是它的设置界面 —— 换壁纸发生时 WebView 通常根本
没启动。所以 `WallpaperUpdater` 里不能有任何依赖 Bridge / WebView 存活的代码。
两层之间靠 `SharedPreferences` 当唯一真相源。

## 配色

取自「记问」的实际取色，两个 App 共用一套。

| 用途 | 值 |
|---|---|
| 页面底 | `#F7F0E6` |
| 凹陷底 | `#F1E6D0` |
| 正文 | `#3A3529` |
| 主按钮 / 开关打开 | `#5E5546` |
| 报错 | `#7F3B32` |

改底色要同步四处，漏一处启动时会闪色：`www/index.html` 的 `--bg`、
`res/values/colors.xml` 的 `appWindowBackground`、`MainActivity.java` 的 `BG`、
`capacitor.config.json` 的 `backgroundColor`。

图标底色是第五处，但它**故意不跟着走**——桌面上纯白比米色干净。

## 已知的不确定项

- **原生代码从未编译过**，第一次构建大概率要红一两次
- **锁屏壁纸**：`FLAG_LOCK` 在 MIUI / HyperOS / EMUI 上可能被忽略。代码里桌面和
  锁屏分两次设置，锁屏失败不影响桌面，界面会明确提示
- **竖版图有没有必应 logo**：用的是 `urlbase + _1080x1920.jpg`。真有 logo 就改
  `WallpaperUpdater.SUFFIX`，但换成 `_UHD.jpg` 是横图，手机上两侧会被裁掉
- **解锁广播**只在进程还活着时有效，属于尽力而为的兜底
- **启动画面的字**用的是 `windowSplashScreenAnimatedIcon`，靠 androidx
  core-splashscreen 兼容到 API 24。个别魔改 ROM 可能不显示，不影响启动

## 在电脑上预览界面

直接用浏览器打开 `www/index.html` 会进「浏览器预览模式」，用假数据填充。想看真实
照片就把 `index.html` 里的 `DEMO_IMG` 填成图片网址。记得用 F12 设备模拟看手机尺寸。

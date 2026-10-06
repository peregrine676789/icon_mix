# IconMix —— 文件夹图标合成工具

把文件夹里的多个程序（`.exe` / 快捷方式）的图标合成为一张宫格图，作为该文件夹在资源管理器中的图标；也可以只导出结果图片，不改动任何系统配置。

需求与验收口径见 [REQUIREMENTS-文件夹图标合成工具.md](REQUIREMENTS-文件夹图标合成工具.md)。

---

## 一、在 IDEA 里打开与运行

### 打开

1. IDEA → `File` → `Open`，选择本目录（`icon_mix`）。
2. 工程是**普通 Java 模块**（不是 Maven/Gradle 工程），已附带：
   - `IconMix.iml`（模块定义，源码根 = `src/main/java`，输出 = `out`）
   - `.idea/modules.xml`、`.idea/misc.xml`、`.idea/encodings.xml`
3. 若 IDEA 提示 JDK 未配置，在 `File → Project Structure → SDK` 里选本机 JDK（本机为 `C:\Program Files\Java\jdk-26`）。
   语言级别用默认的 8 即可，源码只用了 Java 8 的语法与 API。

### 运行

- **图形界面（主用法）**：直接运行 `iconmix.Main`（`main` 方法无参数）。
- **命令行**：给 `iconmix.Main` 加上程序参数，例如
  `--export-only --layout row "D:\games\forGame"`。
- 也可以在 IDEA 里新建 Application 配置，Main class 填 `iconmix.Main`，Working directory 用模块根目录。

### 命令行编译（不想用 IDEA 时）

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java -Path src\main\java).FullName
java -cp out iconmix.Main
```

> 注意：源码含中文，**必须**加 `-encoding UTF-8`，否则会报「编码 GBK 的不可映射字符」。

---

## 二、用法

### 图形界面

1. 把文件夹**拖进窗口**（或点「选择文件夹」）。
2. 在预览区里**按住某个图标拖到另一个格子上**即可交换位置；左上角可切换「横优先 / 列优先」，也可「恢复默认顺序」。
3. 选择落地方式：
   - **应用到文件夹**：写 `desktop.ini`（UTF-16LE）+ 设置文件夹只读 + 通知资源管理器刷新；
   - **仅导出图片**：只在 app 可执行文件所在目录产出结果图片，不动任何文件夹配置；
   - **另存为…**：自选位置与文件名。
4. 「还原文件夹图标」可撤销「应用到文件夹」。

### 命令行

```
IconMix                                 启动图形界面
IconMix <folder> [folder...] [选项]      命令行处理
IconMix --verify <file.ico|file.png>     校验 ICO / PNG 结构
```

| 选项 | 说明 | 默认 |
|---|---|---|
| `--layout row\|column` | 排布偏好 | 自动 |
| `--export-only` | 仅导出图片，不写文件夹配置 | 关 |
| `--apply` | 直接应用到文件夹（写 desktop.ini） | 关 |
| `--revert` | 还原文件夹图标配置 | 关 |
| `--out-dir <dir>` | 导出目录 | app 可执行文件所在目录 |
| `--name <name>` | 导出文件名（不含扩展名与日期后缀） | `{目录名}_icon` |
| `--sizes 256,128,64,...` | ICO 尺寸集合 | 256,128,64,48,32,16 |
| `--ico on\|off` | 是否同时导出 `.ico` | on |
| `--depth <n>` | 扫描深度 | 4 |
| `--keep-history` | 保留历史产物（默认导出前清理旧产物） | 关 |
| `--quiet` | 静默模式 | 关 |

退出码：`0` 成功 / `1` 部分失败 / `2` 失败。

### 导出命名规则

- 默认文件名：`{拖入目录名}_icon({当日日期}).png`，如 `forGame_icon(10.5).png`（括号为英文半角）
- 导出前会**清理**导出目录下所有匹配的旧产物（`{目录名}_icon.png`、`{目录名}_icon(日期).png/.ico`），
  保证同一目录名只留最新一张；`--keep-history` 可关闭该行为
- 匹配从严：`forGame_iconbackup.png`、`forGame_icon(旧).png` 等**不会**被删除

---

## 三、工程结构

```
src/main/java/iconmix/
├── Main.java                    入口：无参数进 GUI，有参数走 CLI
├── core/
│   ├── Scanner.java             目录扫描、素材过滤、排序
│   ├── Renderer.java            宫格合成、缩放、淡描边
│   ├── IcoWriter.java           多尺寸 ICO（BMP/DIB 帧，小端）
│   ├── Exporter.java            PNG/ICO 导出、命名与「先清理后写入」
│   └── ShellApplier.java        desktop.ini、文件属性、shell 刷新、还原
├── extract/
│   ├── PeResourceParser.java    PE 资源目录解析（RT_GROUP_ICON / RT_ICON）
│   ├── LnkParser.java           .lnk 目标解析（含启发式兜底）
│   ├── FrameDecoder.java        DIB / PNG 帧解码、位深与掩码、alpha 兜底
│   └── IconSources.java         统一提取入口 + 像素级去重
├── gui/
│   ├── MainWindow.java          主窗口、拖拽接入、落地动作
│   └── PreviewPanel.java        预览绘制 + 拖拽交换
├── cli/
│   ├── Args.java                参数解析与帮助
│   ├── CliRunner.java           批处理执行与退出码
│   └── IcoVerifier.java         ICO 结构校验（像素解码测试）
└── util/
    ├── LittleEndian.java        小端读写 + 大端 be32（PNG）
    ├── PngIO.java               手写 PNG 编码器（无需图形环境）
    ├── PngVerify.java           PNG 结构校验器
    ├── NativeTools.java         attrib / SHChangeNotify
    └── Log.java                 日志收集
verify/                          验收脚本（不属于主源码树）
├── Harness.java                 24 项结构与行为验收
└── GuiSmoke.java                GUI 离屏渲染冒烟测试
```

## 四、自检

```powershell
# 编译
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java -Path src\main\java).FullName

# 验收（会临时改动被测目录，请用测试目录）
javac -encoding UTF-8 -cp out -d out-test verify\Harness.java
java '-Djava.awt.headless=true' -cp "out;out-test" Harness "D:\tmp\forGame"

# GUI 冒烟（离屏渲染成 PNG，不需要人工看窗口）
javac -encoding UTF-8 -cp out -d out-test verify\GuiSmoke.java
java -cp "out;out-test" GuiSmoke "D:\tmp\forGame" gui-smoke.png
```

## 五、环境要求

- Windows 10 / 11 x64
- JDK 8+（实测 JDK 26）
- 零第三方依赖、全程不需要网络
- `attrib`（系统自带）用于设置文件属性；`powershell` 用于通知 shell 刷新图标（缺失时只影响刷新，不影响图标写入）

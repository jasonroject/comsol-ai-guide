# COMSOL 自动建模、求解与调试：AI 执行指南

日期：2026-10-16。适用于具备文件与命令执行能力，或接入 COMSOL MCP 的 AI。目标是交付可复现、可打开、可重新求解并有数值验证证据的 `.mph` 模型。

## 1. 选择调用路线

COMSOL 的核心自动化入口是 **COMSOL Java API**。本机有两条可用路线：

| 路线 | 工作方式 | 适用场景 |
|---|---|---|
| 原生 Java 批处理 | AI 生成 `.java` → `comsolcompile` → `comsolbatch` → `.mph`、日志和数值结果 | 从零建模、复杂耦合、长时求解、独立批量案例、完整复现 |
| MCP / Python MPh | 工具或 Python 封装调用 COMSOL Java API | 交互式创建、结构检查、参数修改、结果读取、快速迭代 |
| Desktop Java Shell / 录制与导出 | 在独立桌面案例中设置对象，获取实际 API 代码或检查图面 | 接口探索、几何与边界可视核对、最终展示 |
| LiveLink for MATLAB | MATLAB 与 COMSOL 模型接口配合 | 已有 MATLAB 工作流且具备相应安装与许可时 |

推荐复杂任务以 **Java 批处理脚本作为可复现来源**，通过 MCP 做辅助检查。MPh 是第三方 Python 封装，本机 MCP 也是非官方项目；不要把工具列表等同于 COMSOL 全部能力。Java API 的官方说明见 [COMSOL API 概览](https://www.comsol.com/support/learning-center/article/overview-of-the-comsol-api-107912)。

后台执行仍依赖 COMSOL 安装与适用许可证。Python 编排器本身不求解 PDE；实际方程由 COMSOL 求解。

## 2. 本机证据与边界

- 安装路径因机器而异，例如 `C:\Program Files\COMSOL\COMSOL63\Multiphysics`，已有本地导出源码标明版本 `6.3.0.290`。
- 原生可执行程序：`bin\win64\comsolcompile.exe`、`comsolbatch.exe`。
- 历史 Java 源码和批处理脚本覆盖几何、材料、流动/传热/电磁、耦合、瞬态、结果导出及重开验证。
- 2026-10-16 本次 MCP 状态：`mph_available=true`，但 `connected=false`；Desktop 正在运行。可用与已连接是两个状态。
- 本次 MCP 的 RAG 状态为 `Knowledge base not built`，向量条目为 0。即使 `rag_available=true`，也不能声称已经检索到文档证据。
- 本文附有独立二维导热案例及运行器。2026-10-16 本次实测 `status=passed`：首次求解、独立进程读取保存解、清空原解后重新求解均通过；平均温度 298.150000000002 K，平均 x 向热流密度 −1000 W/m²，最大温度解析误差约 1.94×10⁻¹² K。证据见 `examples/verification_20261016.json`（发布版仅将绝对路径替换为占位符）。

历史工程参数只作为自动化实现参考。新项目的材料、方程和边界条件需要重新建立依据。

## 3. 建模前先写模型契约

创建 `model_spec.json`，至少包括：

1. 问题、目标量、物理尺度、允许误差和交付内容。
2. 维度：1D、2D、2D 轴对称或 3D；平面模型的厚度和轴对称旋转轴。
3. 几何、各域物理含义、单位、接触关系、Union/Assembly 选择。
4. 方程、材料属性、依赖的温度/压力/频率/浓度范围和数据来源。
5. 域、边界、点条件及初值；每项对应哪一个命名选择。
6. 多物理场耦合方向、源项、参考量、约束、是否使用变形网格。
7. Study 类型、激活物理场、求解区间、输出时间、内部步长策略、容差。
8. 网格细化计划、关键指标、守恒式、解析或基准验证问题。
9. 假设、未知参数、拟合对象、物理适用边界。

完成条件：每个源项、材料值、边界与目标量的单位、位置和依据明确；缺少的数据被列出。演示值可以用于验证调用链，但须标记为演示。

## 4. 目录、状态与进程归属

每个案例使用新目录：

```text
COMSOLRuns/<case_id>/
  model_spec.json
  code/BuildModel.java
  logs/compile.log, batch.log, stdout.log
  tmp/
  recovery/
  checkpoints/
  results/metrics.json, fields.csv
  delivered/model.mph
  validation.json
  handoff.md
```

记录代码和输入哈希、COMSOL 版本、命令、起止时间、PID、核数、退出码、实际求解区间、模型文件与结果文件路径。已有 `.mph` 先复制，所有变更写入新版本。

同一 MCP 会话的当前模型是共享状态；修改、求解和保存要串行执行。需要并发时，使用独立进程、独立模型、独立目录，并按许可证与内存预算限制并发。

完成条件：当前操作对象属于本任务；输出不会覆盖已有成果；可区分本任务和其他任务的进程。

## 5. 首选验证：独立 Java 后台案例

随附文件：`SmokeConduction.java`、`run_smoke.py`。案例是长 0.1 m、高 0.02 m 的二维矩形，常数导热率 10 W/(m·K)，左端 293.15 K，右端 303.15 K，其余边界为绝热。

解析解：

```text
T(x,y) = 293.15 K + 10 K × x/(0.1 m)
平均温度 = 298.15 K
qx = -k × ∂T/∂x = -1000 W/m²
```

本机执行命令：

```powershell
python run_smoke.py `
  --comsol-bin "C:/Program Files/COMSOL/COMSOL63/Multiphysics/bin/win64" `
  --out ./runs/conduction_001 `
  --cores 1 --timeout 180
```

输出目录必须尚不存在。换机器时修改安装路径；脚本只依赖 Python 标准库。

运行器依次编译、在第一个批处理进程从零建模并求解保存、在第二个进程读取保存结果并清空解后重新求解。每一步必须有正确退出码、完成标记、数值断言和非空模型文件。

温度最大解析误差阈值为 `1e-5 K`，平均温度误差阈值为 `1e-5 K`，平均热流密度误差阈值为 `1e-3 W/m²`。这些只针对该线性解析案例，不能直接套用到复杂研究模型。

完成条件：`report.json` 的 `status=passed`，且 `first`、`reopened_cached`、`reopened_recomputed` 三项都通过。脚本会保留日志供检查。

## 6. Java 批处理执行模式

原生命令模式如下；`<...>` 是待替换占位符：

```powershell
& '<COMSOL-bin>/comsolcompile.exe' '<run>/code/BuildModel.java'
# 检查退出码，并确认 .class 存在且不早于源码。
& '<COMSOL-bin>/comsolbatch.exe' `
  -np 1 -inputfile '<run>/code/BuildModel.class' -nosave `
  -tmpdir '<run>/tmp' -recoverydir '<run>/recovery' `
  -batchlog '<run>/logs/batch.log'
```

这里 `-nosave` 关闭批处理器的自动保存；Java 代码仍须在正确阶段调用 `model.save(...)`。直接运行 `.mph` 时按本机帮助确认 `-outputfile`、`-study` 和 sweep 参数，不能把 `.class` 路线与 `.mph` 路线的默认行为混在一起。官方说明见 [COMSOL Batch](https://doc.comsol.com/6.3/doc/com.comsol.help.comsol/comsol_ref_running.38.08.html)。

Java 基本对象顺序：

```java
Model model = ModelUtil.create("TaskModel");
model.param().set("L", "0.1[m]");
model.component().create("comp1", true);
model.component("comp1").geom().create("geom1", 2);
// 建几何并 run → 命名选择 → 材料 → 物理场与边界 → 网格 → study。
model.study("std1").run();
// 检查实际解、指定数据集、评估指标并通过断言。
model.save(outputPath);
```

普通 JVM 独立启动、COMSOL batch 和 Desktop Java Shell 的初始化上下文不同。使用本机 `comsolcompile/comsolbatch` 时沿用已验证模板；不要把其他上下文的 `initStandalone` 或服务器连接代码机械加入。

源码就是可复现构建来源。编译失败后保留日志，修复 API 调用再重新编译；确认没有误跑旧 `.class`。

## 7. MCP 交互调用模式

工具名以本地服务实际 schema 为准，下列是本次可用的短名。

1. `backend_status({})` 和 `mph_status({})` 检查当前状态；连接存在时先 `model_list({})`、`model_inspect({})` 确认归属。
2. 无连接时可 `mph_start_async({"cores":1,"version":"6.3"})`，保留返回任务 ID，再用 `mph_start_status({"task_id":"实际ID"})` 等待成功。失败时读取具体原因，而非反复启动。
3. `model_create({"name":"unique_case_id"})` 创建独立模型，再按需要调用 `model_create_component`、`param_set`。
4. `geometry_create` → 几何特征工具 → `geometry_build` → `geometry_get_entity_counts`。
5. `selection_create_box` / `selection_create_explicit` → `selection_inspect_entities`。
6. 材料 → 物理场 → 边界 → 网格 → study，逐步检查实际生成的节点与属性。
7. 运行前 `model_validate_all({"include_results":false})`，处理 `issues`、`warnings`，同时完成自己的物理检查。
8. `study_solve({"study_name":"std1"})` 同步求解；长任务优先独立 batch，方便监控、超时和恢复。
9. `datasets_list`，选择实际数据集，再调用 `results_global_evaluate` 或所需区域统计。
10. `model_save({"file_path":"完整输出路径.mph"})`，另行执行重开检查和必要的复算。

例如二维左边界的 Box 工具调用需要指定维度：

```json
{
  "component_name": "comp1",
  "entity_dimension": 1,
  "selection_name": "left_boundary",
  "xmin": "-1e-8[m]",
  "xmax": "1e-8[m]",
  "ymin": "-1e-8[m]",
  "ymax": "0.02[m]+1e-8[m]"
}
```

核对实际工具 schema 是否支持单位表达式、选择标签与几何关联；调用后必须检查返回的实体数量和位置。

**本次本地实现的特殊行为：**

- `mph_start` 在客户端已存在时调用 `clear_models`；它不是无副作用的重连操作。
- `mph_disconnect` 也会清空会话模型，客户端可能留存。仅在确认全部会话模型归本任务时使用。
- 工具常返回 JSON 文本包装；外层调用成功之外，还须解析内部 `success`、`valid`、`issues`、`warnings`。
- `model_validate_all.success=true` 表示校验执行成功；`valid=true` 也只表示启发式规则未发现问题，不能证明方程正确、结果准确或收敛。
- `model_validate_all(include_results=true)` 在本地实现中没有自动执行全部结果表达式验证，仍须显式读取数值。
- 接口目录、表达式目录和常见材料 preset 是辅助映射。许可证、属性名和表达式有效性以当前模型与版本为准。
- 本机 `mph` 后端与 Desktop 模型是不同的状态，不自动同步。GUI 中看到的结果不一定是 MCP 当前模型的结果。

完成条件：每一工具调用的实际模型状态和模型契约一致，且关键结果有独立验证。

## 8. 从零构建时的逐层检查

### 参数与几何

用 `"10[mm]"`、`"293.15[K]"`、`"1[W/(m*K)]"` 等带单位字符串。检查几何单位、布尔操作、域数量、尺度、实体维度和空间位置。Assembly 的接触边界通常需要 pair 与对应物理条件；Union 与 Assembly 的连续性含义不同。

通过几何派生选择或命名选择绑定物理意义。实体编号可用于已经核对、几何固定的小案例；几何变更后重新核对。命名选择若只是包装硬编码编号，同样不能自动抵抗拓扑变化。

选择需要检查非空、预期数量、坐标范围、测度及是否重叠；Box 条件可能是相交或完全包含，记录实际设置。几何 build 成功后再查询最终实体。

### 材料与物理场

列出每个域需要的物性，核对材料覆盖、单位与有效范围。温度函数外推、接近相变、负黏度等必须被捕获。材料库可用不代表该模型已正确赋值。

为每个物理场明确守恒方程、参考压力/温度、默认节点、边界覆盖和初值。注意流体压力零空间、电势参考、固体刚体运动和全绝热稳态传热的可解性。

### 耦合与研究

先让单物理场的小模型通过，再增加耦合。明确一向/双向耦合、源项单位、数据来源、求解域及 study 激活项。检查是否重复计算热源、重力或反应热。

频域电磁 → 瞬态传热时，记录频域解、温度对介电物性的影响及重新求解频率；冻结吸收热源只能声称对应的单向耦合。全局零维压力后处理不能冒充已反馈到流动与相变的压力场。

### 网格

记录单元类型、数量、自由度、质量指标、局部尺寸、边界层和薄区分辨率。`autoMeshSize` 的等级编号不能代替实际网格尺寸和误差证据。

完成条件：每一层都有可检查的状态证据，而不是仅有截图或工具成功提示。

## 9. 不知道 API 怎么办

按以下顺序取得证据：

1. 查本机对应版本官方帮助、API 文档和 Application Library。
2. 在独立的小模型中用 Desktop 设置一个特征，保存为 Java 模型文件，或使用本机支持的代码录制/Java Shell，提取实际调用。
3. MCP 的 `physics_list_features`、`physics_feature_info` 可辅助检查已有节点属性。
4. 对比单项变更前后的 Java 导出和实际结果；记录 tag、类型名、属性名、单位与版本。

MCP 不支持的操作可以转入可复现的 Java 构建脚本。`gui_execute_java_shell` 会影响当前 Desktop 模型，使用前先确认文档归属并保存副本；它与后台 `mph` 会话不是同一个对象。

完成条件：接口经过本机实际调用验证，或明确标记尚未验证；不把猜测的 feature tag 和属性名写成确定步骤。

## 10. 求解与结果：四个不同的“完成”

分别判断：进程完成、求解完成、数值验证通过、物理/科学验证通过。

- **进程完成**：有退出码、完整日志和该案例的完成标记。
- **求解完成**：目标 study 的实际 solution 存在，覆盖预期参数和时间区间，日志无未处理的求解失败。
- **数值验证**：结果有限、守恒、解析/基准对照、网格/时间步敏感性在预定范围内。
- **科学验证**：材料、边界、模型假设与实验或权威参考吻合，适用范围明确。

有 `.mph` 不代表含解；有终点图不代表全时间区间完成；失败前的部分解可以保存，但必须标为 `partial`。

结果提取必须绑定：

```text
case_id + 输入哈希 + component + selection + dataset + solution
+ 参数组合 + 时间/频率 + 表达式 + 单位 + 统计方式
```

先枚举数据集与对应 solution，不能假设 `dset1` 永远是所需解。区分域场 `T`、全局量、区域平均、最大值、积分及点采样；对局部场做 global evaluation 报错时，先使用正确区域算子，避免随意换表达式。

二维面积积分与三维体积积分的单位不同；平面厚度、轴对称 `2πr` 权重及算子是否已包含旋转积分需核对，防止漏乘或重复乘。对三维模型使用 `AvVolume`，对二维使用 `AvSurface` 等对应维度，具体行为以实际数据集和 API 为准。

## 11. 数值验证与自动调试

### 验证标准

1. 与解析解、制造解或独立基准对比至少一个关键量。
2. 守恒：流动检查质量边界积分；传热检查能量储存、边界热通量与体热源；力学检查力/力矩平衡；电磁检查输入、反射、透射和吸收功率。
3. 对目标量做网格敏感性；至少报告实际网格数量/尺度及指标变化。若使用 GCI，需要满足相应渐进收敛条件，不能把任意三套网格都称为 GCI。
4. 瞬态独立检查时间误差：内部最大步长、容差、初值和终点。**输出时间间隔不是内部积分步长。**见 [6.3 时间求解器设置](https://doc.comsol.com/6.3/doc/com.comsol.help.comsol/comsol_ref_solver.36.136.html)。
5. 检查真实完成时间清单、参数清单和所有关键结果的 NaN/Inf、物理范围与采样一致性。

### 调试循环

保存失败现场 → 分类 → 写出原因假设 → 修改一个对应设置 → 重跑 → 与原指标比较 → 接受或回滚。每次记录 `attempt.json`。建议同一假设最多尝试两次，连续五次无改善时输出最小复现、日志和明确缺口，停止盲改。

| 症状 | 优先检查 | 下一动作 |
|---|---|---|
| 编译失败 | API 版本、tag、参数类型、类名与文件名 | 按首个编译错误修复，确认重新生成 class |
| 模型启动失败 | 安装路径、模块许可、内存、临时目录 | 保存原生异常，先跑最小案例 |
| Unknown feature/property | API 类型或属性名来自其他版本 | 查本版本帮助或独立 GUI 导出 |
| 空选择或边界错位 | 几何 build、选择维度、Box 条件、拓扑变化 | 重建选择并校验位置和测度 |
| Undefined variable | component 作用域、域、物理场激活、dataset | 找到变量定义与正确解来源 |
| Singular matrix | 漏约束、刚体运动、压力/电势基准、无方程自由度 | 修复可解性；不先靠换求解器掩盖 |
| 初值不一致 | t=0 边界与场初值、约束和 DAE | 求一致初态或按物理过程平滑加载 |
| 非线性不收敛 | 物性、源项符号/量级、初猜、载荷阶跃 | 先简化耦合，延续参数，再调整阻尼/缩放 |
| 时间步趋近零 | 不连续源、刚性、网格畸变、相变与初值 | 看最早失败位置与变量，局部诊断 |
| OOM / 矩阵过大 | 自由度、求解器、网格、输出场、并发 | 降低并发，合理简化模型，估算资源 |
| ALE 负单元或严重畸变 | 运动定义、位移量级、时间步、网格质量 | 检查运动与重网格；静止网格成功不能替代 ALE 验证 |
| 后处理通过但结果不符 | 旧结果、错 dataset、单位、积分维度 | 新进程重开读取并指定 dataset 复算 |
| 无错误但只完成一部分 | 实际时间/参数清单、日志尾部 | 标记 partial，保留已完成部分 |
| 色图漂亮但指标错误 | 色轴、单位、选区、缓存、插值 | 从原始数值和守恒检查回查 |

针对奇异矩阵和初值问题，优先采用官方诊断思路：[Singular Matrix](https://www.comsol.com/support/knowledgebase/953)、[不一致初值](https://www.comsol.com/support/knowledgebase/1172/)、[瞬态收敛改进](https://www.comsol.com/support/knowledgebase/1262)。

修改材料、反应、边界或耦合属于物理模型修改；调整容差、缩放、时间步或线性求解器属于数值修改。分别记录，不用改变研究问题来换取收敛。截断负值、放松容差或关闭耦合若用于诊断，必须在最终状态里明确披露。

## 12. 长任务、超时与断点恢复

运行前定义核数、内存预算、单次超时、总预算、检查点阶段及外层监督器。日志中记录阶段标记：geometry、mesh、solve-start、实际求解进度、save-done、validation-done。

轮询只读进程、日志增量和检查点；不要因日志静默就立即判断卡死。根据 CPU、内存、阶段和进展综合判断。

随附运行器超时后只终止它直接创建的子进程，并报告需要核对后代进程归属。它不会按名称结束所有 COMSOL；启动器遗留的计算进程需要结合 PID、父子链、启动时间和命令行确认后清理。

恢复优先从本次检查点读取，检查解是否包含预期区间。明确恢复源是最后已接受解、恢复文件还是重新初始求解。只保留部分输出时，禁止将其补齐为已完成案例。异常退出后的 lock 文件也须确认无活跃拥有者后再处理。

## 13. 保存、重开、复算和展示

在最终路径保存 `.mph`，关闭本任务实例，启动新进程：

1. 打开**最终交付文件**，核对几何、选择、材料、激活物理场、边界与 study。
2. 从保存的解读取指标，确认数据集、时间/参数清单和单位。
3. 独立重新求解代表性案例；本次最小示例会清空原解后重算。
4. 与原结果按预定义容差比较，重新做守恒或基准检查。
5. 将修复同步到构建脚本，确保下次从零生成也能通过。
6. 若交付要求可视化，打开最终文件或导出图，核对几何、边界、图例、色轴、单位和所选解。

对于耗时过长的研究，若只完成重开读取而没有独立复算，报告 `reopen_read_pass=true`、`recompute_pass=not_run`。保存缓存可读与独立复算是两项验收。

交付至少包括：模型契约、构建/运行脚本、`.mph`、日志、带单位结果、关键图、验证报告、假设与限制、复现命令。外部 CAD、插值表和材料数据需一并交付或明确依赖位置。

建议报告状态：

```json
{
  "automation_pass": true,
  "setup_checked": true,
  "solver_pass": true,
  "coverage_complete": true,
  "balance_pass": "pending",
  "mesh_verification": "pending",
  "time_verification": "not_applicable",
  "reopen_read_pass": true,
  "recompute_pass": true,
  "scientific_validation": "not_validated"
}
```

上面是报告格式示意，不是任何研究项目已经通过的声明。

## 14. 可直接交给其他 AI 的启动提示词

> 请按《COMSOL自动建模与调试-AI执行指南.md》完成以下仿真。先读取项目约定与现有证据，探测 COMSOL 版本、模块和接口状态，确认不会修改其他任务的模型。在独立目录写出模型契约，先运行有解析解的最小案例，再增量构建几何、命名选择、材料、物理场、边界、网格和研究。优先用原生 Java API 与 batch 形成可复现脚本，MCP 用于辅助检查；已有 MCP 会话先确认归属，避免清空共享模型。每次求解检查原生日志、实际时间/参数覆盖、数据集、单位、物理范围、守恒及目标量。失败时保存现场，一次验证一个原因，记录修改与回滚。最终交付脚本、原生 MPH、结果和验证报告，并在新进程重开读取、完成约定的独立复算。分别报告求解、守恒、网格、时间步、复算和科学验证状态。工艺需求如下：〔填写目标、几何、材料、方程、边界、操作条件和证据来源〕。

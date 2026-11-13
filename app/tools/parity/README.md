# 对拍脚本（parity）

App 里的拼图引擎（`Cuts` / `Tiers` / `Packer` / `Pipeline`）是
[`dsh-see-now`](../../../README.md) 那张 JS 实现的**逐行移植**。这里留着当年用来
证明"两边算出来一模一样"的脚本 —— 改引擎之前先跑一遍，比读代码可靠。

## 怎么跑

这些脚本按"同级目录并排放仓库"的布局写死了相对路径：

```
<workspace>/
├── dsh-see-now/                 # 桌面端（JS 原版）
├── dsh-video-storyboard/        # token 口径的出处
└── see-on-phone/                # 本仓库
    └── app/tools/parity/
```

```bash
# 1. 生成用例（帧序列 → 变化分 / 档位 / 装箱结果）
node gen-cases.mjs

# 2. Java 侧吐同样格式
javac -encoding UTF-8 -d .work CutsCli.java ../../src/com/dsh/eye/Cuts.java
java -cp .work CutsCli < .work/cases.txt > .work/java.txt

# 3. 和 JS 侧逐行 diff
node cuts-js.mjs < .work/cases.txt > .work/js.txt
diff .work/java.txt .work/js.txt
```

| 脚本 | 对拍对象 |
|---|---|
| `cuts-js.mjs` | `Cuts.java` ↔ `dsh-see-now/src/cuts.js`（逐帧变化分） |
| `js-side.mjs` | `Packer.java` ↔ `dsh-see-now/src/pack.js`（行 DP 装箱） |
| `pipeline-js.mjs` | `Pipeline.java` ↔ `dsh-see-now/src/pipeline.js`（封箱时机） |
| `fps-norm.mjs` / `sb-fps.mjs` | 帧率归一化：`dsh-video-storyboard` 的阈值表换算 |

`.work/` 是临时帧和中间输出，**不入库**（几 GB 的原始帧）。

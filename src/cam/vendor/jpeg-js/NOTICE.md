# jpeg-js 0.4.4 — vendored

上游：<https://github.com/jpeg-js/jpeg-js>（npm `jpeg-js@0.4.4`）
vendor 进来的原因：手机端编解码链只剩「看见」App 一个外部进程，解码必须留在纯 JS 里。

**两级许可，覆盖的文件不同。** 两者都允许商用与再分发。

| 文件 | 许可 | 版权 |
|---|---|---|
| `index.js`、`package.json`、其余封装 | BSD-3-Clause | Copyright (c) 2014, Eugene Ware —— 全文见 [LICENSE](./LICENSE) |
| `lib/decoder.js` | **Apache-2.0** | Copyright 2011 notmasteryet —— 全文见 [LICENSE-APACHE.txt](./LICENSE-APACHE.txt) |

`lib/decoder.js` 来自 notmasteryet 的 `jpg.js`（Apache-2.0）。vendor 时做了两处修改：
删掉 JPEG **编码器**、把导出从 `{decode, encode}` 改成裸 `decode` 函数（解码逻辑未改）。
按 Apache-2.0 §4(b)「修改过的文件要带显著声明」，改动已写在 `lib/decoder.js` 文件头。

> 上游 npm 包只带 BSD-3 的 `LICENSE`，Apache-2.0 那一半没有附全文。Apache-2.0 §4(a) 要求
> 再分发时给接收者一份许可证副本，所以这里补了 `LICENSE-APACHE.txt`（标准全文，未作改动）。

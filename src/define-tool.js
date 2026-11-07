/**
 * `@deepseek-ai/dsh-tools` 的离线兜底（精简版，规则与 dsh-see-now 的同名文件一致）。
 *
 * 宿主的这个包被打进 `app.asar`，插件从自己的 node_modules 往上解析时经常找不到 →
 * `import()` 抛 → `apply()` 被 catch 吞掉 → **整个插件静默不注册任何工具**。
 * 手机上（PRoot）这个概率更高，所以兜底必须有。
 *
 * 兜底只少一层运行时参数校验，工具照常能用。
 */

function compilePropertyMap(map, path) {
  if (!map || typeof map !== 'object' || Array.isArray(map)) {
    throw new Error(`${path} 必须是对象（属性名 → 值 schema）`);
  }
  const properties = {};
  const required = [];
  for (const [key, spec] of Object.entries(map)) {
    if (!spec || typeof spec !== 'object' || Array.isArray(spec)) {
      throw new Error(`${path}.${key} 必须是值 schema 对象`);
    }
    const { required: mark, ...rest } = spec;
    if (mark === true) required.push(key);
    properties[key] = compileValue(rest, `${path}.${key}`);
  }
  return required.length ? { properties, required } : { properties };
}

function compileValue(spec, path) {
  const out = {};
  for (const [k, v] of Object.entries(spec)) {
    if (k === 'required') continue;
    if (k === 'properties') {
      const c = compilePropertyMap(v, `${path}.properties`);
      out.properties = c.properties;
      if (c.required) out.required = c.required;
      continue;
    }
    if (k === 'items') { out.items = compileValue(v, `${path}.items`); continue; }
    if (k === 'type' && v === 'json') continue;   // author-only：退化成纯注解
    out[k] = v;
  }
  return out;
}

export function parametersToJsonSchema(spec) {
  const { properties, required } = compilePropertyMap(spec, 'parameters');
  return { type: 'object', properties, ...(required ? { required } : {}) };
}

export function defineToolCompat(options) {
  const { name, description, parameters, output, execute } = options;
  if (!name) throw new Error('defineTool: name 必须是非空字符串');
  if (!output || typeof output.render !== 'function') throw new Error(`defineTool(${name}): 缺 output.render`);
  if (typeof execute !== 'function') throw new Error(`defineTool(${name}): execute 必须是函数`);
  return {
    name,
    description,
    parameters: parametersToJsonSchema(parameters ?? {}),
    output: { schema: compileValue(output.schema ?? {}, 'schema'), render: output.render },
    ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
    ...(typeof options.isConcurrencySafe === 'function' ? { isConcurrencySafe: options.isConcurrencySafe } : {}),
    async execute(args, exec) { return execute(args, exec); },
  };
}

/** 三跳：宿主包 → `DSH_TOOLS_PATH` → 内置兜底。**任何一跳失败都不能抛出去。** */
export async function loadDefineTool() {
  try {
    const mod = await import('@deepseek-ai/dsh-tools');
    if (mod?.defineTool) return mod.defineTool;
  } catch { /* 落到下一跳 */ }
  const override = process.env.DSH_TOOLS_PATH;
  if (override) {
    try {
      const { pathToFileURL } = await import('node:url');
      const mod = await import(pathToFileURL(override).href);
      if (mod?.defineTool) return mod.defineTool;
    } catch { /* 路径不对，继续兜底 */ }
  }
  return defineToolCompat;
}

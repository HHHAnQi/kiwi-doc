// clsx 替代: 项目不引 clsx 包, 自己 30 行写一个.
// 兼容 strings / arrays / objects 三种参数, 支持 falsy 跳过
type ClassValue = string | number | null | undefined | false | ClassValue[] | Record<string, unknown>;

export function cn(...inputs: ClassValue[]): string {
  const out: string[] = [];
  for (const v of inputs) {
    if (!v) continue;
    if (typeof v === 'string' || typeof v === 'number') {
      out.push(String(v));
    } else if (Array.isArray(v)) {
      const inner = cn(...v);
      if (inner) out.push(inner);
    } else if (typeof v === 'object') {
      for (const [k, val] of Object.entries(v)) {
        if (val) out.push(k);
      }
    }
  }
  return out.join(' ');
}

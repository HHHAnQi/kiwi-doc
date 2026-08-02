import { useEffect, useRef, useState } from 'react';
import { getToken, setToken } from '../api/client';

// Header 上的 token 编辑器 (PM-F4)。
//
// 痛点: 旧 Header 注释承诺 "可编辑 dev token", 但 UI 完全缺失, 外部用户改名 token
// 后只能刷新页面再 localStorage 改 → 完全不可用。
//
// 行为:
// - 默认折叠, 只显示当前 token 前 6 位 + ●（绿=默认 dev-token, 黄=已自定义)
// - 点击展开 input + 保存/取消按钮
// - 保存后写入 localStorage (client.ts setToken), 不刷新页面 — 下次 fetch 自动生效
// - 输入与原值相同时禁用保存
export function TokenEditor() {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState(getToken());
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const isDefault = value === 'dev-token-change-me';
  const masked = value.slice(0, 6) + (value.length > 6 ? '…' : '');

  const save = () => {
    const v = value.trim();
    if (!v || v === getToken()) {
      setOpen(false);
      return;
    }
    setToken(v);
    setOpen(false);
    // 不强制 reload, 但当前已加载的数据仍是旧 token 拉的; 让用户手动点 "刷新知识库"
    // 比强制 reload 用户体感更可控。
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => {
          setValue(getToken());
          setOpen((v) => !v);
        }}
        className="flex items-center gap-1 text-[10px] text-slate-500 hover:text-brand-600"
        title="当前 dev/admin token"
      >
        <span
          className={
            'inline-block h-1.5 w-1.5 rounded-full ' +
            (isDefault ? 'bg-emerald-400' : 'bg-amber-400')
          }
        />
        <span>token {masked}</span>
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-6 z-20 w-64 rounded-md border border-slate-200 bg-white p-3 text-xs shadow-lg">
            <label className="mb-1 block text-slate-600">
              Authorization Token
            </label>
            <input
              ref={inputRef}
              type="text"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') save();
                if (e.key === 'Escape') setOpen(false);
              }}
              placeholder="Bearer token..."
              className="w-full rounded border border-slate-300 px-2 py-1 text-xs outline-none focus:border-brand-500"
            />
            <div className="mt-1 text-[10px] text-slate-400">
              dev: <code>dev-token-change-me</code>; admin: <code>admin-token</code>
            </div>
            <div className="mt-2 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="rounded border border-slate-200 px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50"
              >
                取消
              </button>
              <button
                type="button"
                onClick={save}
                disabled={!value.trim() || value.trim() === getToken()}
                className="rounded bg-brand-600 px-2 py-1 text-[11px] text-white hover:bg-brand-700 disabled:opacity-40"
              >
                保存
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

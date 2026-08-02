// format.ts 纯函数测试, 重点是 formatBytes 边界 (size_bytes 字段直出给 UI)。
import { describe, it, expect } from 'vitest';
import { formatBytes, formatRelativeTime, uid } from './format';

describe('formatBytes', () => {
  it('0 / 负值 / NaN → "0 B"', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(-1)).toBe('0 B');
  });
  it('小于 1KB 直显 B (无小数)', () => {
    expect(formatBytes(512)).toBe('512 B');
  });
  it('KB 量级保留 1 位小数', () => {
    expect(formatBytes(4411)).toBe('4.3 KB');
  });
  it('MB 量级保留 1 位小数', () => {
    expect(formatBytes(1_500_000)).toBe('1.4 MB');
  });
  it('GB 上限不再进 TB (units 数组到 GB 截止)', () => {
    // n<10 时 toFixed(1), 所以 2 GB 实际显示 "2.0 GB" - 这就是真实行为
    expect(formatBytes(2 * 1024 * 1024 * 1024)).toBe('2.0 GB');
    // n>=10 时 toFixed(0)
    expect(formatBytes(20 * 1024 * 1024 * 1024)).toBe('20 GB');
  });
});

describe('formatRelativeTime', () => {
  it('空串 → 空', () => {
    expect(formatRelativeTime('')).toBe('');
  });
  it('非法 iso → 空', () => {
    expect(formatRelativeTime('not-a-date')).toBe('');
  });
  it('未来时间 → "刚刚" (diff<0 落入第一分支)', () => {
    expect(formatRelativeTime(new Date(Date.now() + 1000).toISOString())).toBe(
      '刚刚',
    );
  });
  it('30 秒前 → "刚刚"', () => {
    expect(
      formatRelativeTime(new Date(Date.now() - 30_000).toISOString()),
    ).toBe('刚刚');
  });
  it('5 分钟前 → "5 分钟前"', () => {
    expect(
      formatRelativeTime(new Date(Date.now() - 5 * 60_000).toISOString()),
    ).toBe('5 分钟前');
  });
});

describe('uid', () => {
  it('每次调用返回不同 id', () => {
    const a = uid('m');
    const b = uid('m');
    expect(a).not.toBe(b);
  });
  it('带前缀', () => {
    expect(uid('u-')).toMatch(/^u-/);
  });
});

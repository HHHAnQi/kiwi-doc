// SSE 帧解析纯函数测试 — 整条 chat 链路最关键的逻辑, 修一次就踩一次坑。
//
// 覆盖目标:
//  1. citations 帧双命名兼容 (camelCase / snake_case 都得能解析, 历史踩坑点)
//  2. delta/done/error 4 种事件正确分流
//  3. 畸形 / 空 / 非 JSON 帧不抛只返 null
//  4. trace_id 与 state_hint 同时支持两种命名
import { describe, it, expect } from 'vitest';
import { parseSSEFrame } from './chat';

describe('parseSSEFrame', () => {
  describe('citations', () => {
    it('解析 camelCase 字段 (后端 SSE 实际格式)', () => {
      const ev = parseSSEFrame(
        'event:citations\ndata:{"citations":[{"chunkId":304,"docId":14,"page":0,"snippet":"foo","sectionPath":["A","B"],"llmContext":"..."}]}',
      );
      expect(ev).toEqual({
        type: 'citations',
        citations: [
          {
            chunk_id: 304,
            doc_id: 14,
            page: 0,
            snippet: 'foo',
            llm_context: '...',
            section_path: ['A', 'B'],
          },
        ],
      });
    });

    it('解析 snake_case 字段 (向前兼容)', () => {
      const ev = parseSSEFrame(
        'event:citations\ndata:{"citations":[{"chunk_id":1,"doc_id":2,"page":3,"snippet":"bar","section_path":["X"],"llm_context":"y"}]}',
      );
      expect(ev?.type).toBe('citations');
      expect(ev && 'citations' in ev && ev.citations[0]).toEqual({
        chunk_id: 1,
        doc_id: 2,
        page: 3,
        snippet: 'bar',
        llm_context: 'y',
        section_path: ['X'],
      });
    });

    it('空 citations 数组也返回合法事件', () => {
      const ev = parseSSEFrame('event:citations\ndata:{"citations":[]}');
      expect(ev).toEqual({ type: 'citations', citations: [] });
    });

    it('citations 字段非数组时不炸, 返回空数组', () => {
      const ev = parseSSEFrame('event:citations\ndata:{"citations":null}');
      expect(ev).toEqual({ type: 'citations', citations: [] });
    });
  });

  describe('delta', () => {
    it('普通文本 delta', () => {
      expect(parseSSEFrame('event:delta\ndata:{"delta":"hello"}')).toEqual({
        type: 'delta',
        delta: 'hello',
      });
    });
    it('delta 缺失时回退空串, 不 undefined', () => {
      expect(parseSSEFrame('event:delta\ndata:{}')).toEqual({
        type: 'delta',
        delta: '',
      });
    });
  });

  describe('done', () => {
    it('camelCase traceId + stateHint', () => {
      expect(
        parseSSEFrame('event:done\ndata:{"traceId":"abc","stateHint":"OK"}'),
      ).toEqual({ type: 'done', trace_id: 'abc', state_hint: 'OK' });
    });
    it('snake_case 兼容', () => {
      expect(
        parseSSEFrame(
          'event:done\ndata:{"trace_id":"x","state_hint":"NO_RECALL"}',
        ),
      ).toEqual({ type: 'done', trace_id: 'x', state_hint: 'NO_RECALL' });
    });
    it('state_hint 缺失默认 OK', () => {
      expect(parseSSEFrame('event:done\ndata:{"traceId":"x"}')).toEqual({
        type: 'done',
        trace_id: 'x',
        state_hint: 'OK',
      });
    });
  });

  describe('error', () => {
    it('解析错误事件', () => {
      expect(
        parseSSEFrame(
          'event:error\ndata:{"traceId":"t1","message":"模型超时"}',
        ),
      ).toEqual({ type: 'error', trace_id: 't1', message: '模型超时' });
    });
  });

  describe('畸形帧', () => {
    it('无 data 行 → null', () => {
      expect(parseSSEFrame('event:delta\n')).toBeNull();
    });
    it('data 不是 JSON → null (不抛)', () => {
      expect(parseSSEFrame('event:delta\ndata:not json')).toBeNull();
    });
    it('未知 event 类型 → null', () => {
      expect(parseSSEFrame('event:foobar\ndata:{}')).toBeNull();
    });
    it('空帧 → null', () => {
      expect(parseSSEFrame('')).toBeNull();
    });
    it('缺失 event 行时 eventName=默认 message → null', () => {
      // 没有 event 行, eventName='message' 走 default 分支返回 null
      expect(parseSSEFrame('data:{}')).toBeNull();
    });
  });
});

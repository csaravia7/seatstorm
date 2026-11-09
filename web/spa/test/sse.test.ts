import { describe, expect, it } from 'vitest';
import { SseParser } from '../src/lib/sse';

describe('SseParser', () => {
  it('parses events split across chunks and ignores comments', () => {
    const p = new SseParser();
    expect(p.push('event: token\ndata: Ho')).toEqual([]);
    expect(p.push('la\n\n: ping\n\nevent: done\ndata: \n\n')).toEqual([
      { event: 'token', data: 'Hola' },
      { event: 'done', data: '' },
    ]);
  });
  it('joins multi-line data and handles CRLF', () => {
    expect(new SseParser().push('event: token\r\ndata: a\r\ndata: b\r\n\r\n')).toEqual([{ event: 'token', data: 'a\nb' }]);
  });
});

export interface SseMessage {
  event: string;
  data: string;
}

/** Incremental parser for `text/event-stream` bodies (comments are ignored). */
export class SseParser {
  private buf = '';

  push(chunk: string): SseMessage[] {
    this.buf += chunk.replace(/\r\n/g, '\n');
    const out: SseMessage[] = [];
    let idx: number;
    while ((idx = this.buf.indexOf('\n\n')) >= 0) {
      const block = this.buf.slice(0, idx);
      this.buf = this.buf.slice(idx + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) continue;
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length > 0 || event !== 'message') out.push({ event, data: data.join('\n') });
    }
    return out;
  }
}

// 문항 은행 MCP 서버 골격 (TypeScript SDK v2 · stdio)
//
// stdio 서버에서는 stdout 이 곧 프로토콜 채널이다.
// 로그는 console.error 만 쓴다(stdout 으로 찍는 로그 함수는 절대 쓰지 않는다).
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { searchItems } from './itemApi.js';

const server = new McpServer({ name: 'item-bank', version: '1.0.0' });

// 예시 도구: 서버가 살아 있는지 확인한다. 입력 없음.
server.registerTool(
  'ping',
  {
    description: '서버 연결 확인용 예시 도구. "pong" 을 돌려준다.'
  },
  async () => ({
    content: [{ type: 'text', text: 'pong' }]
  })
);

// ================================================================
// 여기에 도구를 등록합니다
//
//   server.registerTool('도구_이름', { description, inputSchema }, handler)
//
// - inputSchema 는 z.object({...}) 전체 스키마로 넘긴다 (import * as z from 'zod/v4')
// - 감쌀 API 는 ./itemApi.js 에 있다 (ESM 이라 확장자를 .js 로 적는다)
// - import 문도 이 자리에 함께 붙여 넣어도 된다
// ================================================================

// 문항 검색: itemApi.searchItems 를 그대로 감싼다. 읽기 전용.
server.registerTool(
  'search_items',
  {
    description:
      '문항 은행에서 초등 수학 문항을 키워드로 검색한다. ' +
      '사용자가 특정 주제 · 단원 · 난이도의 문항을 찾거나, 과제에 넣을 문항을 고르거나, ' +
      '문항 본문 · 태그(계산 · 개념 · 문장제 · 서술형 · 오답률높음 등)를 확인하려 할 때 쓴다. ' +
      '결과는 id · 단원 코드 · 난이도(하/중/상) · 태그 · 본문이다. 문항을 수정하지는 않는다.',
    inputSchema: z.object({
      keyword: z.string().describe('문항 본문이나 태그에 포함된 검색어. 예: "분수", "문장제". 빈 문자열이면 키워드 조건 없이 검색한다'),
      unit: z
        .string()
        .optional()
        .describe('단원 코드(M5-1 · M5-2 · M5-3 · M6-1 · M6-2) 또는 단원 이름 일부(예: "분수의 곱셈"). 생략하면 전체 단원'),
      difficulty: z.enum(['하', '중', '상']).optional().describe('난이도. "하" · "중" · "상" 중 하나. 생략하면 전체 난이도'),
      limit: z.number().int().min(1).max(20).default(5).describe('돌려줄 최대 문항 수. 1~20 정수, 기본 5')
    })
  },
  async ({ keyword, unit, difficulty, limit }) => {
    try {
      const results = await searchItems({ keyword, unit, difficulty, limit });
      if (results.length === 0) {
        const empty = { message: '검색 결과 없음', conditions: { keyword, unit: unit ?? null, difficulty: difficulty ?? null, limit } };
        return { content: [{ type: 'text', text: JSON.stringify(empty) }] };
      }
      return { content: [{ type: 'text', text: JSON.stringify(results) }] };
    } catch (error) {
      console.error('search_items failed:', error);
      return {
        content: [{ type: 'text', text: `문항 검색 중 오류가 발생했습니다: ${String(error)}` }],
        isError: true
      };
    }
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('item-bank MCP server running on stdio');
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});

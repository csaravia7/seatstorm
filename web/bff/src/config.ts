export interface Config {
  port: number;
  sessionSecret: string;
  consolePin: string;
  adminToken: string;
  kafkaBootstrap: string;
  orderUrl: string;
  queryUrl: string;
  catalogUrl: string;
  paymentUrl: string;
  agentUrl: string;
  botsUrl: string;
  kafkaUiUrl: string;
  dynatraceUrl: string;
  maxSseConnections: number;
  hostname: string;
  upstreamTimeoutMs: number;
  defaultConcertId: string;
  pingMs: number;
  seatFlushMs: number;
  leaderboardMs: number;
}

const trimSlash = (s: string) => s.replace(/\/+$/, '');

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    port: Number(env.PORT ?? 3000),
    sessionSecret: env.SESSION_SECRET ?? 'change-me-32-bytes-minimum-secret',
    consolePin: env.CONSOLE_PIN ?? '4321',
    adminToken: env.ADMIN_TOKEN ?? 'change-me-admin',
    kafkaBootstrap: env.KAFKA_BOOTSTRAP ?? '',
    // Defaults match the %dev ports of spec 10.8
    orderUrl: trimSlash(env.ORDER_URL ?? 'http://localhost:8101'),
    queryUrl: trimSlash(env.QUERY_URL ?? 'http://localhost:8105'),
    catalogUrl: trimSlash(env.CATALOG_URL ?? 'http://localhost:8106'),
    paymentUrl: trimSlash(env.PAYMENT_URL ?? 'http://localhost:8103'),
    agentUrl: trimSlash(env.AGENT_URL ?? 'http://localhost:8108'),
    botsUrl: trimSlash(env.BOTS_URL ?? 'http://localhost:3100'),
    kafkaUiUrl: env.KAFKA_UI_URL ?? '',
    dynatraceUrl: env.DYNATRACE_URL ?? '',
    maxSseConnections: Number(env.MAX_SSE_CONNECTIONS ?? 2000),
    hostname: env.HOSTNAME ?? `local-${process.pid}`,
    upstreamTimeoutMs: 5000,
    defaultConcertId: env.CONCERT_ID ?? 'neon-tide-2026',
    pingMs: 15_000,
    seatFlushMs: 100,
    leaderboardMs: 2000,
  };
}

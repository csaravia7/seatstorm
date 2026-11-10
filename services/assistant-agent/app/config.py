"""Environment-driven settings (spec 9.1)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field

PROVIDERS = ("anthropic", "nvidia")


def _int(env: dict, name: str, default: int) -> int:
    raw = env.get(name, "").strip()
    return int(raw) if raw else default


def _float(env: dict, name: str, default: float) -> float:
    raw = env.get(name, "").strip()
    return float(raw) if raw else default


def _str(env: dict, name: str, default: str) -> str:
    raw = env.get(name, "").strip()
    return raw or default


@dataclass(frozen=True)
class Settings:
    llm_provider: str = "anthropic"
    llm_fallback_provider: str = ""
    llm_max_rpm: int = 30
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5-5"
    nvidia_api_key: str = ""
    nvidia_base_url: str = "https://integrate.api.nvidia.com/v1"
    nvidia_model: str = "openai/gpt-oss-20b"
    nvidia_temperature: float = 1.0
    nvidia_top_p: float = 1.0
    nvidia_max_tokens: int = 4096
    query_url: str = "http://seatmap-query:8080"
    concert_id: str = "neon-tide-2026"
    docs_dir: str = "./docs"
    port: int = 8080
    # Tunables (spec 9.6 values by default; overridable mainly for tests)
    queue_timeout_s: float = 8.0
    first_token_timeout_s: float = 8.0
    total_timeout_s: float = 60.0
    tool_timeout_s: float = 5.0
    session_msgs_per_hour: int = 20
    session_ttl_s: float = 3600.0
    warnings: tuple = field(default=(), compare=False)

    @classmethod
    def from_env(cls, env: dict | None = None) -> "Settings":
        e = dict(os.environ if env is None else env)
        provider = _str(e, "LLM_PROVIDER", "anthropic").lower()
        fallback = e.get("LLM_FALLBACK_PROVIDER", "").strip().lower()
        for p in (provider, fallback):
            if p and p not in PROVIDERS:
                raise ValueError(f"Unknown LLM provider '{p}'; expected one of {PROVIDERS}")
        return cls(
            llm_provider=provider,
            llm_fallback_provider=fallback,
            llm_max_rpm=_int(e, "LLM_MAX_RPM", 30),
            anthropic_api_key=e.get("ANTHROPIC_API_KEY", "").strip(),
            anthropic_model=_str(e, "ANTHROPIC_MODEL", "claude-sonnet-5-5"),
            nvidia_api_key=e.get("NVIDIA_API_KEY", "").strip(),
            nvidia_base_url=_str(e, "NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1"),
            nvidia_model=_str(e, "NVIDIA_MODEL", "openai/gpt-oss-20b"),
            nvidia_temperature=_float(e, "NVIDIA_TEMPERATURE", 1.0),
            nvidia_top_p=_float(e, "NVIDIA_TOP_P", 1.0),
            nvidia_max_tokens=_int(e, "NVIDIA_MAX_TOKENS", 4096),
            query_url=_str(e, "QUERY_URL", "http://seatmap-query:8080").rstrip("/"),
            concert_id=_str(e, "CONCERT_ID", "neon-tide-2026"),
            docs_dir=_str(e, "DOCS_DIR", "./docs"),
            port=_int(e, "PORT", 8080),
            queue_timeout_s=_float(e, "LLM_QUEUE_TIMEOUT_S", 8.0),
            first_token_timeout_s=_float(e, "LLM_FIRST_TOKEN_TIMEOUT_S", 8.0),
            total_timeout_s=_float(e, "LLM_TOTAL_TIMEOUT_S", 60.0),
            tool_timeout_s=_float(e, "TOOL_TIMEOUT_S", 5.0),
            session_msgs_per_hour=_int(e, "SESSION_MSGS_PER_HOUR", 20),
        )

    def has_key(self, provider: str) -> bool:
        if provider == "anthropic":
            return bool(self.anthropic_api_key)
        if provider == "nvidia":
            return bool(self.nvidia_api_key)
        return False

    def provider_chain(self) -> list[str]:
        """Providers to try in order; only those with an API key."""
        chain: list[str] = []
        for p in (self.llm_provider, self.llm_fallback_provider):
            if p and p not in chain and self.has_key(p):
                chain.append(p)
        return chain

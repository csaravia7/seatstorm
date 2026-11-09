import json

import pytest

from app.main import create_app
from app.prompt import BASE_PROMPT, DocsError, MAX_DOCS_BYTES, build_system_prompt, load_docs
from conftest import ANTHROPIC_URL, NVIDIA_URL, SEED_DOCS, anthropic_text, make_settings, openai_text


def test_seed_docs_exist_and_fit_budget():
    names = {n for n, _ in load_docs(SEED_DOCS)}
    assert names == {"faq.md", "how-it-works.md", "reasons.md"}
    assert sum(len(t.encode()) for _, t in load_docs(SEED_DOCS)) < MAX_DOCS_BYTES


def test_over_12kb_fails_with_clear_message(tmp_path):
    (tmp_path / "a.md").write_text("x" * (MAX_DOCS_BYTES + 1))
    with pytest.raises(DocsError, match="12 KB"):
        build_system_prompt(tmp_path)


def test_missing_docs_dir_fails_clearly(tmp_path):
    with pytest.raises(DocsError, match="DOCS_DIR"):
        build_system_prompt(tmp_path / "nope")


async def test_startup_fails_when_docs_too_big(tmp_path):
    (tmp_path / "big.md").write_text("y" * (MAX_DOCS_BYTES + 10))
    app = create_app(lambda: make_settings(docs_dir=str(tmp_path)))
    with pytest.raises(DocsError):
        async with app.router.lifespan_context(app):
            pass


def test_prompt_contains_base_text_and_all_docs():
    p = build_system_prompt(SEED_DOCS)
    assert p.startswith(BASE_PROMPT)
    for _, text in load_docs(SEED_DOCS):
        assert text.strip() in p


async def test_anthropic_request_has_docs_and_cache_control(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("ok"))
    async with app_factory() as (chat, _):
        await chat.events()
    system = json.loads(route.calls[0].request.content)["system"]
    assert isinstance(system, list) and system[0]["cache_control"] == {"type": "ephemeral"}
    assert "HOLD_EXPIRED" in system[0]["text"] and "Cómo funciona SeatStorm" in system[0]["text"]


async def test_nvidia_request_has_docs_in_system_message(app_factory, mock):
    route = mock.post(NVIDIA_URL).mock(return_value=openai_text("ok"))
    async with app_factory(llm_provider="nvidia") as (chat, _):
        await chat.events()
    first = json.loads(route.calls[0].request.content)["messages"][0]
    assert first["role"] == "system" and "Preguntas frecuentes" in first["content"]

import json

import pytest

from feiwu_bridge.assistant import PERSONA_PROMPT, SYSTEM_PROMPT, AssistantService
from feiwu_bridge.schemas import AssistantRequest


class FakeProvider:
    def __init__(self, responses: list[str]) -> None:
        self.responses = responses
        self.calls: list[list[dict[str, str]]] = []

    async def complete(self, messages: list[dict[str, str]]) -> str:
        self.calls.append(messages)
        return self.responses.pop(0)


@pytest.mark.asyncio
async def test_returns_natural_reply_and_includes_history() -> None:
    provider = FakeProvider([json.dumps({"command": "", "reply": "北京今天可能较冷。"})])
    service = AssistantService(provider)  # type: ignore[arg-type]
    request = AssistantRequest.model_validate({
        "message": "那北京呢？",
        "client_time": "2026-11-03 20:00",
        "history": [{"user": "上海天气怎么样？", "assistant": "上海今天较凉。"}],
    })

    result = await service.answer(request)

    assert result.reply == "北京今天可能较冷。"
    assert provider.calls[0][0] == {"role": "system", "content": SYSTEM_PROMPT}
    assert provider.calls[0][1]["content"] == "上海天气怎么样？"


def test_system_prompt_includes_emo_empathic_persona() -> None:
    assert PERSONA_PROMPT in SYSTEM_PROMPT
    assert "克制的 emo 气质" in SYSTEM_PROMPT
    assert "先接住情绪，再回应事情本身" in SYSTEM_PROMPT
    assert "不要强行把话题变消沉" in SYSTEM_PROMPT


@pytest.mark.asyncio
async def test_retries_once_when_provider_returns_invalid_json() -> None:
    provider = FakeProvider([
        "not-json",
        json.dumps({"command": "提醒我明天八点开会", "reply": ""}),
    ])
    service = AssistantService(provider)  # type: ignore[arg-type]

    result = await service.answer(AssistantRequest(message="明早提醒我开会"))

    assert result.command == "提醒我明天八点开会"
    assert len(provider.calls) == 2


import json

import pytest

from feiwu_bridge.assistant import AssistantService
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
    assert provider.calls[0][1]["content"] == "上海天气怎么样？"


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


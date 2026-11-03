import httpx
import pytest
from cryptography.fernet import Fernet

from feiwu_bridge.config import Settings
from feiwu_bridge.provider import OpenAICompatibleProvider
from feiwu_bridge.store import ProviderConfig


class FakeStore:
    async def get(self) -> ProviderConfig:
        return ProviderConfig(
            base_url="https://api.deepseek.com",
            chat_path="/chat/completions",
            model="deepseek-flash",
            api_key="test-provider-key",
        )


def settings() -> Settings:
    return Settings(
        client_token="a" * 32,
        admin_token="b" * 32,
        config_encryption_key=Fernet.generate_key().decode(),
        provider_retries=0,
    )


@pytest.mark.asyncio
async def test_provider_sends_key_only_to_configured_provider() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "https://api.deepseek.com/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-provider-key"
        return httpx.Response(200, json={
            "choices": [{"message": {"content": '{"command":"","reply":"你好"}'}}],
        })

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    provider = OpenAICompatibleProvider(settings(), FakeStore(), client)  # type: ignore[arg-type]

    content = await provider.complete([{"role": "user", "content": "你好"}])

    assert '"reply":"你好"' in content
    await client.aclose()



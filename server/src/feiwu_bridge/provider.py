import asyncio
import logging
from collections.abc import Sequence

import httpx

from .config import Settings
from .store import ProviderConfigStore

logger = logging.getLogger(__name__)


class ProviderError(RuntimeError):
    pass


class OpenAICompatibleProvider:
    def __init__(
        self,
        settings: Settings,
        store: ProviderConfigStore,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.settings = settings
        self.store = store
        self._owns_client = client is None
        self.client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(settings.provider_timeout_seconds),
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    async def complete(self, messages: Sequence[dict[str, str]]) -> str:
        config = await self.store.get()
        if not config.configured:
            raise ProviderError("third-party API key is not configured")
        payload = {
            "model": config.model,
            "messages": list(messages),
            "stream": False,
            "max_tokens": self.settings.provider_max_tokens,
            "temperature": self.settings.provider_temperature,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
        }
        attempts = self.settings.provider_retries + 1
        for attempt in range(attempts):
            try:
                response = await self.client.post(
                    config.chat_url,
                    headers=headers,
                    json=payload,
                )
            except httpx.TimeoutException as exc:
                if attempt + 1 >= attempts:
                    raise ProviderError("third-party model request timed out") from exc
                await asyncio.sleep(0.4 * (2**attempt))
                continue
            except httpx.HTTPError as exc:
                raise ProviderError("could not connect to third-party model") from exc

            if response.status_code < 400:
                try:
                    content = response.json()["choices"][0]["message"]["content"]
                except (KeyError, IndexError, TypeError, ValueError) as exc:
                    raise ProviderError("third-party model returned an invalid response") from exc
                return "" if content is None else str(content).strip()

            retryable = response.status_code == 429 or response.status_code >= 500
            logger.warning("Model provider returned HTTP %s", response.status_code)
            if retryable and attempt + 1 < attempts:
                await asyncio.sleep(0.4 * (2**attempt))
                continue
            if response.status_code in {401, 403}:
                raise ProviderError("third-party API authentication failed")
            if response.status_code == 402:
                raise ProviderError("third-party API account has insufficient balance")
            raise ProviderError(f"third-party model returned HTTP {response.status_code}")

        raise ProviderError("third-party model request failed")

    async def close(self) -> None:
        if self._owns_client:
            await self.client.aclose()


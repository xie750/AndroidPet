from pathlib import Path

import pytest
from cryptography.fernet import Fernet

from feiwu_bridge.config import Settings
from feiwu_bridge.schemas import ProviderConfigUpdate
from feiwu_bridge.store import ProviderConfigStore


def make_settings(data_dir: Path) -> Settings:
    return Settings(
        client_token="a" * 32,
        admin_token="b" * 32,
        config_encryption_key=Fernet.generate_key().decode(),
        provider_api_key="initial-secret-key",
        data_dir=data_dir,
    )


@pytest.mark.asyncio
async def test_api_key_is_encrypted_and_never_returned(tmp_path: Path) -> None:
    store = ProviderConfigStore(make_settings(tmp_path))
    await store.initialize()
    await store.update(ProviderConfigUpdate(
        base_url="https://api.deepseek.com",
        chat_path="/chat/completions",
        model="deepseek-flash",
        api_key="new-secret-key",
    ))

    raw = (tmp_path / "provider.json").read_text(encoding="utf-8")
    view = await store.view()
    saved = await store.get()

    assert "new-secret-key" not in raw
    assert view.api_key_configured is True
    assert not hasattr(view, "api_key")
    assert saved.api_key == "new-secret-key"

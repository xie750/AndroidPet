import asyncio
import json
from dataclasses import dataclass
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken

from .config import Settings
from .schemas import ProviderConfigUpdate, ProviderConfigView


class ConfigStoreError(RuntimeError):
    pass


@dataclass(frozen=True)
class ProviderConfig:
    base_url: str
    chat_path: str
    model: str
    api_key: str

    @property
    def chat_url(self) -> str:
        return self.base_url.rstrip("/") + "/" + self.chat_path.lstrip("/")

    @property
    def configured(self) -> bool:
        return bool(self.api_key.strip())


class ProviderConfigStore:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.path = Path(settings.data_dir).resolve() / "provider.json"
        try:
            key = settings.config_encryption_key.get_secret_value().encode("utf-8")
            self.cipher = Fernet(key)
        except (ValueError, TypeError) as exc:
            raise ConfigStoreError("FEIWU_CONFIG_ENCRYPTION_KEY is not a valid Fernet key") from exc
        self.lock = asyncio.Lock()

    async def initialize(self) -> None:
        async with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            if not self.path.exists():
                await asyncio.to_thread(self._write, self._bootstrap())

    async def get(self) -> ProviderConfig:
        async with self.lock:
            return await asyncio.to_thread(self._read)

    async def view(self) -> ProviderConfigView:
        config = await self.get()
        return self._view(config)

    async def update(self, update: ProviderConfigUpdate) -> ProviderConfigView:
        async with self.lock:
            current = await asyncio.to_thread(self._read)
            api_key = current.api_key
            if update.clear_api_key:
                api_key = ""
            elif update.api_key is not None and update.api_key.strip():
                api_key = update.api_key.strip()
            config = ProviderConfig(
                base_url=self._validate_base_url(update.base_url),
                chat_path=self._validate_path(update.chat_path),
                model=update.model.strip(),
                api_key=api_key,
            )
            await asyncio.to_thread(self._write, config)
        return self._view(config)

    def _bootstrap(self) -> ProviderConfig:
        return ProviderConfig(
            base_url=self.settings.provider_base_url,
            chat_path=self.settings.provider_chat_path,
            model=self.settings.provider_model,
            api_key=self.settings.provider_api_key.get_secret_value(),
        )

    def _read(self) -> ProviderConfig:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            encrypted = raw.get("api_key_encrypted", "")
            api_key = ""
            if encrypted:
                api_key = self.cipher.decrypt(encrypted.encode("utf-8")).decode("utf-8")
            return ProviderConfig(
                base_url=self._validate_base_url(str(raw["base_url"])),
                chat_path=self._validate_path(str(raw["chat_path"])),
                model=str(raw["model"]).strip(),
                api_key=api_key,
            )
        except (OSError, KeyError, ValueError, json.JSONDecodeError, InvalidToken) as exc:
            raise ConfigStoreError(
                "provider configuration is missing, corrupt, or cannot be decrypted"
            ) from exc

    def _write(self, config: ProviderConfig) -> None:
        encrypted = ""
        if config.api_key:
            encrypted = self.cipher.encrypt(config.api_key.encode("utf-8")).decode("utf-8")
        payload = {
            "base_url": config.base_url,
            "chat_path": config.chat_path,
            "model": config.model,
            "api_key_encrypted": encrypted,
        }
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)

    @staticmethod
    def _view(config: ProviderConfig) -> ProviderConfigView:
        return ProviderConfigView(
            base_url=config.base_url,
            chat_path=config.chat_path,
            model=config.model,
            api_key_configured=config.configured,
        )

    @staticmethod
    def _validate_base_url(value: str) -> str:
        clean = value.strip().rstrip("/")
        if not clean.startswith(("https://", "http://")):
            raise ConfigStoreError("provider base URL must start with http:// or https://")
        return clean

    @staticmethod
    def _validate_path(value: str) -> str:
        clean = value.strip()
        if not clean:
            raise ConfigStoreError("provider chat path cannot be empty")
        return clean if clean.startswith("/") else "/" + clean

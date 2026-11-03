from functools import lru_cache
from pathlib import Path

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="FEIWU_",
        extra="ignore",
    )

    client_token: SecretStr = Field(min_length=24)
    admin_token: SecretStr = Field(min_length=24)
    config_encryption_key: SecretStr = Field(min_length=32)
    admin_allow_remote: bool = False
    provider_base_url: str = "https://api.deepseek.com"
    provider_chat_path: str = "/chat/completions"
    provider_api_key: SecretStr = SecretStr("")
    provider_model: str = Field(default="deepseek-flash", min_length=1)
    provider_timeout_seconds: float = Field(default=35.0, ge=3, le=120)
    provider_max_tokens: int = Field(default=400, ge=64, le=4096)
    provider_temperature: float = Field(default=0.3, ge=0, le=2)
    provider_retries: int = Field(default=2, ge=0, le=4)
    server_host: str = "0.0.0.0"
    server_port: int = Field(default=8765, ge=1, le=65535)
    log_level: str = "info"
    requests_per_minute: int = Field(default=20, ge=1, le=600)
    data_dir: Path = Path("./data")

    @field_validator("provider_base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        clean = value.strip().rstrip("/")
        if not clean.startswith(("https://", "http://")):
            raise ValueError("provider_base_url must start with http:// or https://")
        return clean

    @field_validator("provider_chat_path")
    @classmethod
    def validate_chat_path(cls, value: str) -> str:
        clean = value.strip()
        return clean if clean.startswith("/") else "/" + clean

    @property
    def provider_chat_url(self) -> str:
        return self.provider_base_url + self.provider_chat_path


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]


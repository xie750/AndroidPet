from pydantic import BaseModel, ConfigDict, Field, model_validator


class ConversationTurn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user: str = Field(min_length=1, max_length=500)
    assistant: str = Field(min_length=1, max_length=500)


class AssistantRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message: str = Field(min_length=1, max_length=500)
    client_time: str | None = Field(default=None, max_length=80)
    history: list[ConversationTurn] = Field(default_factory=list, max_length=4)


class AssistantResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    command: str = Field(default="", max_length=240)
    reply: str = Field(default="", max_length=400)

    @model_validator(mode="after")
    def ensure_useful_response(self) -> "AssistantResponse":
        self.command = self.command.strip()
        self.reply = self.reply.strip()
        if not self.command and not self.reply:
            raise ValueError("provider returned neither command nor reply")
        return self


class HealthResponse(BaseModel):
    status: str
    provider: str
    provider_configured: bool


class ProviderConfigUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    base_url: str = Field(min_length=8, max_length=300)
    chat_path: str = Field(default="/chat/completions", min_length=2, max_length=120)
    model: str = Field(min_length=1, max_length=120)
    api_key: str | None = Field(default=None, max_length=500)
    clear_api_key: bool = False


class ProviderConfigView(BaseModel):
    base_url: str
    chat_path: str
    model: str
    api_key_configured: bool


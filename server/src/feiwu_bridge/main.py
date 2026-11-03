from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .assistant import AssistantService
from .config import Settings, get_settings
from .provider import OpenAICompatibleProvider, ProviderError
from .schemas import (
    AssistantRequest,
    AssistantResponse,
    HealthResponse,
    ProviderConfigUpdate,
    ProviderConfigView,
)
from .security import (
    RateLimiter,
    admin_token_header,
    client_token_header,
    verify_admin_access,
    verify_client_token,
)
from .store import ConfigStoreError, ProviderConfigStore


def create_app(settings: Settings | None = None) -> FastAPI:
    active_settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.config_store = ProviderConfigStore(active_settings)
        await app.state.config_store.initialize()
        app.state.provider = OpenAICompatibleProvider(active_settings, app.state.config_store)
        app.state.rate_limiter = RateLimiter(active_settings.requests_per_minute)
        yield
        await app.state.provider.close()

    app = FastAPI(
        title="Feiwu Model Bridge",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    web_root = Path(__file__).parent / "web"
    app.mount("/admin/assets", StaticFiles(directory=web_root), name="admin-assets")

    @app.get("/admin", include_in_schema=False)
    async def admin_page() -> FileResponse:
        return FileResponse(web_root / "index.html")

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        view = await app.state.config_store.view()
        return HealthResponse(
            status="ok",
            provider=view.model,
            provider_configured=view.api_key_configured,
        )

    @app.get("/admin/api/config", response_model=ProviderConfigView)
    async def get_provider_config(
        request: Request,
        token: str | None = Depends(admin_token_header),
    ) -> ProviderConfigView:
        verify_admin_access(active_settings, request, token)
        return await app.state.config_store.view()

    @app.put("/admin/api/config", response_model=ProviderConfigView)
    async def update_provider_config(
        payload: ProviderConfigUpdate,
        request: Request,
        token: str | None = Depends(admin_token_header),
    ) -> ProviderConfigView:
        verify_admin_access(active_settings, request, token)
        try:
            return await app.state.config_store.update(payload)
        except ConfigStoreError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    @app.post("/admin/api/test")
    async def test_provider_config(
        request: Request,
        token: str | None = Depends(admin_token_header),
    ) -> dict[str, str]:
        verify_admin_access(active_settings, request, token)
        service = AssistantService(app.state.provider)
        try:
            response = await service.answer(AssistantRequest(message="用一句简短中文向我问好"))
        except ProviderError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="无法连接第三方模型，请检查地址、模型名、余额和 API Key",
            ) from exc
        return {"status": "ok", "reply": response.reply or response.command}

    @app.post("/v1/assistant", response_model=AssistantResponse)
    async def assistant(
        payload: AssistantRequest,
        request: Request,
        token: str | None = Depends(client_token_header),
    ) -> AssistantResponse:
        verify_client_token(active_settings, token)
        await request.app.state.rate_limiter.check(request)
        service = AssistantService(request.app.state.provider)
        try:
            return await service.answer(payload)
        except ProviderError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="model provider is temporarily unavailable",
            ) from exc

    return app


app = create_app()


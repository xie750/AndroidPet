import uvicorn

from .config import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "feiwu_bridge.main:app",
        host=settings.server_host,
        port=settings.server_port,
        log_level=settings.log_level,
        reload=False,
    )


if __name__ == "__main__":
    main()


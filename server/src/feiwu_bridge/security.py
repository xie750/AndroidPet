import asyncio
import ipaddress
import secrets
import time
from collections import defaultdict, deque

from fastapi import Header, HTTPException, Request, status

from .config import Settings


def verify_client_token(settings: Settings, provided: str | None) -> None:
    expected = settings.client_token.get_secret_value()
    if provided is None or not secrets.compare_digest(expected, provided):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid client token",
        )


def verify_admin_access(settings: Settings, request: Request, provided: str | None) -> None:
    host = request.client.host if request.client else ""
    try:
        is_loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        is_loopback = host == "localhost"
    if not settings.admin_allow_remote and not is_loopback:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="admin access is limited to this computer",
        )
    expected = settings.admin_token.get_secret_value()
    if provided is None or not secrets.compare_digest(expected, provided):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid admin token",
        )


class RateLimiter:
    def __init__(self, requests_per_minute: int) -> None:
        self.limit = requests_per_minute
        self.events: dict[str, deque[float]] = defaultdict(deque)
        self.lock = asyncio.Lock()

    async def check(self, request: Request) -> None:
        key = request.client.host if request.client else "unknown"
        now = time.monotonic()
        async with self.lock:
            events = self.events[key]
            while events and now - events[0] >= 60:
                events.popleft()
            if len(events) >= self.limit:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="too many requests",
                )
            events.append(now)


async def client_token_header(x_feiwu_token: str | None = Header(default=None)) -> str | None:
    return x_feiwu_token


async def admin_token_header(x_feiwu_admin_token: str | None = Header(default=None)) -> str | None:
    return x_feiwu_admin_token


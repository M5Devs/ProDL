import httpx

MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024


async def fetch_thumbnail(url: str, *, timeout: float = 15.0) -> bytes:
    """Download a thumbnail image as raw bytes."""
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
        response = await client.get(url)
        response.raise_for_status()
        content = response.content
    if len(content) > MAX_THUMBNAIL_BYTES:
        raise ValueError("Thumbnail exceeds the 5 MiB limit")
    return content

import hashlib
import html
import re
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from bs4 import BeautifulSoup

_TRACKING_PARAMS = {"fbclid", "gclid", "ref", "ref_src", "mc_cid", "mc_eid", "igshid"}
_STOPWORDS = frozenset(
    "a an the of in on at to for from by with and or as is are was were be been has have had "
    "its it this that these those after before over under into says say said will would may "
    "new amid".split()
)


def clean_text(value: str | None) -> str:
    """Strip markup and collapse whitespace. Feed titles and summaries often contain HTML."""
    if not value:
        return ""
    text = BeautifulSoup(value, "html.parser").get_text(" ")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def html_to_paragraphs(value: str | None) -> str:
    """Like clean_text but keeps paragraph breaks, for article bodies."""
    if not value:
        return ""
    soup = BeautifulSoup(value, "html.parser")
    for tag in soup(["script", "style", "figure", "figcaption", "iframe", "noscript"]):
        tag.decompose()
    blocks = [re.sub(r"\s+", " ", p.get_text(" ")).strip() for p in soup.find_all(["p", "li"])]
    blocks = [b for b in blocks if b]
    return "\n\n".join(blocks) if blocks else clean_text(value)


def canonical_url(url: str) -> str:
    parts = urlsplit(url.strip())
    query = [
        (k, v)
        for k, v in parse_qsl(parts.query, keep_blank_values=True)
        if not k.lower().startswith("utm_") and k.lower() not in _TRACKING_PARAMS
    ]
    path = parts.path.rstrip("/") or "/"
    return urlunsplit(
        (parts.scheme.lower() or "https", parts.netloc.lower(), path, urlencode(query), "")
    )


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def title_tokens(title: str) -> frozenset[str]:
    words = re.findall(r"[a-z0-9]+", title.lower())
    return frozenset(w for w in words if w not in _STOPWORDS and len(w) > 1)


def title_similarity(a: frozenset[str], b: frozenset[str]) -> float:
    """Jaccard similarity of the meaningful words in two headlines."""
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def truncate(value: str, limit: int) -> str:
    value = value.strip()
    if len(value) <= limit:
        return value
    return value[: limit - 1].rsplit(" ", 1)[0].rstrip(",.;: ") + "…"

#!/usr/bin/env python3
"""
catalog_tool.py — BestiaPop Music Catalog Investigation & Download CLI Toolkit

Utility script for inspecting online music catalog APIs (Deezer, iTunes, YouTube)
and extracting/downloading audio streams with full parameterization.

Mirroring BestiaPop's Android implementation:
- MetadataFetcher.kt (Deezer & iTunes API parsers, album merge, gap-filling)
- YouTubeExtractor.kt (InnerTube API search, client rotation, audio stream extraction)
"""

import sys
import os
import re
import json
import argparse
import urllib.parse
from typing import Optional, Dict, Any, List

try:
    import requests
except ImportError:
    print("Error: 'requests' library required. Install via: pip install requests", file=sys.stderr)
    sys.exit(1)

try:
    import mutagen
    from mutagen.easyid3 import EasyID3
    from mutagen.mp4 import MP4, MP4Tags
    MUTAGEN_AVAILABLE = True
except ImportError:
    MUTAGEN_AVAILABLE = False

# Default HTTP Settings
DEFAULT_TIMEOUT = 15
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/133.0.0.0 Safari/537.36 BestiaPop/1.0"
)

DEEZER_API_BASE = "https://api.deezer.com"
ITUNES_API_BASE = "https://itunes.apple.com"
YOUTUBE_WEB_BASE = "https://www.youtube.com"
YOUTUBE_API_BASE = "https://youtubei.googleapis.com"


# ==============================================================================
# Helper Functions & Normalization
# ==============================================================================

def normalize_text(text: str) -> str:
    """Normalize text for cross-catalog comparison."""
    if not text:
        return ""
    lowered = text.lower()
    cleaned = re.sub(r"[^\w\s]+", " ", lowered, flags=re.UNICODE)
    return " ".join(cleaned.split())


def is_album_match(cand_title: str, cand_artist: str, target_title: str, target_artist: str) -> bool:
    """Evaluate whether candidate album matches target album and artist."""
    c_title = normalize_text(cand_title)
    t_title = normalize_text(target_title)
    if not c_title or not t_title:
        return False
    if c_title != t_title and t_title not in c_title and c_title not in t_title:
        return False
    if target_artist and cand_artist:
        c_art = normalize_text(cand_artist)
        t_art = normalize_text(target_artist)
        if c_art and t_art and (c_art != t_art and t_art not in c_art and c_art not in t_art):
            return False
    return True


def http_get_json(url: str, params: Optional[Dict[str, Any]] = None, timeout: int = DEFAULT_TIMEOUT, user_agent: str = DEFAULT_USER_AGENT) -> Optional[Dict[str, Any]]:
    headers = {"User-Agent": user_agent, "Accept": "application/json"}
    try:
        resp = requests.get(url, params=params, headers=headers, timeout=timeout)
        if resp.status_code == 200:
            return resp.json()
        print(f"[HTTP {resp.status_code}] {url}", file=sys.stderr)
    except Exception as e:
        print(f"[HTTP Error] {url}: {e}", file=sys.stderr)
    return None


# ==============================================================================
# 1. Deezer API Client
# ==============================================================================

class DeezerClient:
    def __init__(self, base_url: str = DEEZER_API_BASE, timeout: int = DEFAULT_TIMEOUT, user_agent: str = DEFAULT_USER_AGENT):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.user_agent = user_agent

    def search_artist(self, query: str, limit: int = 10) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/search/artist"
        data = http_get_json(url, params={"q": query, "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("data", []) if data else []

    def get_artist_albums(self, artist_id: int, limit: int = 50) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/artist/{artist_id}/albums"
        data = http_get_json(url, params={"limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("data", []) if data else []

    def get_artist_top_tracks(self, artist_id: int, limit: int = 30) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/artist/{artist_id}/top"
        data = http_get_json(url, params={"limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("data", []) if data else []

    def search_album(self, title: str, artist: Optional[str] = None, limit: int = 10) -> List[Dict[str, Any]]:
        query = f'artist:"{artist}" album:"{title}"' if artist else f'album:"{title}"'
        url = f"{self.base_url}/search/album"
        data = http_get_json(url, params={"q": query, "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        results = data.get("data", []) if data else []
        if not results:
            fallback_q = f"{artist} {title}".strip() if artist else title
            data = http_get_json(url, params={"q": fallback_q, "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
            results = data.get("data", []) if data else []
        return results

    def get_album_tracks(self, album_id: int, limit: int = 100) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/album/{album_id}/tracks"
        data = http_get_json(url, params={"limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("data", []) if data else []

    def search_track(self, title: str, artist: Optional[str] = None, limit: int = 20) -> List[Dict[str, Any]]:
        query = f'artist:"{artist}" track:"{title}"' if artist else title
        url = f"{self.base_url}/search/track"
        data = http_get_json(url, params={"q": query, "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("data", []) if data else []


# ==============================================================================
# 2. iTunes Search & Lookup Client
# ==============================================================================

class ItunesClient:
    def __init__(self, base_url: str = ITUNES_API_BASE, timeout: int = DEFAULT_TIMEOUT, user_agent: str = DEFAULT_USER_AGENT):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.user_agent = user_agent

    def search_album(self, title: str, artist: Optional[str] = None, limit: int = 5) -> List[Dict[str, Any]]:
        term = f"{artist} {title}".strip() if artist else title
        url = f"{self.base_url}/search"
        data = http_get_json(url, params={"term": term, "entity": "album", "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("results", []) if data else []

    def lookup_album_tracks(self, collection_id: int, limit: int = 100) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/lookup"
        data = http_get_json(url, params={"id": collection_id, "entity": "song", "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        if not data:
            return []
        tracks = [item for item in data.get("results", []) if item.get("wrapperType") == "track"]
        return tracks

    def search_song(self, title: str, artist: Optional[str] = None, limit: int = 20) -> List[Dict[str, Any]]:
        term = f"{artist} {title}".strip() if artist else title
        url = f"{self.base_url}/search"
        data = http_get_json(url, params={"term": term, "entity": "song", "limit": limit}, timeout=self.timeout, user_agent=self.user_agent)
        return data.get("results", []) if data else []


# ==============================================================================
# 3. BestiaPop Album Resolver (Deezer + iTunes Merge & Gap-Filling)
# ==============================================================================

def resolve_album_tracks(
    artist: str,
    title: str,
    album_id: Optional[str] = None,
    cover_url: Optional[str] = None,
    timeout: int = DEFAULT_TIMEOUT
) -> List[Dict[str, Any]]:
    """
    Replicates BestiaPop's MetadataFetcher.fetchAlbumTrackCandidates:
    1. Direct Deezer by albumId (if numeric)
    2. Deezer album search if direct fails
    3. Direct iTunes lookup by albumId (if numeric)
    4. iTunes album search + lookup
    5. Slot merging with gap-filling without duplicate rows
    """
    deezer = DeezerClient(timeout=timeout)
    itunes = ItunesClient(timeout=timeout)

    deezer_tracks: List[Dict[str, Any]] = []
    # 1. Deezer direct
    if album_id and album_id.isdigit():
        raw_tracks = deezer.get_album_tracks(int(album_id))
        for i, t in enumerate(raw_tracks):
            deezer_tracks.append({
                "track_number": t.get("track_position", i + 1),
                "disc_number": t.get("disk_number", 1),
                "title": t.get("title", f"Pista {i + 1}"),
                "artist": t.get("artist", {}).get("name", artist),
                "album": title,
                "duration_ms": t.get("duration", 0) * 1000,
                "isrc": t.get("isrc"),
                "source": "Deezer",
                "source_id": str(t.get("id"))
            })

    # 2. Deezer search
    if not deezer_tracks and title:
        albums = deezer.search_album(title, artist=artist, limit=5)
        matched_id = None
        for alb in albums:
            if is_album_match(alb.get("title", ""), alb.get("artist", {}).get("name", ""), title, artist):
                matched_id = alb.get("id")
                break
        if not matched_id and albums:
            matched_id = albums[0].get("id")

        if matched_id:
            raw_tracks = deezer.get_album_tracks(matched_id)
            for i, t in enumerate(raw_tracks):
                deezer_tracks.append({
                    "track_number": t.get("track_position", i + 1),
                    "disc_number": t.get("disk_number", 1),
                    "title": t.get("title", f"Pista {i + 1}"),
                    "artist": t.get("artist", {}).get("name", artist),
                    "album": title,
                    "duration_ms": t.get("duration", 0) * 1000,
                    "isrc": t.get("isrc"),
                    "source": "Deezer",
                    "source_id": str(t.get("id"))
                })

    itunes_tracks: List[Dict[str, Any]] = []
    # 3. iTunes direct
    if album_id and album_id.isdigit():
        raw_tracks = itunes.lookup_album_tracks(int(album_id))
        for i, t in enumerate(raw_tracks):
            itunes_tracks.append({
                "track_number": t.get("trackNumber", i + 1),
                "disc_number": t.get("discNumber", 1),
                "title": t.get("trackName", f"Pista {i + 1}"),
                "artist": t.get("artistName", artist),
                "album": t.get("collectionName", title),
                "duration_ms": t.get("trackTimeMillis", 0),
                "isrc": None,
                "source": "iTunes",
                "source_id": str(t.get("trackId"))
            })

    # 4. iTunes search
    if not itunes_tracks and title:
        albums = itunes.search_album(title, artist=artist, limit=5)
        matched_id = None
        for alb in albums:
            if is_album_match(alb.get("collectionName", ""), alb.get("artistName", ""), title, artist):
                matched_id = alb.get("collectionId")
                break
        if not matched_id and albums:
            matched_id = albums[0].get("collectionId")

        if matched_id:
            raw_tracks = itunes.lookup_album_tracks(matched_id)
            for i, t in enumerate(raw_tracks):
                itunes_tracks.append({
                    "track_number": t.get("trackNumber", i + 1),
                    "disc_number": t.get("discNumber", 1),
                    "title": t.get("trackName", f"Pista {i + 1}"),
                    "artist": t.get("artistName", artist),
                    "album": t.get("collectionName", title),
                    "duration_ms": t.get("trackTimeMillis", 0),
                    "isrc": None,
                    "source": "iTunes",
                    "source_id": str(t.get("trackId"))
                })

    # 5. Merge & Gap-filling
    if not deezer_tracks:
        return itunes_tracks
    if not itunes_tracks:
        return deezer_tracks

    merged = list(deezer_tracks)
    existing_nums = {t["track_number"] for t in deezer_tracks if t["track_number"] > 0}
    existing_titles = {normalize_text(t["title"]) for t in deezer_tracks if t.get("title")}

    for track in itunes_tracks:
        t_num = track["track_number"]
        n_title = normalize_text(track["title"])
        num_match = t_num > 0 and t_num in existing_nums
        title_match = n_title and n_title in existing_titles

        if not num_match and not title_match:
            merged.append(track)
        else:
            for m in merged:
                if (t_num > 0 and m["track_number"] == t_num) or (n_title and normalize_text(m["title"]) == n_title):
                    m.setdefault("sources", []).append({"source": "iTunes", "id": track["source_id"]})
                    break

    merged.sort(key=lambda x: (x.get("disc_number", 1), x.get("track_number", 999), x.get("title", "")))
    return merged


# ==============================================================================
# 4. YouTube Search & Stream Extractor
# ==============================================================================

class YouTubeExtractorClient:
    CLIENT_PROFILES = [
        {
            "name": "TVHTML5",
            "version": "5.20260707",
            "clientId": "7",
            "userAgent": "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            "osName": "TV",
            "osVersion": "5.0",
            "apiKey": ""
        },
        {
            "name": "VISIONOS",
            "version": "1.02",
            "clientId": "101",
            "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15",
            "osName": "visionOS",
            "osVersion": "26.5.23O471",
            "apiKey": ""
        },
        {
            "name": "ANDROID",
            "version": "21.26.364",
            "clientId": "3",
            "userAgent": "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            "osName": "Android",
            "osVersion": "11",
            "apiKey": "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        }
    ]

    def __init__(self, timeout: int = DEFAULT_TIMEOUT):
        self.timeout = timeout
        self.session = requests.Session()

    def search(self, query: str, limit: int = 15) -> List[Dict[str, Any]]:
        url = f"{YOUTUBE_WEB_BASE}/results"
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            "Accept-Language": "es-ES,es;q=0.9,en;q=0.8"
        }
        try:
            resp = self.session.get(url, params={"search_query": query}, headers=headers, timeout=self.timeout)
            if resp.status_code != 200:
                return []
            match = re.search(r"var ytInitialData\s*=\s*({.+?});</script>", resp.text)
            if not match:
                return []
            data = json.loads(match.group(1))
            contents = (
                data.get("contents", {})
                .get("twoColumnSearchResultsRenderer", {})
                .get("primaryContents", {})
                .get("sectionListRenderer", {})
                .get("contents", [])
            )
            items = []
            for sec in contents:
                item_section = sec.get("itemSectionRenderer", {}).get("contents", [])
                for itm in item_section:
                    v = itm.get("videoRenderer") or itm.get("compactVideoRenderer")
                    if not v:
                        continue
                    vid_id = v.get("videoId")
                    if not vid_id:
                        continue
                    title = ""
                    title_obj = v.get("title", {})
                    if "runs" in title_obj and title_obj["runs"]:
                        title = title_obj["runs"][0].get("text", "")
                    elif "simpleText" in title_obj:
                        title = title_obj["simpleText"]
                    
                    author = ""
                    byline = v.get("ownerText") or v.get("longBylineText")
                    if byline and "runs" in byline and byline["runs"]:
                        author = byline["runs"][0].get("text", "")
                    
                    dur_text = v.get("lengthText", {}).get("simpleText", "")
                    thumbnails = v.get("thumbnail", {}).get("thumbnails", [])
                    art = thumbnails[-1].get("url") if thumbnails else None

                    items.append({
                        "id": vid_id,
                        "title": title,
                        "author": author,
                        "duration_text": dur_text,
                        "artwork": art,
                        "url": f"https://www.youtube.com/watch?v={vid_id}"
                    })
                    if len(items) >= limit:
                        return items
            return items
        except Exception as e:
            print(f"[YouTube Search Error] {e}", file=sys.stderr)
            return []

    def extract_stream_url(self, query_or_url: str) -> Optional[Dict[str, Any]]:
        trimmed = query_or_url.strip()
        vid_match = re.search(r"(?:v=|/v/|youtu\.be/|/embed/|^)([a-zA-Z0-9_-]{11})(?:[&?]|$)", trimmed)
        video_id = vid_match.group(1) if vid_match else None

        # If not a valid video ID/URL, search YouTube first
        if not video_id:
            hits = self.search(trimmed, limit=1)
            if hits:
                video_id = hits[0]["id"]
            else:
                return None

        # Attempt extraction via InnerTube client profiles
        for profile in self.CLIENT_PROFILES:
            endpoint = (
                f"{YOUTUBE_WEB_BASE}/youtubei/v1/player"
                if not profile["apiKey"]
                else f"{YOUTUBE_API_BASE}/youtubei/v1/player?key={profile['apiKey']}"
            )
            payload = {
                "videoId": video_id,
                "context": {
                    "client": {
                        "clientName": profile["name"],
                        "clientVersion": profile["version"],
                        "hl": "es",
                        "gl": "US",
                        "userAgent": profile["userAgent"],
                        "osName": profile["osName"],
                        "osVersion": profile["osVersion"]
                    }
                },
                "playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS"}},
                "contentCheckOk": True,
                "racyCheckOk": True
            }
            headers = {
                "User-Agent": profile["userAgent"],
                "X-YouTube-Client-Name": profile["clientId"],
                "X-YouTube-Client-Version": profile["version"],
                "Content-Type": "application/json"
            }
            try:
                resp = self.session.post(endpoint, json=payload, headers=headers, timeout=self.timeout)
                if resp.status_code != 200:
                    continue
                res_data = resp.json()
                streaming_data = res_data.get("streamingData", {})
                formats = streaming_data.get("adaptiveFormats", []) + streaming_data.get("formats", [])
                audio_formats = [
                    f for f in formats
                    if f.get("mimeType", "").startswith("audio/") and "url" in f
                ]
                if not audio_formats:
                    continue
                best_audio = max(audio_formats, key=lambda x: x.get("bitrate", 0))
                video_details = res_data.get("videoDetails", {})
                return {
                    "video_id": video_id,
                    "title": video_details.get("title", ""),
                    "author": video_details.get("author", ""),
                    "duration_seconds": video_details.get("lengthSeconds", "0"),
                    "audio_url": best_audio["url"],
                    "mime_type": best_audio.get("mimeType", ""),
                    "bitrate": best_audio.get("bitrate", 0),
                    "client_used": profile["name"]
                }
            except Exception:
                continue

        # Fallback: Invidious public instance helper
        for inv_host in ["https://invidious.nerdvpn.de", "https://inv.nadeko.net"]:
            try:
                inv_resp = self.session.get(f"{inv_host}/api/v1/videos/{video_id}", timeout=6)
                if inv_resp.status_code == 200:
                    inv_data = inv_resp.json()
                    inv_fmts = inv_data.get("adaptiveFormats", [])
                    inv_audios = [f for f in inv_fmts if f.get("type", "").startswith("audio/") and "url" in f]
                    if inv_audios:
                        best = max(inv_audios, key=lambda x: int(x.get("bitrate", 0)))
                        return {
                            "video_id": video_id,
                            "title": inv_data.get("title", ""),
                            "author": inv_data.get("author", ""),
                            "duration_seconds": str(inv_data.get("lengthSeconds", 0)),
                            "audio_url": best["url"],
                            "mime_type": best.get("type", ""),
                            "bitrate": int(best.get("bitrate", 0)),
                            "client_used": f"Invidious ({inv_host})"
                        }
            except Exception:
                continue

        return None


# ==============================================================================
# 5. Audio Downloader with Chunking, Progress & Tagging
# ==============================================================================

def tag_audio_file(
    file_path: str,
    title: str,
    artist: str,
    album: str = "",
    track_number: int = 0
):
    """Embed ID3 / MP4 tags if mutagen is available."""
    if not MUTAGEN_AVAILABLE:
        return
    try:
        if file_path.endswith(".m4a") or file_path.endswith(".mp4"):
            audio = MP4(file_path)
            audio["\xa9nam"] = title
            audio["\xa9ART"] = artist
            if album:
                audio["\xa9alb"] = album
            if track_number > 0:
                audio["trkn"] = [(track_number, 0)]
            audio.save()
        elif file_path.endswith(".mp3"):
            audio = EasyID3(file_path)
            audio["title"] = title
            audio["artist"] = artist
            if album:
                audio["album"] = album
            if track_number > 0:
                audio["tracknumber"] = str(track_number)
            audio.save()
    except Exception as e:
        print(f"[Tagging Warning] Could not embed tags: {e}", file=sys.stderr)


def download_file(
    url: str,
    output_path: str,
    title: str = "",
    artist: str = "",
    album: str = "",
    track_number: int = 0,
    chunk_size: int = 65536,
    timeout: int = 30
) -> bool:
    headers = {"User-Agent": DEFAULT_USER_AGENT}
    try:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with requests.get(url, headers=headers, stream=True, timeout=timeout) as resp:
            resp.raise_for_status()
            total_size = int(resp.headers.get("content-length", 0))
            downloaded = 0
            with open(output_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=chunk_size):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total_size > 0:
                            pct = (downloaded / total_size) * 100
                            print(f"\rDownloading: {pct:.1f}% ({downloaded / 1024 / 1024:.2f}MB / {total_size / 1024 / 1024:.2f}MB)", end="", flush=True)
                        else:
                            print(f"\rDownloading: {downloaded / 1024 / 1024:.2f}MB", end="", flush=True)
            print(" -> Complete!")
            if title and artist:
                tag_audio_file(output_path, title, artist, album, track_number)
            return True
    except Exception as e:
        print(f"\n[Download Failed] {output_path}: {e}", file=sys.stderr)
        if os.path.exists(output_path):
            try:
                os.remove(output_path)
            except Exception:
                pass
        return False


# ==============================================================================
# CLI Commands Dispatcher
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="BestiaPop Music Catalog & YouTube Extractor CLI Toolkit",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # --- Deezer Subcommands ---
    p_deezer = subparsers.add_parser("deezer", help="Query Deezer Catalog API")
    deezer_subs = p_deezer.add_subparsers(dest="deezer_action", required=True)

    p_d_art = deezer_subs.add_parser("search-artist", help="Search artist on Deezer")
    p_d_art.add_argument("query", help="Artist name or search term")
    p_d_art.add_argument("--limit", type=int, default=10, help="Max results")

    p_d_alb = deezer_subs.add_parser("search-album", help="Search album on Deezer")
    p_d_alb.add_argument("title", help="Album title")
    p_d_alb.add_argument("--artist", help="Artist name")
    p_d_alb.add_argument("--limit", type=int, default=10)

    p_d_art_alb = deezer_subs.add_parser("artist-albums", help="List albums of an artist")
    p_d_art_alb.add_argument("--artist-id", type=int, required=True, help="Deezer Artist ID")
    p_d_art_alb.add_argument("--limit", type=int, default=50)

    p_d_art_top = deezer_subs.add_parser("artist-top", help="Top tracks of an artist")
    p_d_art_top.add_argument("--artist-id", type=int, required=True, help="Deezer Artist ID")
    p_d_art_top.add_argument("--limit", type=int, default=30)

    p_d_tracks = deezer_subs.add_parser("album-tracks", help="List tracks of a Deezer album")
    p_d_tracks.add_argument("--album-id", type=int, required=True, help="Deezer Album ID")

    p_d_song = deezer_subs.add_parser("search-track", help="Search track on Deezer")
    p_d_song.add_argument("title", help="Track title")
    p_d_song.add_argument("--artist", help="Artist name")
    p_d_song.add_argument("--limit", type=int, default=20)

    # --- iTunes Subcommands ---
    p_itunes = subparsers.add_parser("itunes", help="Query Apple iTunes Catalog API")
    itunes_subs = p_itunes.add_subparsers(dest="itunes_action", required=True)

    p_i_alb = itunes_subs.add_parser("search-album", help="Search album on iTunes")
    p_i_alb.add_argument("title", help="Album title")
    p_i_alb.add_argument("--artist", help="Artist name")
    p_i_alb.add_argument("--limit", type=int, default=5)

    p_i_tracks = itunes_subs.add_parser("album-tracks", help="Lookup tracks of an iTunes album (collectionId)")
    p_i_tracks.add_argument("--collection-id", type=int, required=True, help="iTunes Collection ID")

    p_i_song = itunes_subs.add_parser("search-song", help="Search song on iTunes")
    p_i_song.add_argument("title", help="Song title")
    p_i_song.add_argument("--artist", help="Artist name")
    p_i_song.add_argument("--limit", type=int, default=20)

    # --- BestiaPop Album Resolver ---
    p_album = subparsers.add_parser("album", help="Resolve album tracklist with Deezer+iTunes merge and gap-filling")
    p_album.add_argument("--artist", required=True, help="Artist name")
    p_album.add_argument("--title", required=True, help="Album title")
    p_album.add_argument("--album-id", default="", help="Optional Deezer or iTunes ID")
    p_album.add_argument("--json", action="store_true", help="Print raw JSON output")

    # --- YouTube Subcommands ---
    p_yt = subparsers.add_parser("youtube", help="YouTube search and audio stream extraction")
    yt_subs = p_yt.add_subparsers(dest="yt_action", required=True)

    p_yt_search = yt_subs.add_parser("search", help="Search YouTube for query")
    p_yt_search.add_argument("query", help="Search term")
    p_yt_search.add_argument("--limit", type=int, default=10)

    p_yt_stream = yt_subs.add_parser("extract-stream", help="Extract audio stream URL for video or query")
    p_yt_stream.add_argument("query_or_url", help="YouTube video ID, URL, or song search term")

    # --- Download Subcommands ---
    p_dl = subparsers.add_parser("download", help="Download audio streams to local disk")
    dl_subs = p_dl.add_subparsers(dest="dl_action", required=True)

    p_dl_song = dl_subs.add_parser("song", help="Download a single song from YouTube")
    p_dl_song.add_argument("query", help="Song search term or YouTube URL")
    p_dl_song.add_argument("-o", "--output-dir", default="./downloads", help="Output directory")
    p_dl_song.add_argument("--filename", help="Custom output filename (without ext)")

    p_dl_album = dl_subs.add_parser("album", help="Resolve strict album tracklist and download all tracks")
    p_dl_album.add_argument("--artist", required=True, help="Artist name")
    p_dl_album.add_argument("--title", required=True, help="Album title")
    p_dl_album.add_argument("--album-id", default="", help="Optional Deezer or iTunes ID")
    p_dl_album.add_argument("-o", "--output-dir", default="./downloads", help="Output directory")

    args = parser.parse_args()

    # --- Execution Logic ---
    if args.command == "deezer":
        client = DeezerClient()
        if args.deezer_action == "search-artist":
            res = client.search_artist(args.query, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.deezer_action == "search-album":
            res = client.search_album(args.title, artist=args.artist, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.deezer_action == "artist-albums":
            res = client.get_artist_albums(args.artist_id, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.deezer_action == "artist-top":
            res = client.get_artist_top_tracks(args.artist_id, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.deezer_action == "album-tracks":
            res = client.get_album_tracks(args.album_id)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.deezer_action == "search-track":
            res = client.search_track(args.title, artist=args.artist, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))

    elif args.command == "itunes":
        client = ItunesClient()
        if args.itunes_action == "search-album":
            res = client.search_album(args.title, artist=args.artist, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.itunes_action == "album-tracks":
            res = client.lookup_album_tracks(args.collection_id)
            print(json.dumps(res, indent=2, ensure_ascii=False))
        elif args.itunes_action == "search-song":
            res = client.search_song(args.title, artist=args.artist, limit=args.limit)
            print(json.dumps(res, indent=2, ensure_ascii=False))

    elif args.command == "album":
        tracks = resolve_album_tracks(artist=args.artist, title=args.title, album_id=args.album_id)
        if args.json:
            print(json.dumps(tracks, indent=2, ensure_ascii=False))
        else:
            print(f"\n=======================================================")
            print(f" Álbum: {args.title} | Artista: {args.artist}")
            print(f" Total pistas resueltas: {len(tracks)}")
            print(f"=======================================================")
            for t in tracks:
                dur_s = t["duration_ms"] // 1000
                m, s = divmod(dur_s, 60)
                disc = f"D{t['disc_number']} " if t.get("disc_number", 1) > 1 else ""
                print(f"  {disc}{t['track_number']:2d}. {t['title']:<45} ({m}:{s:02d}) [{t['source']}]")
            print()

    elif args.command == "youtube":
        yt = YouTubeExtractorClient()
        if args.yt_action == "search":
            hits = yt.search(args.query, limit=args.limit)
            print(json.dumps(hits, indent=2, ensure_ascii=False))
        elif args.yt_action == "extract-stream":
            stream = yt.extract_stream_url(args.query_or_url)
            if stream:
                print(json.dumps(stream, indent=2, ensure_ascii=False))
            else:
                print("Error: Could not extract audio stream (InnerTube bot detection or restricted video).", file=sys.stderr)
                sys.exit(1)

    elif args.command == "download":
        yt = YouTubeExtractorClient()
        if args.dl_action == "song":
            print(f"Resolving YouTube stream for '{args.query}'...")
            stream = yt.extract_stream_url(args.query)
            if not stream:
                print("Error: Audio stream could not be extracted.", file=sys.stderr)
                sys.exit(1)

            title = stream.get("title", "audio")
            author = stream.get("author", "Unknown Artist")
            filename = args.filename or re.sub(r'[\\/*?:"<>|]', "", f"{author} - {title}").strip()
            ext = "m4a" if "mp4" in stream.get("mime_type", "") else "webm"
            out_file = os.path.join(args.output_dir, f"{filename}.{ext}")
            print(f"Downloading to: {out_file}")
            download_file(stream["audio_url"], out_file, title=title, artist=author)

        elif args.dl_action == "album":
            tracks = resolve_album_tracks(artist=args.artist, title=args.title, album_id=args.album_id)
            if not tracks:
                print("Error: No album tracks found across Deezer or iTunes.", file=sys.stderr)
                sys.exit(1)

            clean_album_dir = re.sub(r'[\\/*?:"<>|]', "", f"{args.artist} - {args.title}").strip()
            target_dir = os.path.join(args.output_dir, clean_album_dir)
            os.makedirs(target_dir, exist_ok=True)

            print(f"\nDownloading album ({len(tracks)} tracks) to: {target_dir}\n")
            for t in tracks:
                t_num = t["track_number"]
                t_title = t["title"]
                t_art = t["artist"]
                q = f"{t_art} {t_title}"
                print(f"[{t_num}/{len(tracks)}] Resolving: {t_art} - {t_title}...")
                stream = yt.extract_stream_url(q)
                if not stream:
                    print(f"  -> Failed to resolve stream for track {t_num} ({t_title})", file=sys.stderr)
                    continue

                safe_title = re.sub(r'[\\/*?:"<>|]', "", t_title).strip()
                ext = "m4a" if "mp4" in stream.get("mime_type", "") else "webm"
                out_path = os.path.join(target_dir, f"{t_num:02d} - {safe_title}.{ext}")
                download_file(stream["audio_url"], out_path, title=t_title, artist=t_art, album=args.title, track_number=t_num)
            print("\nAlbum download process finished!")


if __name__ == "__main__":
    main()

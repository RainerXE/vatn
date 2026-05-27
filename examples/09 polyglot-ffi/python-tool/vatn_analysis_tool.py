#!/usr/bin/env python3
"""
vatn_analysis_tool.py

VATN FFI Demo — Python stdio bridge tool
Called by VATN via ProcessBuilder (JSON in → JSON out on stdout).

Use-case: Reuse existing Python ML/data-science libraries that have no
Java equivalent (pandas, scikit-learn, spacy, transformers, etc.)
without needing to embed CPython in the JVM.

Protocol (one request per process invocation):
  stdin:  single JSON line  { "action": "...", "params": { ... } }
  stdout: single JSON line  { "ok": true,  "result": { ... } }
                         or { "ok": false, "error": "..." }

The VATN plugin (VatnPythonAnalysisTool.java) launches this script,
writes the request, closes stdin, and reads stdout to completion.

Actions:
  word_frequency   params: { "text": "..." }
                   result: { "frequencies": { "word": count, ... }, "top": [...] }

  sentiment        params: { "text": "..." }
                   result: { "label": "positive|negative|neutral", "score": 0.0-1.0 }
                   NOTE: uses a simple lexicon approach — swap for
                   transformers.pipeline("sentiment-analysis") if available.

  summarise        params: { "text": "...", "sentences": 3 }
                   result: { "summary": "..." }
                   NOTE: uses basic extractive summarisation.
                   Swap body for sumy / transformers if available.
"""

import sys
import json
import re
from collections import Counter


# ── helpers ──────────────────────────────────────────────────────────────────

def tokenise(text: str) -> list[str]:
    return re.findall(r"\b[a-zA-Z']+\b", text.lower())


STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to",
    "for", "of", "with", "is", "it", "this", "that", "was", "are",
    "be", "as", "by", "from", "i", "you", "we", "he", "she", "they",
}

# Tiny sentiment lexicon — replace with a real library for production
POSITIVE_WORDS = {
    "good", "great", "excellent", "amazing", "wonderful", "fantastic",
    "love", "happy", "success", "win", "best", "perfect", "brilliant",
    "outstanding", "superb", "joy", "enjoy", "recommend", "fast", "easy",
}
NEGATIVE_WORDS = {
    "bad", "terrible", "awful", "horrible", "hate", "fail", "worst",
    "poor", "slow", "broken", "crash", "error", "bug", "wrong",
    "disappointing", "frustrating", "useless", "annoying", "problem",
}


# ── action handlers ──────────────────────────────────────────────────────────

def word_frequency(params: dict) -> dict:
    text = params.get("text", "")
    tokens = [t for t in tokenise(text) if t not in STOPWORDS]
    freq = Counter(tokens)
    top_n = params.get("top_n", 10)
    top = [{"word": w, "count": c} for w, c in freq.most_common(top_n)]
    return {"frequencies": dict(freq), "top": top, "total_tokens": len(tokens)}


def sentiment(params: dict) -> dict:
    text = params.get("text", "")
    tokens = set(tokenise(text))
    pos_hits = tokens & POSITIVE_WORDS
    neg_hits = tokens & NEGATIVE_WORDS
    pos_score = len(pos_hits)
    neg_score = len(neg_hits)
    total = pos_score + neg_score or 1
    if pos_score > neg_score:
        label = "positive"
        score = round(pos_score / total, 3)
    elif neg_score > pos_score:
        label = "negative"
        score = round(neg_score / total, 3)
    else:
        label = "neutral"
        score = 0.5
    return {
        "label": label,
        "score": score,
        "positive_hits": list(pos_hits),
        "negative_hits": list(neg_hits),
    }


def summarise(params: dict) -> dict:
    """Extractive summarisation: pick the N highest word-frequency sentences."""
    text = params.get("text", "")
    n_sentences = max(1, params.get("sentences", 3))
    sentences = re.split(r"(?<=[.!?])\s+", text.strip())
    if len(sentences) <= n_sentences:
        return {"summary": text}
    freq = Counter(t for t in tokenise(text) if t not in STOPWORDS)
    scored = []
    for s in sentences:
        score = sum(freq.get(t, 0) for t in tokenise(s))
        scored.append((score, s))
    scored.sort(key=lambda x: -x[0])
    selected = [s for _, s in scored[:n_sentences]]
    # Restore original order
    ordered = [s for s in sentences if s in selected]
    return {"summary": " ".join(ordered)}


ACTIONS = {
    "word_frequency": word_frequency,
    "sentiment": sentiment,
    "summarise": summarise,
}


# ── entry point ──────────────────────────────────────────────────────────────

def main():
    raw = sys.stdin.read().strip()
    if not raw:
        print(json.dumps({"ok": False, "error": "empty stdin"}))
        return

    try:
        request = json.loads(raw)
    except json.JSONDecodeError as e:
        print(json.dumps({"ok": False, "error": f"invalid JSON: {e}"}))
        return

    action = request.get("action")
    params = request.get("params", {})

    handler = ACTIONS.get(action)
    if handler is None:
        known = list(ACTIONS.keys())
        print(json.dumps({"ok": False, "error": f"unknown action '{action}'. known: {known}"}))
        return

    try:
        result = handler(params)
        print(json.dumps({"ok": True, "result": result}))
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}))


if __name__ == "__main__":
    main()
